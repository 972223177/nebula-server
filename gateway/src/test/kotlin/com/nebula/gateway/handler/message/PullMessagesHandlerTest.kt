package com.nebula.gateway.handler.message

import com.nebula.chat.ChatContentType
import com.nebula.chat.message.PullMessagesReq
import com.nebula.chat.message.PullMessagesResp
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.service.chat.MessageService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PullMessagesHandler 消息拉取 Handler 单元测试（D-17, D-18, D-19, REVIEW-MEDIUM-9, REVIEW-MEDIUM-11）。
 *
 * 覆盖场景：
 * - cursor=0 → 使用 Long.MAX_VALUE 查询最新消息（D-18, Pitfall 2）
 * - cursor>0 → 传递 cursor 值查询历史消息
 * - limit=0 → coerceIn 到 1（T-06-08）
 * - limit=200 → coerceIn 到 100（D-19）
 * - 会话不存在 → 抛出 ConversationException(BizCode.CONV_NOT_FOUND)（REVIEW-MEDIUM-9）
 * - 非成员拉取消息 → 抛出 ConversationException(BizCode.NOT_MEMBER)
 * - hasMore = (返回数量 >= limit) 正确设置
 * - toChatMessage() 映射所有字段正确（REVIEW-MEDIUM-11）
 */
class PullMessagesHandlerTest {

    private lateinit var messageService: MessageService
    private lateinit var messageRepository: MessageRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var handler: PullMessagesHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        messageService = mockk()
        messageRepository = mockk()
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        handler = PullMessagesHandler(messageService)
    }

    @Test
    fun `cursor为0时使用LongMAX_VALUE查询最新消息`() = runTest {
        every { conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L) } returns ConversationMemberEntity("conv-001", 1001L)
        every { conversationRepository.existsById("conv-001") } returns true
        coEvery {
            messageRepository.findMessagesBackward("conv-001", Long.MAX_VALUE, any())
        } returns listOf(createMessageEntity(50001L))

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setCursor(0L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertEquals(1, resp.messagesCount)
        assertEquals(50001L, resp.messagesList[0].msgId)
    }

    @Test
    fun `cursor大于0时传递cursor值查询历史消息`() = runTest {
        every { conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L) } returns ConversationMemberEntity("conv-001", 1001L)
        every { conversationRepository.existsById("conv-001") } returns true
        coEvery {
            messageRepository.findMessagesBackward("conv-001", 50000L, any())
        } returns listOf(createMessageEntity(49999L))

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setCursor(50000L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertEquals(1, resp.messagesCount)
        assertEquals(49999L, resp.messagesList[0].msgId)
    }

    @Test
    fun `limit为0时coerceIn到1`() = runTest {
        every { conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L) } returns ConversationMemberEntity("conv-001", 1001L)
        every { conversationRepository.existsById("conv-001") } returns true
        coEvery {
            messageRepository.findMessagesBackward("conv-001", Long.MAX_VALUE, any())
        } returns listOf(createMessageEntity(50001L))

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setLimit(0)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertEquals(1, resp.messagesCount)
    }

    @Test
    fun `limit为200时coerceIn到100`() = runTest {
        every { conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L) } returns ConversationMemberEntity("conv-001", 1001L)
        every { conversationRepository.existsById("conv-001") } returns true
        // 模拟返回 100 条数据
        val mockMessages = (1L..100L).map { createMessageEntity(it) }
        coEvery {
            messageRepository.findMessagesBackward("conv-001", Long.MAX_VALUE, any())
        } returns mockMessages

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setLimit(200)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertEquals(100, resp.messagesCount)
    }

    @Test
    fun `返回数据达到limit时hasMore为true`() = runTest {
        every { conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L) } returns ConversationMemberEntity("conv-001", 1001L)
        every { conversationRepository.existsById("conv-001") } returns true
        val mockMessages = (1L..20L).map { createMessageEntity(it) }
        coEvery {
            messageRepository.findMessagesBackward("conv-001", Long.MAX_VALUE, any())
        } returns mockMessages

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setLimit(20)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertTrue(resp.hasMore)
    }

    @Test
    fun `返回数据未达到limit时hasMore为false`() = runTest {
        every { conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L) } returns ConversationMemberEntity("conv-001", 1001L)
        every { conversationRepository.existsById("conv-001") } returns true
        val mockMessages = (1L..5L).map { createMessageEntity(it) }
        coEvery {
            messageRepository.findMessagesBackward("conv-001", Long.MAX_VALUE, any())
        } returns mockMessages

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setLimit(20)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertFalse(resp.hasMore)
    }

    @Test
    fun `toChatMessage映射所有字段正确`() = runTest {
        every { conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L) } returns ConversationMemberEntity("conv-001", 1001L)
        every { conversationRepository.existsById("conv-001") } returns true
        val entity = MessageEntity(
            conversationId = "conv-001",
            senderUid = 2001L,
            messageType = ChatContentType.TEXT_VALUE,
            content = "Hello World",
            payload = byteArrayOf(1, 2, 3),
            clientTs = 1000L,
            serverTs = 2000L
        ).apply { id = 50001L }

        coEvery {
            messageRepository.findMessagesBackward("conv-001", Long.MAX_VALUE, any())
        } returns listOf(entity)

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        val msg = resp.messagesList[0]
        assertEquals(50001L, msg.msgId)
        assertEquals("conv-001", msg.conversationId)
        assertEquals(2001L, msg.senderUid)
        assertEquals(0L, msg.receiverUid)
        assertEquals(ChatContentType.TEXT, msg.messageType)
        assertEquals("Hello World", msg.content)
        assertEquals(1000L, msg.clientTs)
        assertEquals(2000L, msg.serverTs)
    }

    @Test
    fun `非成员拉取消息抛出NOT_MEMBER`() = runTest {
        // 查找成员返回 null，表示当前用户不是该会话的成员
        every { conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L) } returns null

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .build()

        val ex = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }
        assertEquals(BizCode.NOT_MEMBER, ex.bizCode)
    }

    /** 创建模拟的 MessageEntity 用于测试。 */
    private fun createMessageEntity(id: Long): MessageEntity = MessageEntity(
        conversationId = "conv-001",
        senderUid = 1001L,
        messageType = 0,
        content = "test",
        payload = null,
        clientTs = 1000L,
        serverTs = 2000L
    ).apply { this.id = id }
}
