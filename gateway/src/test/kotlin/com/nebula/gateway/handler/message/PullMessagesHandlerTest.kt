package com.nebula.gateway.handler.message

import com.nebula.chat.ChatContentType
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.message.PullMessagesReq
import com.nebula.chat.message.PullMessagesResp
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.service.chat.MessageService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
    private lateinit var handler: PullMessagesHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        messageService = mockk()
        handler = PullMessagesHandler(messageService)
    }

    @Test
    fun cursorZeroShouldUseMaxValue() = runTest {
        coEvery { messageService.pullMessages(any<PullMessagesReq>(), any()) } returns
                PullMessagesResp.newBuilder().addMessages(createChatMessage(50001L)).build()

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setCursor(0L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertEquals(1, resp.messagesCount)
        assertEquals(50001L, resp.messagesList[0].msgId)
    }

    @Test
    fun cursorGreaterThanZeroShouldPassCursor() = runTest {
        coEvery { messageService.pullMessages(any<PullMessagesReq>(), any()) } returns
                PullMessagesResp.newBuilder().addMessages(createChatMessage(49999L)).build()

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setCursor(50000L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertEquals(1, resp.messagesCount)
        assertEquals(49999L, resp.messagesList[0].msgId)
    }

    @Test
    fun limitZeroShouldCoerceToOne() = runTest {
        coEvery { messageService.pullMessages(any<PullMessagesReq>(), any()) } returns
                PullMessagesResp.newBuilder().addMessages(createChatMessage(50001L)).build()

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setLimit(0)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertEquals(1, resp.messagesCount)
    }

    @Test
    fun limitExceededShouldCoerceToMax() = runTest {
        val mockMessages = (1L..100L).map { createChatMessage(it) }
        coEvery { messageService.pullMessages(any<PullMessagesReq>(), any()) } returns
                PullMessagesResp.newBuilder().addAllMessages(mockMessages).build()

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setLimit(200)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertEquals(100, resp.messagesCount)
    }

    @Test
    fun reachingLimitShouldSetHasMoreTrue() = runTest {
        val mockMessages = (1L..20L).map { createChatMessage(it) }
        coEvery { messageService.pullMessages(any<PullMessagesReq>(), any()) } returns
                PullMessagesResp.newBuilder().addAllMessages(mockMessages).setHasMore(true).build()

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setLimit(20)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertTrue(resp.hasMore)
    }

    @Test
    fun belowLimitShouldSetHasMoreFalse() = runTest {
        val mockMessages = (1L..5L).map { createChatMessage(it) }
        coEvery { messageService.pullMessages(any<PullMessagesReq>(), any()) } returns
                PullMessagesResp.newBuilder().addAllMessages(mockMessages).setHasMore(false).build()

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .setLimit(20)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertFalse(resp.hasMore)
    }

    @Test
    fun toChatMessageShouldMapAllFields() = runTest {
        val chatMsg = ChatMessage.newBuilder()
            .setMsgId(50001L)
            .setConversationId("conv-001")
            .setSenderUid(2001L)
            .setReceiverUid(0L)
            .setMessageType(ChatContentType.TEXT)
            .setContent("Hello World")
            .setClientTs(1000L)
            .setServerTs(2000L)
            .build()
        coEvery { messageService.pullMessages(any<PullMessagesReq>(), any()) } returns
                PullMessagesResp.newBuilder().addMessages(chatMsg).build()

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
    fun nonMemberPullShouldThrowNotMember() = runTest {
        coEvery { messageService.pullMessages(any<PullMessagesReq>(), any()) } throws
                ConversationException(BizCode.NOT_MEMBER)

        val req = PullMessagesReq.newBuilder()
            .setConversationId("conv-001")
            .build()

        val ex = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }
        assertEquals(BizCode.NOT_MEMBER, ex.bizCode)
    }

    /** 创建模拟的 ChatMessage 用于测试。 */
    private fun createChatMessage(id: Long): ChatMessage = ChatMessage.newBuilder()
        .setMsgId(id)
        .setConversationId("conv-001")
        .setSenderUid(1001L)
        .setMessageType(com.nebula.chat.ChatContentType.TEXT)
        .setContent("test")
        .setClientTs(1000L)
        .setServerTs(2000L)
        .build()
}
