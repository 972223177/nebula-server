package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.common.BizCode
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * DedupStep 单元测试（D-07, D-13）。
 *
 * 覆盖场景：
 * - 首次 client_message_id → SETNX 成功 → 返回 true
 * - 重复 client_message_id → SETNX 失败 → 抛出 SendMessageException(SEND_FAILED)
 *
 * 注：DedupStep 内部创建 RedisCoroutinesCommandsImpl，测试中需 mock 连接和 commands。
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class DedupStepTest {

    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var step: DedupStep

    private val clientMessageId = "msg-001"
    private val dedupKey = "chat:dedup:$clientMessageId"

    @BeforeEach
    fun setUp() {
        redis = mockk<RedisCoroutinesCommands<String, String>>(relaxed = true)
        connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        step = DedupStep(connection)
    }

    /**
     * 辅助方法：替换 DedupStep 内部的 redis 字段。
     * DedupStep 的 redis 字段通过 init 块从 connection 初始化，
     * 我们通过反射替换为 mock，以便在测试中控制行为。
     */
    private fun injectMockRedis() {
        val field = DedupStep::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(step, redis)
    }

    @Test
    fun firstMessageSetnxShouldReturnTrue() = runTest {
        injectMockRedis()
        coEvery { redis.setnx(dedupKey, "pending") } returns true
        coEvery { redis.expire(dedupKey, 7 * 24 * 3600L) } returns true

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId(clientMessageId)
            .build()
        val context = SendContext(req = req, senderUid = 1001L)

        val result = step.execute(context)

        assertTrue(result, "首次消息应返回 true 继续链")
        coVerify(exactly = 1) { redis.setnx(dedupKey, "pending") }
        coVerify(exactly = 1) { redis.expire(dedupKey, 7 * 24 * 3600L) }
    }

    @Test
    fun duplicateMessageSetnxShouldThrowSendFailed() = runTest {
        injectMockRedis()
        coEvery { redis.setnx(dedupKey, "pending") } returns false

        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId(clientMessageId)
            .build()
        val context = SendContext(req = req, senderUid = 1001L)

        try {
            step.execute(context)
            fail("重复消息应抛出 SendMessageException(SEND_FAILED)")
        } catch (e: SendMessageException) {
            assertEquals(BizCode.SEND_FAILED, e.bizCode)
        }

        coVerify(exactly = 1) { redis.setnx(dedupKey, "pending") }
        // expire 不应被调用（setnx 失败时不会设置 TTL）
        coVerify(inverse = true) { redis.expire(any<String>(), any<Long>()) }
    }
}
