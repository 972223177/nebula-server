package com.nebula.repository.redis

import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.dao.UserDao
import com.nebula.repository.entity.UserEntity
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.persistence.EntityManager
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PrivacyRepository 的 MockK 单元测试（P0-04，方案 A 重构版 — 2026-06-20）。
 *
 * 覆盖 3 个核心方法的主要路径和异常回退：
 * - getHideOnlineStatus：Redis 未命中回退 MySQL、异常回退
 * - setHideOnlineStatus：Redis 写入 + MySQL 异步刷写
 * - batchGetHideOnlineStatus：MGET 调用验证
 *
 * 通过反射注入 mock redis 控制 Redis 行为，mock UserDao + JpaTxRunner 模拟 MySQL 读写。
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class PrivacyRepositoryTest {

    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var userDao: UserDao
    private lateinit var txRunner: JpaTxRunner
    private lateinit var em: EntityManager
    private lateinit var repository: PrivacyRepository

    private val userId = 1001L

    @BeforeEach
    fun setUp() {
        redis = mockk(relaxed = true)
        connection = mockk(relaxed = true)
        userDao = mockk(relaxed = true)
        txRunner = mockk()
        em = mockk(relaxed = true)
        repository = PrivacyRepository(connection, userDao, txRunner)

        val field = PrivacyRepository::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(repository, redis)

        // txRunner.execute 直接调用 lambda
        coEvery { txRunner.execute<Any?>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[0] as suspend (EntityManager) -> Any?).invoke(em)
        }
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    // ==================== getHideOnlineStatus ====================

    @Test
    fun getHideOnlineStatusShouldFallbackToMysqlWhenRedisMiss() = runTest {
        coEvery { redis.get("privacy:user:$userId") } returns null
        coEvery { userDao.findById(em, userId) } returns UserEntity(
            username = "test", passwordHash = "", nickname = "test"
        ).apply {
            this.id = userId
            privacyStatus = 2
        }

        val result = repository.getHideOnlineStatus(userId)

        assertTrue(result, "Redis 未命中时应从 MySQL 回退并返回 true")
    }

    @Test
    fun getHideOnlineStatusShouldReturnFalseWhenRedisMissAndMysqlNotFound() = runTest {
        coEvery { redis.get("privacy:user:$userId") } returns null
        coEvery { userDao.findById(em, userId) } returns null

        val result = repository.getHideOnlineStatus(userId)

        assertFalse(result, "Redis 和 MySQL 均无记录时应返回 false")
    }

    @Test
    fun getHideOnlineStatusShouldReturnFalseOnRedisException() = runTest {
        coEvery { redis.get("privacy:user:$userId") } throws RuntimeException("Redis connection lost")

        val result = repository.getHideOnlineStatus(userId)

        assertFalse(result, "Redis 异常时应返回 false（容错默认值）")
    }

    // ==================== setHideOnlineStatus ====================

    @Test
    fun setHideOnlineStatusShouldWriteRedisAndMysql() = runTest {
        coEvery { userDao.findById(em, userId) } returns UserEntity(
            username = "test", passwordHash = "", nickname = "test"
        ).apply {
            this.id = userId
            privacyStatus = 0
        }
        coEvery { userDao.update(em, any()) } answers { firstArg() }

        repository.setHideOnlineStatus(userId, true)

        coVerify(exactly = 1) {
            redis.setex("privacy:user:$userId", 7 * 24 * 3600L, """{"hideOnlineStatus":true}""")
        }
        coVerify(atLeast = 1) { userDao.findById(em, userId) }
    }

    @Test
    fun setHideOnlineStatusShouldHandleRedisException() = runTest {
        coEvery { redis.setex(any(), any(), any()) } throws RuntimeException("Redis timeout")

        repository.setHideOnlineStatus(userId, true)

        // 即使 Redis 异常也不抛异常（日志记录后静默返回）
        coVerify(exactly = 0) { userDao.findById(em, any()) }
    }

    // ==================== batchGetHideOnlineStatus ====================

    @Test
    fun batchGetHideOnlineStatusShouldCallMget() = runTest {
        val userIds = listOf(1L, 2L, 3L)
        val key1 = "privacy:user:1"
        val key2 = "privacy:user:2"
        val key3 = "privacy:user:3"

        repository.batchGetHideOnlineStatus(userIds)

        coVerify(exactly = 1) { redis.mget(key1, key2, key3) }
    }

    @Test
    fun batchGetHideOnlineStatusShouldReturnEmptyForEmptyInput() = runTest {
        val result = repository.batchGetHideOnlineStatus(emptyList())

        assertTrue(result.isEmpty(), "空输入应返回空集合")
    }
}
