package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.InviteMemberReq
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
import io.mockk.mockk
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
 * InviteMemberHandler 邀请成员 Handler 单元测试（D-03, D-05, D-19）。
 *
 * 覆盖场景：
 * - 正常邀请返回 Response(code=0)
 * - 群满(当前195人+邀请10人>200)抛 GROUP_FULL
 * - 被邀请者已在群中抛 ALREADY_IN_GROUP
 * - inviter 非成员抛 NOT_MEMBER
 * - 会话已解散抛 GROUP_DISSOLVED
 * - MEMBER_JOINED 推送现有成员
 */
class InviteMemberHandlerTest {

    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var lockManager: ConversationLockManager
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var pushService: PushService
    private lateinit var handler: InviteMemberHandler

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

        handler = InviteMemberHandler(
            conversationRepository,
            conversationMemberRepository,
            lockManager,
            transactionTemplate,
            pushService
        )
    }

    @Test
    fun `正常邀请返回Responsecode为0`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val inviterMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "member" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns inviterMember
        // 新邀请的 uids 不在群中
        every {
            conversationMemberRepository.findByConversationIdAndUserIds("conv-001", listOf(2001L, 3001L))
        } returns emptyList()
        every { conversationMemberRepository.countActiveByConversationId("conv-001") } returns 5L
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .addAllUids(listOf(2001L, 3001L))
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)
    }

    @Test
    fun `群满当前195人加邀请10人超200抛GROUP_FULL`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 195
        }
        val inviterMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "member" }
        // 邀请 10 人，保证超出上限（当前 195 + 10 = 205 > 200）
        val invite10Uids = (2001L..2010L).toList()

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns inviterMember
        every {
            conversationMemberRepository.findByConversationIdAndUserIds("conv-001", invite10Uids)
        } returns emptyList()
        every { conversationMemberRepository.countActiveByConversationId("conv-001") } returns 195L

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .addAllUids(invite10Uids)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_FULL, exception.bizCode)
    }

    @Test
    fun `被邀请者已在群中抛ALREADY_IN_GROUP`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val inviterMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "member" }
        val existingMember = ConversationMemberEntity("conv-001", 2001L).apply { role = "member" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns inviterMember
        // 被邀请者已在群中
        every {
            conversationMemberRepository.findByConversationIdAndUserIds("conv-001", listOf(2001L))
        } returns listOf(existingMember)

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .addUids(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.ALREADY_IN_GROUP, exception.bizCode)
    }

    @Test
    fun `inviter非成员抛NOT_MEMBER`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        // 邀请者不在群中
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns null

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .addUids(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    @Test
    fun `会话已解散抛GROUP_DISSOLVED`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 1; memberCount = 5
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .addUids(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_DISSOLVED, exception.bizCode)
    }

    @Test
    fun `MEMBER_JOINED推送现有成员`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val inviterMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "member" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns inviterMember
        every {
            conversationMemberRepository.findByConversationIdAndUserIds("conv-001", listOf(2001L, 3001L))
        } returns emptyList()
        every { conversationMemberRepository.countActiveByConversationId("conv-001") } returns 5L
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .addAllUids(listOf(2001L, 3001L))
            .build()
        withContext(SessionKey(session)) { handler.handle(req) }

        // 验证推送给现有成员（排除新加入者）
        coVerify {
            pushService.pushConversationEvent(
                convId = "conv-001",
                eventType = PushEventType.MEMBER_JOINED,
                payloadBytes = any(),
                excludeUids = setOf(2001L, 3001L)
            )
        }
    }
}
