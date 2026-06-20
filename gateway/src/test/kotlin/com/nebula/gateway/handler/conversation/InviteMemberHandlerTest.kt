package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.InviteMemberReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.mockLockManager
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

    private lateinit var conversationService: ConversationService
    private lateinit var pushService: PushService
    private lateinit var handler: InviteMemberHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        pushService = mockk(relaxed = true)

        val lockManager = mockLockManager()

        handler = InviteMemberHandler(
            conversationService,
            lockManager,
            pushService
        )
    }

    @Test
    fun inviteShouldReturnOkCode() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } returns listOf(2001L, 3001L)

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .addAllUids(listOf(2001L, 3001L))
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)
    }

    @Test
    fun groupFullShouldThrowGroupFull() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } throws ConversationException(BizCode.GROUP_FULL)

        val invite10Uids = (2001L..2010L).toList()

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
    fun alreadyInGroupShouldThrowAlreadyInGroup() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } throws ConversationException(BizCode.ALREADY_IN_GROUP)

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
    fun inviterNonMemberShouldThrowNotMember() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } throws ConversationException(BizCode.NOT_MEMBER)

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
    fun inviteConvNotFoundShouldThrowConvNotFound() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } throws ConversationException(BizCode.CONV_NOT_FOUND)

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-missing")
            .addUids(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.CONV_NOT_FOUND, exception.bizCode)
    }

    @Test
    fun emptyInviteListShouldThrowInvalidParam() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } throws ConversationException(BizCode.INVALID_PARAM)

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
    }

    @Test
    fun partialExistingMembersShouldAddNewOnly() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } returns listOf(2001L, 4001L, 5001L)

        val req = InviteMemberReq.newBuilder()
            .setConversationId("conv-001")
            .addAllUids(listOf(2001L, 3001L, 4001L, 5001L))
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)

        // 验证推送排除已在群中的 3001L，仅包含新成员 2001L/4001L/5001L
        coVerify {
            pushService.pushConversationEvent(
                convId = "conv-001",
                eventType = PushEventType.MEMBER_JOINED,
                payloadBytes = any(),
                excludeUids = setOf(2001L, 4001L, 5001L)
            )
        }
    }

    @Test
    fun inviteDissolvedGroupShouldThrowGroupDissolved() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } throws ConversationException(BizCode.GROUP_DISSOLVED)

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
    fun memberJoinedShouldPushToExistingMembers() = runTest {
        coEvery { conversationService.inviteMember(any(), any()) } returns listOf(2001L, 3001L)

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

    @Test
    fun handleShouldRequireSession() = runTest {
        val exception = kotlin.test.assertFailsWith<com.nebula.common.exception.BizException> {
            val req = com.nebula.chat.conversation.InviteMemberReq.getDefaultInstance()
            handler.handle(req)
        }
        kotlin.test.assertEquals(com.nebula.common.BizCode.UNAUTHORIZED, exception.bizCode, "无 Session 时应抛出 UNAUTHORIZED")
    }

}
