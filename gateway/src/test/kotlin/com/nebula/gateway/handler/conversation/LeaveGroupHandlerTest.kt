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
import com.nebula.service.conversation.ConversationMemberInfo
import com.nebula.service.conversation.ConversationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
    private lateinit var pushService: PushService
    private lateinit var handler: LeaveGroupHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        pushService = mockk(relaxed = true)

        val lockManager = mockLockManager()
        val transactionTemplate = mockTransactionTemplate()

        handler = LeaveGroupHandler(
            conversationService,
            lockManager,
            transactionTemplate,
            pushService
        )
    }

    @Test
    fun ownerLeaveShouldDissolveAndPushGroupDissolved() = runTest {
        coEvery { conversationService.leaveGroup(any(), any()) } returns Unit

        // Handle 直接查询当前用户成员信息，判断角色
        val ownerMember = ConversationMemberInfo(userId = 1001L, role = "owner")
        coEvery {
            conversationService.getMemberRole("conv-001", 1001L)
        } returns ownerMember

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
    fun memberLeaveShouldSoftDeleteAndPushMemberLeft() = runTest {
        coEvery { conversationService.leaveGroup(any(), any()) } returns Unit

        val normalMember = ConversationMemberInfo(userId = 1001L, role = "member")
        coEvery {
            conversationService.getMemberRole("conv-001", 1001L)
        } returns normalMember

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
    fun leaveNonMemberShouldThrowNotMember() = runTest {
        coEvery { conversationService.leaveGroup(any(), any()) } throws ConversationException(BizCode.NOT_MEMBER)

        // 当前用户不在群中 → 进入 else 分支 → leaveGroup 抛出
        coEvery {
            conversationService.getMemberRole("conv-001", 1001L)
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
    fun leaveConvNotFoundShouldThrowConvNotFound() = runTest {
        coEvery {
            conversationService.getMemberRole("conv-missing", 1001L)
        } returns null
        coEvery { conversationService.leaveGroup(any(), any()) } throws ConversationException(BizCode.CONV_NOT_FOUND)

        val req = LeaveGroupReq.newBuilder()
            .setConversationId("conv-missing")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.CONV_NOT_FOUND, exception.bizCode)
    }

    @Test
    fun leaveDissolvedGroupShouldThrowGroupDissolved() = runTest {
        val normalMember = ConversationMemberInfo(userId = 1001L, role = "member")
        coEvery {
            conversationService.getMemberRole("conv-001", 1001L)
        } returns normalMember
        coEvery { conversationService.leaveGroup(any(), any()) } throws ConversationException(BizCode.GROUP_DISSOLVED)

        val req = LeaveGroupReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_DISSOLVED, exception.bizCode)
    }
}
