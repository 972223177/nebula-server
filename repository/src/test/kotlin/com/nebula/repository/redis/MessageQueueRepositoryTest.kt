package com.nebula.repository.redis

import io.lettuce.core.Consumer
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.StreamMessage
import io.lettuce.core.XAddArgs
import io.lettuce.core.XReadArgs
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MessageQueueRepository 的 MockK 单元测试（P0-05）。
 *
 * 覆盖 4 个核心方法的正常调用和签名验证：
 * - enqueue：XADD 写入 Stream
 * - consume：XREADGROUP 消费消息
 * - acknowledge：XACK 确认消息
 * - checkAndSetDedup：SET NX EX 去重检测
 *
 * 通过反射注入 mock redis 控制 Redis 协程行为。
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class MessageQueueRepositoryTest {

    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var repository: MessageQueueRepository

    @BeforeEach
    fun setUp() {
        redis = mockk(relaxed = true)
        connection = mockk(relaxed = true)
        repository = MessageQueueRepository(connection)

        val field = MessageQueueRepository::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(repository, redis)
    }

    // ==================== enqueue ====================

    @Test
    fun enqueueShouldCallXaddAndReturnMessageId() = runTest {
        val message = mapOf("conversationId" to "conv-001", "content" to "hello")
        coEvery { redis.xadd("queue:messages", any<XAddArgs>(), message) } returns "1700000000000-0"

        val result = repository.enqueue(message)

        assertEquals("1700000000000-0", result, "应返回 Redis Stream 消息 ID")
        coVerify(exactly = 1) { redis.xadd("queue:messages", any<XAddArgs>(), message) }
    }

    @Test
    fun enqueueShouldReturnNullOnFailure() = runTest {
        coEvery { redis.xadd("queue:messages", any<XAddArgs>(), any()) } returns null

        val result = repository.enqueue(mapOf("key" to "value"))

        assertNull(result, "发送失败时应返回 null")
    }

    // ==================== consume ====================

    @Test
    fun consumeShouldReturnMessages() = runTest {
        coEvery { redis.xreadgroup(any<Consumer<String>>(), any<XReadArgs>(), any<XReadArgs.StreamOffset<String>>()) } returns emptyFlow<StreamMessage<String, String>>()

        val result = repository.consume(batchSize = 10, blockMs = 1000)

        assertTrue(result.isEmpty(), "无消息时应返回空列表")
        coVerify(exactly = 1) { redis.xreadgroup(any<Consumer<String>>(), any<XReadArgs>(), any<XReadArgs.StreamOffset<String>>()) }
    }

    // ==================== acknowledge ====================

    @Test
    fun acknowledgeShouldCallXack() = runTest {
        repository.acknowledge("1700000000000-0")

        coVerify(exactly = 1) { redis.xack("queue:messages", "flush-workers", "1700000000000-0") }
    }

    // ==================== checkAndSetDedup ====================

    @Test
    fun checkAndSetDedupShouldReturnTrueWhenFirstCall() = runTest {
        coEvery { redis.set(any(), any(), any()) } returns "OK"

        val result = repository.checkAndSetDedup("cmid-001", 1001L)

        assertTrue(result, "首次调用应返回 true（无重复）")
        coVerify(exactly = 1) { redis.set("dedup:msg:cmid-001", "1001", any()) }
    }

    @Test
    fun checkAndSetDedupShouldReturnFalseWhenDuplicate() = runTest {
        coEvery { redis.set(any(), any(), any()) } returns null

        val result = repository.checkAndSetDedup("cmid-001", 1001L)

        assertTrue(!result, "重复消息应返回 false")
    }

    @Test
    fun checkAndSetDedupShouldReturnTrueOnException() = runTest {
        coEvery { redis.set(any(), any(), any()) } throws RuntimeException("Redis down")

        val result = repository.checkAndSetDedup("cmid-001", 1001L)

        assertTrue(result, "异常时应 fail-open 返回 true")
    }

    // ==================== ensureConsumerGroup ====================

    @Test
    fun ensureConsumerGroupShouldCreateGroup() = runTest {
        repository.ensureConsumerGroup()

        coVerify(exactly = 1) { redis.xgroupCreate(any(), "flush-workers", any()) }
    }

    // ==================== getPendingCount ====================

    @Test
    fun getPendingCountShouldReturnPendingInfo() = runTest {
        repository.getPendingCount()

        coVerify(exactly = 1) { redis.xpending("queue:messages", "flush-workers") }
    }
}
