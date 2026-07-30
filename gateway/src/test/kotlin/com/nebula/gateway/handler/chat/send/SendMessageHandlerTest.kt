package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.message.ChatMessage
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.sessionContext
import com.nebula.common.redis.RedisStreamQueue
import com.nebula.service.chat.MessageService
import com.nebula.service.chat.SendMessageResult
import com.nebula.service.conversation.ConversationService
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.util.Base64

/**
 * SendMessageHandler 单元测试（D-04, D-13, D-72, §七 Durable Outbox）。
 *
 * D-72：Redis SETNX 去重逻辑已下沉到 MessageService.checkAndSetDedup() 中，
 * handler 层不再处理去重。
 *
 * 2026-07-30（§七）：原 fire-and-forget（serverScope.launch + asyncUnreadAndPush）改为
 * 发布持久化 fan-out 事件到 fanoutQueue，由 FanoutWorker 后台消费。本测试覆盖 handler 层
 * 的入队语义（含 fail-open），未读自增与推送的真实执行由 FanoutWorkerTest 覆盖。
 *
 * 覆盖场景：
 * - 正常发送 → MessageService 返回 SendMessageResult，返回 SendMessageResp
 * - Step 链 SendMessageException → 直接传播（D-09）
 * - 非预期异常 → 包装为 BizException(INTERNAL_ERROR)（REVIEW-HIGH-2）
 * - fan-out 事件入队与调用方取消解耦（§七）
 * - fan-out 入队 payload 完整性
 * - 入队失败 fail-open（Redis 抖动不导致发送失败）
 */
class SendMessageHandlerTest {

    private lateinit var sensitiveWordService: SensitiveWordService
    private lateinit var messageService: MessageService
    private lateinit var conversationService: ConversationService
    private lateinit var fanoutQueue: RedisStreamQueue
    private lateinit var handler: SendMessageHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        sensitiveWordService = mockk<SensitiveWordService>()
        messageService = mockk()
        conversationService = mockk<ConversationService>(relaxed = true)
        fanoutQueue = mockk<RedisStreamQueue>()

        // 默认不脱敏，filter 返回原文本，避免影响既有用例；敏感词用例单独 stub
        every { sensitiveWordService.filter(any()) } returnsArgument 0
        coEvery { messageService.checkAndSetDedup(any(), any()) } returns true
        // fan-out 入队默认成功返回 id；失败场景由专门用例 stub
        coEvery { fanoutQueue.enqueue(any()) } returns "mock-stream-id"

        handler = SendMessageHandler(sensitiveWordService, messageService, conversationService, fanoutQueue)
    }

    private fun buildSendResult(msgId: Long, convId: String): SendMessageResult = SendMessageResult(
        msgId = msgId,
        serverTs = 1700000000000L,
        conversationId = convId,
        senderUid = 1001L,
        chatMessage = ChatMessage.newBuilder()
            .setMsgId(msgId)
            .setConversationId(convId)
            .setSenderUid(1001L)
            .build(),
        conversationBrief = com.nebula.chat.conversation.ConversationBrief.getDefaultInstance(),
        conversationCreated = false
    )

    private fun buildReq(convId: String, clientMsgId: String, content: String = "Hello") = SendMessageReq.newBuilder()
        .setConversationId(convId)
        .setContent(content)
        .setClientMessageId(clientMsgId)
        .build()

    @Test
    fun sendShouldReturnSendMessageResp() = runTest {
        val sendResult = buildSendResult(50001L, "conv-001")
        coEvery { messageService.sendMessage(any(), any()) } returns sendResult

        val resp = withContext(SessionKey(session)) {
            handler.handle(buildReq("conv-001", "msg-001"))
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

        val sendResult = buildSendResult(50001L, "conv-001")
        val reqSlot = slot<SendMessageReq>()
        coEvery { messageService.sendMessage(req = capture(reqSlot), senderUid = any()) } returns sendResult

        withContext(SessionKey(session)) {
            handler.handle(buildReq("conv-001", "msg-sensitive", "你是个傻逼"))
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

        val exception = assertFailsWith<BizException> {
            withContext(SessionKey(session)) {
                handler.handle(buildReq("conv-001", "msg-001"))
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

        val exception = assertFailsWith<BizException> {
            withContext(SessionKey(session)) {
                handler.handle(buildReq("conv-001", "msg-002"))
            }
        }
        assertEquals(BizCode.INTERNAL_ERROR, exception.bizCode)
        assertTrue(exception.message.contains("Redis connection timeout"))
    }

    /**
     * F5（2026-07-30 §七 Durable Outbox）：发送响应与未读/推送 fan-out 解耦。
     * handle() 将「待推送/待加未读」作为持久事件同步入队 fanoutQueue，
     * 无论调用方请求上下文（连接断开/取消）如何，入队都已完成 —— 推送由 FanoutWorker 后台执行，
     * 与调用方取消链彻底隔离（对应原 P1「推送挂 serverScope」语义的新实现）。
     * 验证：handle() 完成后 fanoutQueue.enqueue 被精确调用 1 次，且 callerScope 取消不阻止入队。
     */
    @Test
    fun fanoutEventEnqueuedDecoupledFromCallerContext() = runTest {
        val sendResult = buildSendResult(50001L, "conv-001")
        coEvery { messageService.sendMessage(any(), any()) } returns sendResult

        // 调用方协程（模拟发送方连接上下文，仍用真实 Dispatchers.Default 代表真实调用方）
        val callerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        callerScope.launch(SessionKey(session)) {
            handler.handle(buildReq("conv-001", "msg-f5"))
        }.join()
        // 模拟发送方连接断开 / 请求上下文被取消
        callerScope.cancel()

        // 入队是 handle() 内同步动作，与 callerScope 取消无关，必被调用且精确 1 次
        coVerify(exactly = 1) { fanoutQueue.enqueue(any()) }
        callerScope.cancel()
    }

    /**
     * fan-out 入队 payload 正确性：conversationId / msgId / senderUid / chatMessage(Base64 proto) 完整。
     * 推送与未读自增的真实执行由 FanoutWorkerTest 覆盖。
     */
    @Test
    fun fanoutEventPayloadIsComplete() = runTest {
        val sendResult = buildSendResult(70001L, "conv-payload")
        coEvery { messageService.sendMessage(any(), any()) } returns sendResult

        val eventSlot = slot<Map<String, String>>()
        coEvery { fanoutQueue.enqueue(capture(eventSlot)) } returns "id-1"

        withContext(SessionKey(session)) {
            handler.handle(buildReq("conv-payload", "msg-payload"))
        }

        val event = eventSlot.captured
        assertEquals("conv-payload", event["conversationId"])
        assertEquals("70001", event["msgId"])
        assertEquals("1001", event["senderUid"])
        val decoded = ChatMessage.parseFrom(Base64.getDecoder().decode(event["chatMessage"]))
        assertEquals(70001L, decoded.msgId)
        assertEquals("conv-payload", decoded.conversationId)
    }

    /**
     * fail-open：fanoutQueue.enqueue 抛异常（Redis 抖动）时，发送响应仍正常返回，不冒泡为发送失败。
     * 消息已持久化（经 messageService.sendMessage 落库），client 拉历史可补偿缺失的推送。
     */
    @Test
    fun fanoutEnqueueFailureIsFailOpen() = runTest {
        val sendResult = buildSendResult(80001L, "conv-fo")
        coEvery { messageService.sendMessage(any(), any()) } returns sendResult
        coEvery { fanoutQueue.enqueue(any()) } throws RuntimeException("Redis down")

        val resp = withContext(SessionKey(session)) {
            handler.handle(buildReq("conv-fo", "msg-fo"))
        }
        // 发送成功，未因 fan-out 入队失败而失败
        assertEquals(80001L, resp.msgId)
    }
}
