package com.nebula.gateway.delivery

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.KeyValue
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RedisDeliveryTracker 单元测试（D-70, D-71, D-72）。
 *
 * 覆盖场景：
 * - setStatus HSET 调用及返回值处理
 * - getStatus 正常解析、键不存在、非法值等边界
 * - getAllStatuses 多字段返回
 * - refreshTtl 调用 expire
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisDeliveryTrackerTest {

    /** 被注入的 mock Redis 协程命令接口 */
    private lateinit var mockRedis: RedisCoroutinesCommands<String, String>

    /** mock Redis 连接（仅用于构造 tracker） */
    private lateinit var connection: StatefulRedisConnection<String, String>

    /** 被测对象 */
    private lateinit var tracker: RedisDeliveryTracker

    /** 测试用消息 ID */
    private val msgId = 10001L

    /** 测试用用户 ID */
    private val uid = 2001L

    /** 期望的 Redis Hash key */
    private val expectedKey = "msg:${msgId}:delivery"

    /** 期望的 Hash field */
    private val expectedField = "${uid}:status"

    @BeforeEach
    fun setUp() {
        mockRedis = mockk<RedisCoroutinesCommands<String, String>>(relaxed = true)
        connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        tracker = RedisDeliveryTracker(connection, mockRedis)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ──────────────────────────────────────────────
    // setStatus
    // ──────────────────────────────────────────────

    @Test
    fun setStatusShouldHsetAndExpire() = runTest {
        // hset 返回 true
        coEvery { mockRedis.hset(expectedKey, expectedField, "0") } returns true

        val result = tracker.setStatus(msgId, uid, 0)

        assertTrue(result, "hset 返回 true 时 setStatus 应返回 true")
        coVerify {
            mockRedis.hset(expectedKey, expectedField, "0")
            mockRedis.expire(expectedKey, 7 * 24 * 3600L)
        }
    }

    @Test
    fun setStatusShouldReturnFalseWhenHsetReturnsNull() = runTest {
        // hset 返回 null（模拟 Redis 异常/超时）
        coEvery { mockRedis.hset(expectedKey, expectedField, "1") } returns null

        val result = tracker.setStatus(msgId, uid, 1)

        assertEquals(false, result, "hset 返回 null 时 setStatus 应返回 false")
        coVerify { mockRedis.expire(expectedKey, 7 * 24 * 3600L) }
    }

    // ──────────────────────────────────────────────
    // getStatus
    // ──────────────────────────────────────────────

    @Test
    fun getStatusShouldReturnIntWhenExists() = runTest {
        coEvery { mockRedis.hget(expectedKey, expectedField) } returns "1"

        val result = tracker.getStatus(msgId, uid)

        assertNotNull(result, "hget 返回 '1' 时 getStatus 应返回 1")
        assertEquals(1, result)
    }

    @Test
    fun getStatusShouldReturnNullWhenNotExists() = runTest {
        coEvery { mockRedis.hget(expectedKey, expectedField) } returns null

        val result = tracker.getStatus(msgId, uid)

        assertNull(result, "hget 返回 null 时 getStatus 应返回 null")
    }

    @Test
    fun getStatusShouldReturnNullWhenInvalidValue() = runTest {
        coEvery { mockRedis.hget(expectedKey, expectedField) } returns "abc"

        val result = tracker.getStatus(msgId, uid)

        assertNull(result, "hget 返回非数字字符串时 getStatus 应返回 null（toIntOrNull 失败）")
    }

    // ──────────────────────────────────────────────
    // getAllStatuses
    // ──────────────────────────────────────────────

    @Test
    fun getAllStatusesShouldReturnAllFields() = runTest {
        val entry1 = KeyValue.just("1001:status", "0")
        val entry2 = KeyValue.just("1002:status", "1")
        val entry3 = KeyValue.just("1003:status", "2")
        coEvery { mockRedis.hgetall(expectedKey) } returns flowOf(entry1, entry2, entry3)

        val result = tracker.getAllStatuses(msgId)

        assertEquals(3, result.size, "应返回 3 个字段")
        assertEquals("0", result["1001:status"])
        assertEquals("1", result["1002:status"])
        assertEquals("2", result["1003:status"])
    }

    // ──────────────────────────────────────────────
    // refreshTtl
    // ──────────────────────────────────────────────

    @Test
    fun refreshTtlShouldExpire() = runTest {
        tracker.refreshTtl(msgId)

        coVerify { mockRedis.expire(expectedKey, 7 * 24 * 3600L) }
    }
}
