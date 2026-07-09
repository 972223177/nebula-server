package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.message.ChatMessage
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.sessionContext
import com.nebula.service.conversation.ConversationService
import com.nebula.service.chat.MessageService
import com.nebula.service.chat.SendMessageResult
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SendMessageHandler 单元测试（D-04, D-13, D-72）。
 *
 * D-72：Redis SETNX 去重逻辑已下沉到 MessageService.checkAndSetDedup() 中，
 * handler 层不再处理去重。
 *
 * 覆盖场景：
 * - 正常发送 → MessageService 返回 SendMessageResult，返回 SendMessageResp
 * - Step 链 SendMessageException → 直接传播（D-09）
 * - 非预期异常 → 包装为 BizException(INTERNAL_ERROR)（REVIEW-HIGH-2）
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SendMessageHandlerTest {

    private lateinit var sensitiveWordService: SensitiveWordService
    private lateinit var messageService: MessageService
    private lateinit var pushService: PushService
    private lateinit var conversationService: ConversationService
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var scope: CoroutineScope
    private lateinit var handler: SendMessageHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        sensitiveWordService = mockk<SensitiveWordService>()
        messageService = mockk()
        pushService = mockk<PushService>(relaxed = true)
        conversationService = mockk<ConversationService>(relaxed = true)
        connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        // 默认不脱敏，filter 返回原文本，避免影响既有用例；敏感词用例单独 stub
        every { sensitiveWordService.filter(any()) } returnsArgument 0
        coEvery { messageService.checkAndSetDedup(any(), any()) } returns true

        handler = SendMessageHandler(sensitiveWordService, messageService, pushService, conversationService, connection, scope)
    }

    /**
     * 取消 CoroutineScope，释放 Dispatchers.Default 线程，避免非守护线程阻止 JVM 退出。
     */
    @AfterEach
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun sendShouldReturnSendMessageResp() = runTest {
        // MessageService 返回成功结果
        // 注：使用真实 SendMessageResult 实例而非 mockk mock ——
        // mockk 对 data class.copy() 会生成子 mock 而不继承父 stub，
        // 导致 handler 的 result.copy(conversationCreated=...) 拿不到 msgId 等字段值。
        // 2026-07 改造：SendMessageResult 多了 conversationCreated/conversationBrief 字段，
        // 真实实例更稳定，未来再加字段不会回归。
        val chatMsg = ChatMessage.newBuilder()
            .setMsgId(50001L)
            .setConversationId("conv-001")
            .setSenderUid(1001L)
            .build()
        val sendResult = SendMessageResult(
            msgId = 50001L,
            serverTs = 1700000000000L,
            conversationId = "conv-001",
            senderUid = 1001L,
            chatMessage = chatMsg,
            conversationBrief = com.nebula.chat.conversation.ConversationBrief.getDefaultInstance(),
            conversationCreated = false
        )
        coEvery { messageService.sendMessage(any(), any()) } returns sendResult

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

    /**
     * 敏感词脱敏：文本内容命中敏感词时，handler 应先脱敏（替换为 *）再照常发送，
     * 落库与推送的内容均为脱敏结果，且 MessageService.sendMessage 被调用。
     */
    @Test
    fun sensitiveContentShouldBeMaskedAndSent() = runTest(sessionContext()) {
        every { sensitiveWordService.filter("你是个傻逼") } returns "你是个***"

        val chatMsg = ChatMessage.newBuilder()
            .setMsgId(50001L)
            .setConversationId("conv-001")
            .setSenderUid(1001L)
            .build()
        val sendResult = SendMessageResult(
            msgId = 50001L,
            serverTs = 1700000000000L,
            conversationId = "conv-001",
            senderUid = 1001L,
            chatMessage = chatMsg,
            conversationBrief = com.nebula.chat.conversation.ConversationBrief.getDefaultInstance(),
            conversationCreated = false
        )
        val reqSlot = slot<SendMessageReq>()
        coEvery { messageService.sendMessage(req = capture(reqSlot), senderUid = any()) } returns sendResult

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("你是个傻逼")
            .setClientMessageId("msg-sensitive")
            .build()

        withContext(SessionKey(session)) {
            handler.handle(req)
        }
        assertEquals("你是个***", reqSlot.captured.content, "落库/推送内容应为脱敏后结果")
    }

    /**
     * Step 链中 BizException → 直接传播
     * D-09 要求 Step 链异常不吞没，直接传播给 Dispatcher
     */
    @Test
    fun stepChainBizExceptionShouldPropagate() = runTest(sessionContext()) {
        coEvery {
            messageService.sendMessage(any(), any())
        } throws BizException(BizCode.SEND_FAILED, "发送失败")

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()

        val exception = assertFailsWith<BizException> {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
        }
        assertEquals(BizCode.SEND_FAILED, exception.bizCode)
        assertEquals("发送失败", exception.message)
    }

    /**
     * 非预期异常（RuntimeException）→ 包装为 BizException(INTERNAL_ERROR)
     * REVIEW-HIGH-2 安全要求 — Step 链 try-catch 包裹非预期异常
     */
    @Test
    fun unexpectedExceptionShouldBeWrappedAsInternalError() = runTest(sessionContext()) {
        coEvery {
            messageService.sendMessage(any(), any())
        } throws RuntimeException("Redis connection timeout")

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId("msg-002")
            .build()

        val exception = assertFailsWith<BizException> {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
        }
        assertEquals(BizCode.INTERNAL_ERROR, exception.bizCode)
        assertTrue(exception.message!!.contains("Redis connection timeout"))
    }

    /**
     * F5（2026-07 review）：推送必须挂在 serverScope 上，不随调用方请求上下文（连接断开 / 10s 超时取消）丢失。
     * 验证：即使发送方协程被取消，pushService.pushMessageToMembers 仍被调用。
     */
    @Test
    fun pushSurvivesCallerContextCancellation() = runTest {
        val chatMsg = ChatMessage.newBuilder()
            .setMsgId(50001L)
            .setConversationId("conv-001")
            .setSenderUid(1001L)
            .build()
        val sendResult = SendMessageResult(
            msgId = 50001L,
            serverTs = 1700000000000L,
            conversationId = "conv-001",
            senderUid = 1001L,
            chatMessage = chatMsg,
            conversationBrief = com.nebula.chat.conversation.ConversationBrief.getDefaultInstance(),
            conversationCreated = false
        )
        coEvery { messageService.sendMessage(any(), any()) } returns sendResult
        // 返回空成员列表：刻意跳过 redis.incr 循环与 userId 过滤，使推送路径不依赖 Redis 副作用，
        // 从而精准验证「推送是否挂在 serverScope 上、不随调用方请求上下文取消而丢失」这一 P1 语义
        // （真实多成员 + Redis 写入路径由集成测试覆盖）。
        coEvery { conversationService.getConversationMembers("conv-001") } returns emptyList()

        // 调用方协程（模拟发送方连接上下文）
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        callerScope.launch(SessionKey(session)) {
            handler.handle(
                SendMessageReq.newBuilder()
                    .setConversationId("conv-001")
                    .setContent("Hello")
                    .setClientMessageId("msg-f5")
                    .build()
            )
        }.join()
        // 模拟发送方连接断开 / 请求上下文被取消
        callerScope.cancel()

        // 推送挂在 serverScope（= 注入的 scope）上，不受 callerScope 取消影响。
        // 若 P1 回归（推送错误绑在 callerScope），callerScope.cancel() 会取消它，
        // pushMessageToMembers 不会被调用，coVerify 将失败。
        delay(1000)
        coVerify(exactly = 1) { pushService.pushMessageToMembers(any(), any()) }
        callerScope.cancel()
    }
}
