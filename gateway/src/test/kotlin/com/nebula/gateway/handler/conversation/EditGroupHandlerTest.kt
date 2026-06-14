package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.conversation.EditGroupReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
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
import kotlin.test.assertTrue

/**
 * EditGroupHandler 编辑群信息 Handler 单元测试（D-15）。
 *
 * 覆盖场景：
 * - 只改 name → ConversationEntity.name 更新
 * - 只改 avatar_url → ConversationEntity.avatar 更新
 * - 两个参数都不传 → INVALID_PARAM
 * - 非群主编辑 → GROUP_PERM_DENIED
 * - name 超过 128 字符 → INVALID_PARAM
 * - avatar_url 超过 256 字符 → INVALID_PARAM
 * - 正常编辑推送 GROUP_UPDATED
 */
class EditGroupHandlerTest {

    private lateinit var conversationService: ConversationService
    private lateinit var pushService: PushService
    private lateinit var handler: EditGroupHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        pushService = mockk(relaxed = true)
        handler = EditGroupHandler(conversationService, pushService)
    }

    @Test
    fun editNameShouldUpdateConversationEntityName() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } returns Unit

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setName("新群名")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)
    }

    @Test
    fun editAvatarUrlShouldUpdateConversationEntityAvatar() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } returns Unit

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setAvatarUrl("http://new-avatar.com/1.png")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)
    }

    @Test
    fun noParametersShouldThrowInvalidParam() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } throws ConversationException(BizCode.INVALID_PARAM)

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
        assertTrue(exception.message?.contains("至少修改") == true)
    }

    @Test
    fun conversationNotFoundShouldThrowConvNotFound() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } throws ConversationException(BizCode.CONV_NOT_FOUND)

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-missing")
            .setName("新群名")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.CONV_NOT_FOUND, exception.bizCode)
    }

    @Test
    fun dissolvedGroupShouldThrowGroupDissolved() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } throws ConversationException(BizCode.GROUP_DISSOLVED)

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setName("新群名")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_DISSOLVED, exception.bizCode)
    }

    @Test
    fun nonMemberEditShouldThrowNotMember() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } throws ConversationException(BizCode.NOT_MEMBER)

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setName("新群名")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    @Test
    fun nonOwnerEditShouldThrowGroupPermDenied() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } throws ConversationException(BizCode.GROUP_PERM_DENIED)

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setName("新群名")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_PERM_DENIED, exception.bizCode)
    }

    @Test
    fun editNameExceeding128CharsShouldThrowInvalidParam() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } throws ConversationException(BizCode.INVALID_PARAM)

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setName("a".repeat(129))
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
        assertTrue(exception.message?.contains("128") == true)
    }

    @Test
    fun avatarUrlExceeding256CharsShouldThrowInvalidParam() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } throws ConversationException(BizCode.INVALID_PARAM)

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setAvatarUrl("x".repeat(257))
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
        assertTrue(exception.message?.contains("256") == true)
    }

    @Test
    fun editShouldPushGroupUpdated() = runTest {
        coEvery { conversationService.editGroupInfo(any(), any()) } returns Unit

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setName("新名")
            .setAvatarUrl("http://a.com")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)

        // 验证推送给所有成员
        coVerify {
            pushService.pushConversationEvent(
                convId = "conv-001",
                eventType = PushEventType.GROUP_UPDATED,
                payloadBytes = any()
            )
        }
    }
}
