package com.nebula.repository.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * SessionRepository.batchDelete 的单元测试。
 *
 * R-01 修复后：batchDelete 改为逐条 redis.del()，不再使用 setAutoFlushCommands pipeline。
 * 测试验证每个 key 都被 del 调用，空列表不触发任何操作。
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SessionRepositoryBatchDeleteTest {

    /** 创建带 mock redis 的 SessionRepository（反射注入） */
    private fun createRepo(): Pair<SessionRepository, RedisCoroutinesCommands<String, String>> {
        val connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        val redis = mockk<RedisCoroutinesCommands<String, String>>(relaxed = true)
        val repo = SessionRepository(connection)
        val field = SessionRepository::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(repo, redis)
        return repo to redis
    }

    @Test
    fun batchDeleteShouldDeleteMultipleKeys() = runTest {
        val (repo, redis) = createRepo()
        val keys = listOf("session:token:abc", "session:token:def", "session:token:ghi")

        repo.batchDelete(keys)

        keys.forEach { key ->
            coVerify(exactly = 1) { redis.del(key) }
        }
    }

    @Test
    fun batchDeleteWithEmptyListShouldDoNothing() = runTest {
        val (repo, redis) = createRepo()

        repo.batchDelete(emptyList())

        coVerify(exactly = 0) { redis.del(any()) }
    }
}
