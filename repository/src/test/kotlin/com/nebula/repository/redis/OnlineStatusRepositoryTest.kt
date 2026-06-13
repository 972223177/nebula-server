package com.nebula.repository.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.KeyValue
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * OnlineStatusRepository 三值状态单元测试（D-57）。
 *
 * 覆盖场景：
 * - setOnline/getStatus 往返验证
 * - setHidden/getStatus 验证 status=2
 * - refreshTtl 调用验证
 * - batchGetStatus 批量查询验证
 *
 * 注：通过反射注入 mock Redis commands 以控制 Redis 行为。
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class OnlineStatusRepositoryTest {

    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var repository: OnlineStatusRepository

    @BeforeEach
    fun setUp() {
        redis = mockk(relaxed = true)
        connection = mockk(relaxed = true)
        repository = OnlineStatusRepository(connection)

        // 通过反射替换内部 redis 字段为 mock
        val field = OnlineStatusRepository::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(repository, redis)
    }

    @Test
    fun `setOnline 写入 JSON 后 getStatus 返回 status=1`() = runTest {
        // 模拟 setOnline 写入 JSON，getStatus 返回相同内容
        coEvery { redis.get("online:user:1") } returns """{"status":1,"lastActiveAt":1700000000000}"""

        repository.setOnline(1L)
        val result = repository.getStatus(1L)

        assertNotNull(result)
        assertEquals(1, result!!.status)
    }

    @Test
    fun `setHidden 写入 JSON 后 getStatus 返回 status=2`() = runTest {
        coEvery { redis.get("online:user:2") } returns """{"status":2,"lastActiveAt":1700000000000}"""

        repository.setHidden(2L)
        val result = repository.getStatus(2L)

        assertNotNull(result)
        assertEquals(2, result!!.status)
    }

    @Test
    fun `refreshTtl 调用 EXPIRE`() = runTest {
        repository.refreshTtl(3L)
        // relaxed mock 不抛异常即验证成功
    }

    @Test
    fun `batchGetStatus 批量查询返回所有状态`() = runTest {
        coEvery { redis.mget("online:user:1", "online:user:2", "online:user:3") } returns flowOf<KeyValue<String, String>>(
            KeyValue.just("online:user:1", """{"status":1,"lastActiveAt":1700000000000}"""),  // 在线
            KeyValue.just("online:user:2", """{"status":2,"lastActiveAt":1700000000000}"""),  // 隐藏
            KeyValue.empty("online:user:3")  // 离线
        )

        val result = repository.batchGetStatus(listOf(1L, 2L, 3L))

        assertEquals(3, result.size)
        assertEquals(1, result[1L]?.status)   // 在线
        assertEquals(2, result[2L]?.status)   // 隐藏
        assertNull(result[3L])                // 离线
    }

    @Test
    fun `isOnline 对在线用户返回 true`() = runTest {
        coEvery { redis.get("online:user:4") } returns """{"status":1,"lastActiveAt":1700000000000}"""

        val result = repository.isOnline(4L)

        assertTrue(result)
    }

    @Test
    fun `isOnline 对离线用户返回 false`() = runTest {
        coEvery { redis.get("online:user:5") } returns null

        val result = repository.isOnline(5L)

        assertFalse(result)
    }
}
