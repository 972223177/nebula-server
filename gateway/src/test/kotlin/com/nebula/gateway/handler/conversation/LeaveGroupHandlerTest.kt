package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.LeaveGroupReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.mockLockManager
import com.nebula.gateway.testutil.mockTransactionTemplate
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.service.conversation.ConversationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * LeaveGroupHandler 退群/解散群 Handler 单元测试（D-04, D-09, D-19）。
 *
 * 覆盖场景：
 * - 群主退群 → 会话 status=DISSOLVED，推送 GROUP_DISSOLVED
 * - 普通成员退群 → 软删除，推送 MEMBER_LEFT
 * - 非成员退群抛 NOT_MEMBER
 * - 已解散群退群抛 GROUP_DISSOLVED
 */
class LeaveGroupHandlerTest {

    private lateinit var conversationService: ConversationService
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var pushService: PushService
    private lateinit var handler: LeaveGroupHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        pushService = mockk(relaxed = true)

        val lockManager = mockLockManager()
        val transactionTemplate = mockTransactionTemplate()

        handler = LeaveGroupHandler(
            conversationService,
            lockManager,
            transactionTemplate,
            pushService,
            conversationMemberRepository
        )
    }

    @Test
    fun `群主退群解散群并推送GROUP_DISSOLVED`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "owner" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        coEvery {
            conversationMemberRepository.softDeleteAllByConversationId("conv-001")
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val req = LeaveGroupReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)

        // 验证推送 GROUP_DISSOLVED
        coVerify {
            pushService.pushConversationEvent(
                convId = "conv-001",
                eventType = PushEventType.GROUP_DISSOLVED,
                payloadBytes = any()
            )
        }
    }

    @Test
    fun `普通成员退群软删除自己并推送MEMBER_LEFT`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val normalMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "member" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns normalMember
        coEvery {
            conversationMemberRepository.softDeleteByConversationIdAndUserId("conv-001", 1001L)
        } just runs
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }

        val req = LeaveGroupReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)

        // 验证推送 MEMBER_LEFT，排除退群者自己
        coVerify {
            pushService.pushConversationEvent(
                convId = "conv-001",
                eventType = PushEventType.MEMBER_LEFT,
                payloadBytes = any(),
                excludeUids = setOf(1001L)
            )
        }
    }

    @Test
    fun `非成员退群抛NOT_MEMBER`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        // 当前用户不在群中
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns null

        val req = LeaveGroupReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    @Test
    fun `会话不存在抛CONV_NOT_FOUND`() = runTest {
        every { conversationRepository.findById("conv-missing") } returns Optional.empty()

        val req = LeaveGroupReq.newBuilder()
            .setConversationId("conv-missing")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.CONV_NOT_FOUND, exception.bizCode)
    }

    @Test
    fun `已解散群退群抛GROUP_DISSOLVED`() = runTest {
        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 1; memberCount = 0
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)

        val req = LeaveGroupReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_DISSOLVED, exception.bizCode)
    }
}
