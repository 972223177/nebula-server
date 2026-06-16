package com.nebula.repository.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * SessionRepository 的 MockK 单元测试（P0-01）。
 *
 * 覆盖 7 个 suspend 方法的正常调用和边界情况，
 * 以及 batchDelete pipeline 的正常/异常/空列表路径。
 *
 * suspend 方法通过反射注入 mock redis 进行验证，
 * batchDelete 使用 connection mock 直接验证 pipeline 行为。
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SessionRepositoryTest {

    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var repository: SessionRepository

    private val token = "test-token-001"
    private val userData = """{"uid":1001,"device":"MOBILE"}"""
    private val ttl = 7 * 24 * 3600L

    @BeforeEach
    fun setUp() {
        redis = mockk(relaxed = true)
        connection = mockk(relaxed = true)
        repository = SessionRepository(connection)

        // 通过反射替换内部 redis 字段为 mock
        val field = SessionRepository::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(repository, redis)
    }

    // ==================== save ====================

    @Test
    fun saveShouldSetexWithPrefix() = runTest {
        repository.save(token, userData, ttl)

        coVerify(exactly = 1) {
            redis.setex("session:token:$token", ttl, userData)
        }
    }

    // ==================== findByToken ====================

    @Test
    fun findByTokenShouldReturnUserDataWhenExists() = runTest {
        coEvery { redis.get("session:token:$token") } returns userData

        val result = repository.findByToken(token)

        assert(result == userData) { "应返回用户数据 JSON" }
    }

    @Test
    fun findByTokenShouldReturnNullWhenNotExists() = runTest {
        coEvery { redis.get("session:token:non-existent") } returns null

        val result = repository.findByToken("non-existent")

        assertNull(result, "不存在的 token 应返回 null")
    }

    // ==================== delete ====================

    @Test
    fun deleteShouldCallDel() = runTest {
        repository.delete(token)

        coVerify(exactly = 1) { redis.del("session:token:$token") }
    }

    // ==================== refreshTtl ====================

    @Test
    fun refreshTtlShouldCallExpire() = runTest {
        repository.refreshTtl(token, ttl)

        coVerify(exactly = 1) { redis.expire("session:token:$token", ttl) }
    }

    // ==================== saveRaw ====================

    @Test
    fun saveRawShouldSetexWithRawKey() = runTest {
        val rawKey = "device:map:1001"
        repository.saveRaw(rawKey, "MOBILE", ttl)

        coVerify(exactly = 1) { redis.setex(rawKey, ttl, "MOBILE") }
    }

    // ==================== findRaw ====================

    @Test
    fun findRawShouldReturnValueWhenExists() = runTest {
        coEvery { redis.get("device:map:1001") } returns "MOBILE"

        val result = repository.findRaw("device:map:1001")

        assert(result == "MOBILE") { "应返回原始值" }
    }

    @Test
    fun findRawShouldReturnNullWhenNotExists() = runTest {
        coEvery { redis.get("unknown:key") } returns null

        val result = repository.findRaw("unknown:key")

        assertNull(result, "不存在的 key 应返回 null")
    }

    // ==================== deleteKey ====================

    @Test
    fun deleteKeyShouldCallDel() = runTest {
        repository.deleteKey("cleanup:key:001")

        coVerify(exactly = 1) { redis.del("cleanup:key:001") }
    }

    // ==================== batchDelete (pipeline) ====================

    @Test
    fun batchDeleteShouldDeleteMultipleKeysViaPipeline() = runTest {
        val keys = listOf("session:token:abc", "session:token:def", "session:token:ghi")

        repository.batchDelete(keys)

        verify(exactly = 1) { connection.setAutoFlushCommands(false) }
        verify(exactly = 1) { connection.flushCommands() }
        verify(exactly = 1) { connection.setAutoFlushCommands(true) }
    }

    @Test
    fun batchDeleteWithEmptyListShouldDoNothing() = runTest {
        repository.batchDelete(emptyList())

        verify(exactly = 0) { connection.setAutoFlushCommands(any()) }
        verify(exactly = 0) { connection.flushCommands() }
    }

    @Test
    fun batchDeleteShouldRestoreAutoFlushOnException() = runTest {
        val async = mockk<RedisAsyncCommands<String, String>>(relaxed = true)
        every { connection.async() } returns async
        every { connection.flushCommands() } throws RuntimeException("Redis connection lost")
        val keys = listOf("session:token:abc")

        assertFailsWith<RuntimeException> {
            repository.batchDelete(keys)
        }

        verify(exactly = 1) { connection.setAutoFlushCommands(false) }
        verify(exactly = 1) { connection.flushCommands() }
        verify(exactly = 1) { connection.setAutoFlushCommands(true) }
    }
}
