package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.message.ChatMessage
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.service.chat.MessageService
import com.nebula.service.chat.SendMessageResult
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SendMessageHandler 单元测试（D-04, D-13, D-72）。
 *
 * D-72：Redis SETNX 去重逻辑已下沉到 MessageQueueRepository.checkAndSetDedup() 中，
 * handler 层不再处理去重。
 *
 * 覆盖场景：
 * - 正常发送 → MessageService 返回 SendMessageResult，返回 SendMessageResp
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SendMessageHandlerTest {

    private lateinit var messageService: MessageService
    private lateinit var pushService: PushService
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var scope: CoroutineScope
    private lateinit var handler: SendMessageHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        messageService = mockk()
        pushService = mockk<PushService>(relaxed = true)
        conversationMemberRepository = mockk<ConversationMemberRepository>(relaxed = true)
        connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        handler = SendMessageHandler(messageService, pushService, conversationMemberRepository, connection, scope)
    }

    @Test
    fun sendShouldReturnSendMessageResp() = runTest {
        // MessageService 返回成功结果
        val convEntity = ConversationEntity(type = 0).apply { id = "conv-001" }
        val chatMsg = ChatMessage.newBuilder()
            .setMsgId(50001L)
            .setConversationId("conv-001")
            .setSenderUid(1001L)
            .build()
        coEvery { messageService.sendMessage(any(), any()) } returns SendMessageResult(
            msgId = 50001L,
            serverTs = 1700000000000L,
            conversationId = "conv-001",
            senderUid = 1001L,
            chatMessage = chatMsg,
            conversation = convEntity
        )

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()

        val resp = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        assertNotNull(resp)
        assertEquals(50001L, resp.msgId, "应返回 MessageService 设置的 msgId")
        assertTrue(resp.serverTs > 0, "应返回服务端时间戳")
    }
}
