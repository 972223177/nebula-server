package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.EditGroupReq
import com.nebula.chat.conversation.GroupUpdatedPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.service.conversation.ConversationService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
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
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var pushService: PushService
    private lateinit var handler: EditGroupHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        pushService = mockk(relaxed = true)
        handler = EditGroupHandler(conversationService, pushService)
    }

    @Test
    fun `只改name名更新ConversationEntity的name字段`() = runTest {
        val conv = ConversationEntity(type = 1).apply {
            id = "conv-001"
            name = "旧群名"
            groupOwnerUid = 1001L
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply {
            role = "owner"
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(conv)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        every { conversationRepository.save(any()) } answers { firstArg() }

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setName("新群名")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)
        // 验证 entity.name 已更新
        assertEquals("新群名", conv.name)
        // 验证 avatar 未变
        assertEquals("", conv.avatar)
    }

    @Test
    fun `只改avatar_url更新ConversationEntity的avatar字段`() = runTest {
        val conv = ConversationEntity(type = 1).apply {
            id = "conv-001"
            avatar = "http://old.com/a.png"
            groupOwnerUid = 1001L
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply {
            role = "owner"
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(conv)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        every { conversationRepository.save(any()) } answers { firstArg() }

        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setAvatarUrl("http://new-avatar.com/1.png")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)
        // 验证 avatar 已更新
        assertEquals("http://new-avatar.com/1.png", conv.avatar)
        // 验证 name 未变
        assertEquals("", conv.name)
    }

    @Test
    fun `两个参数都不传抛INVALID_PARAM`() = runTest {
        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
        assertTrue(exception.message!!.contains("至少修改"))
    }

    @Test
    fun `会话不存在抛CONV_NOT_FOUND`() = runTest {
        every { conversationRepository.findById("conv-missing") } returns Optional.empty()

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
    fun `已解散群编辑抛GROUP_DISSOLVED`() = runTest {
        val conv = ConversationEntity(type = 1).apply {
            id = "conv-001"; status = 1
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(conv)

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
    fun `非成员编辑抛NOT_MEMBER`() = runTest {
        val conv = ConversationEntity(type = 1).apply {
            id = "conv-001"
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(conv)
        // 请求者不是该会话的成员
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns null

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
    fun `非群主编辑抛GROUP_PERM_DENIED`() = runTest {
        val conv = ConversationEntity(type = 1).apply {
            id = "conv-001"
            groupOwnerUid = 2002L // 群主是另一个用户
        }
        val normalMember = ConversationMemberEntity("conv-001", 1001L).apply {
            role = "member"
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(conv)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns normalMember

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
    fun `name超过128字符抛INVALID_PARAM`() = runTest {
        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setName("a".repeat(129))
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
        assertTrue(exception.message!!.contains("128"))
    }

    @Test
    fun `avatar_url超过256字符抛INVALID_PARAM`() = runTest {
        val req = EditGroupReq.newBuilder()
            .setConversationId("conv-001")
            .setAvatarUrl("x".repeat(257))
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
        assertTrue(exception.message!!.contains("256"))
    }

    @Test
    fun `正常编辑推送GROUP_UPDATED`() = runTest {
        val conv = ConversationEntity(type = 1).apply {
            id = "conv-001"
            name = "旧群名"
            avatar = "http://old.com/a.png"
            groupOwnerUid = 1001L
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply {
            role = "owner"
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(conv)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        every { conversationRepository.save(any()) } answers { firstArg() }

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
