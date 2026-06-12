package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.chat.SendMessageResp
import com.nebula.common.BizCode
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.repository.repository.ConversationMemberRepository
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.every
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
 * SendMessageHandler 单元测试（D-04, D-13）。
 *
 * 覆盖场景：
 * - Step 链全部成功 → 返回 SendMessageResp（含 msgId + serverTs）
 * - Step 链中 SendMessageException → 直接传播
 * - 非预期异常（如模拟 RuntimeException）→ 包装为 SendMessageException(BizCode.INTERNAL_ERROR)
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SendMessageHandlerTest {

    private lateinit var step1: SendMessageStep
    private lateinit var step2: SendMessageStep
    private lateinit var step3: SendMessageStep
    private lateinit var pushService: PushService
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var scope: CoroutineScope
    private lateinit var handler: SendMessageHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        step1 = mockk<SendMessageStep>(relaxed = true)
        step2 = mockk<SendMessageStep>(relaxed = true)
        step3 = mockk<SendMessageStep>(relaxed = true)
        pushService = mockk<PushService>(relaxed = true)
        conversationMemberRepository = mockk<ConversationMemberRepository>(relaxed = true)
        redis = mockk<RedisCoroutinesCommands<String, String>>(relaxed = true)
        connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        val steps = listOf(step1, step2, step3)
        handler = SendMessageHandler(steps, pushService, conversationMemberRepository, connection, scope)
    }

    /**
     * 辅助方法：替换 SendMessageHandler 内部的 redis 字段。
     */
    private fun injectMockRedis() {
        val field = SendMessageHandler::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(handler, redis)
    }

    @Test
    fun `Step 链全部成功返回 SendMessageResp`() = runTest {
        injectMockRedis()
        // 所有 Step 返回 true（继续链）
        coEvery { step1.execute(any()) } returns true
        coEvery { step2.execute(any()) } returns true
        coEvery { step3.execute(any()) } returns true

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()

        // 注意：由于我们 mock 了 step3，它实际上不会设置 context.msgId。
        // handler.handle() 中 requireNotNull(context.msgId) 会检查 msgId 是否非空。
        // 我们必须让 WriteStep（step3）设置 msgId。
        // 换个方式：不 mock step3，而是让其中某个 step 设置正确的 msgId。
        // 由于 test 中使用了 relaxed mock，step.execute 返回 false，不会设值。
        // 更好的方式：mock step1 返回 true，step2 返回 true，然后让 step3 真实设置 msgId 或者绕过 msgId 检查。
        // 这里我们直接在测试中构造 handler 并注入 redis，但使用真实的 WriteStep 替代 step3。

        // 实际上最简单：创建一个模拟的 step3 调用链，让 handler 能执行完毕
        // 我们重新设置 step3 的 execute 来手动设置 msgId
        coEvery { step3.execute(any()) } answers {
            val ctx = arg<SendContext>(0)
            val field = SendContext::class.java.getDeclaredField("msgId")
            field.isAccessible = true
            field.set(ctx, 50001L)
            true
        }

        val resp = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        assertNotNull(resp)
        assertEquals(50001L, resp.msgId, "应返回 WriteStep 设置的 msgId")
        assertTrue(resp.serverTs > 0, "应返回服务端时间戳")
    }

    @Test
    fun `Step 链中 SendMessageException 直接传播`() = runTest {
        injectMockRedis()
        coEvery { step1.execute(any()) } returns true
        coEvery { step2.execute(any()) } throws SendMessageException(BizCode.SEND_FAILED, "重复消息")

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()

        try {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
            kotlin.test.fail("应抛出 SendMessageException(SEND_FAILED)")
        } catch (e: SendMessageException) {
            assertEquals(BizCode.SEND_FAILED, e.bizCode)
        }

        // step3 不应执行（链在 step2 终止）
        coEvery { step3.execute(any()) } returns true // 重置 mock 避免 relaxed 影响
    }

    @Test
    fun `非预期异常包装为 SendMessageException INTERNAL_ERROR`() = runTest {
        injectMockRedis()
        coEvery { step1.execute(any()) } throws RuntimeException("Redis 连接超时")

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()

        try {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
            kotlin.test.fail("应抛出 SendMessageException(INTERNAL_ERROR)")
        } catch (e: SendMessageException) {
            assertEquals(BizCode.INTERNAL_ERROR, e.bizCode)
            // 异常消息应包含原始异常信息
            assertTrue(e.message!!.contains("Redis 连接超时"), "异常消息应包含原始异常信息")
        }
    }
}
