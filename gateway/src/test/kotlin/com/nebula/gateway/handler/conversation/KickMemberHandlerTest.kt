package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.KickMemberReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * KickMemberHandler 踢出成员 Handler 单元测试（D-04, D-14, D-19）。
 *
 * 覆盖场景：
 * - 正常踢人 → 软删除，推送 MEMBER_KICKED + MEMBER_LEFT
 * - 踢群主抛 GROUP_PERM_DENIED
 * - 踢自己抛 INVALID_PARAM
 * - 非群主踢人抛 GROUP_PERM_DENIED
 * - 被踢者非成员抛 NOT_MEMBER
 * - 群已解散抛 GROUP_DISSOLVED
 */
class KickMemberHandlerTest {

    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var lockManager: ConversationLockManager
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var pushService: PushService
    private lateinit var handler: KickMemberHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        lockManager = mockk()
        transactionTemplate = mockk()
        pushService = mockk(relaxed = true)

        // Mock 锁管理器：直接执行代码块
        coEvery { lockManager.withLock(any(), any<suspend () -> kotlin.Any>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[1] as suspend () -> kotlin.Any).invoke()
        }

        // Mock 事务模板：在事务内执行回调
        every { transactionTemplate.execute(any<org.springframework.transaction.support.TransactionCallback<Any?>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[0] as org.springframework.transaction.support.TransactionCallback<Any?>)
                .doInTransaction(mockk(relaxed = true))
        }

        handler = KickMemberHandler(
            conversationRepository,
            conversationMemberRepository,
            lockManager,
            transactionTemplate,
            pushService
        )
    }

    @Test
    fun `正常踢人软删除目标并推送双事件`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "owner" }
        val targetMember = ConversationMemberEntity("conv-001", 2001L).apply { role = "member" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 2001L)
        } returns targetMember
        coEvery {
            conversationMemberRepository.softDeleteByConversationIdAndUserId("conv-001", 2001L)
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)

        // 验证推送给被踢者
        verify {
            pushService.pushEventToUser(
                targetUid = 2001L,
                eventType = PushEventType.MEMBER_KICKED,
                payloadBytes = any()
            )
        }

        // 验证推送给剩余成员（排除被踢者）
        coVerify {
            pushService.pushConversationEvent(
                convId = "conv-001",
                eventType = PushEventType.MEMBER_LEFT,
                payloadBytes = any(),
                excludeUids = setOf(2001L)
            )
        }
    }

    @Test
    fun `踢群主抛GROUP_PERM_DENIED`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "owner" }
        // 被踢目标也是群主
        val targetOwner = ConversationMemberEntity("conv-001", 2001L).apply { role = "owner" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 2001L)
        } returns targetOwner

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_PERM_DENIED, exception.bizCode)
    }

    @Test
    fun `踢自己抛INVALID_PARAM`() = runTest {
        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(1001L) // 与 session.userId 相同
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
    }

    @Test
    fun `非群主踢人抛GROUP_PERM_DENIED`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        // 当前用户是普通成员
        val normalMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "member" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns normalMember

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_PERM_DENIED, exception.bizCode)
    }

    @Test
    fun `被踢者非成员抛NOT_MEMBER`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "owner" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        // 被踢者不在群中
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 2001L)
        } returns null

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    @Test
    fun `群已解散抛GROUP_DISSOLVED`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 1; memberCount = 0
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_DISSOLVED, exception.bizCode)
    }
}
