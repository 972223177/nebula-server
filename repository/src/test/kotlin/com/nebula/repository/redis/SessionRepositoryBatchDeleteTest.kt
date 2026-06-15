package com.nebula.repository.redis

import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.async.RedisAsyncCommands
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * SessionRepository.batchDelete 的单元测试。
 *
 * 验证 Redis pipeline 批量删除方法的正确性：
 * - 正常批量删除场景
 * - 空列表边界情况
 * - autoFlush 异常恢复
 */
class SessionRepositoryBatchDeleteTest {

    /** 创建 mock StatefulRedisConnection */
    private fun createMockConnection(): StatefulRedisConnection<String, String> {
        return mockk(relaxed = true)
    }

    @Test
    fun batchDeleteShouldDeleteMultipleKeysViaPipeline() = runTest {
        val connection = createMockConnection()
        val repo = SessionRepository(connection)
        val keys = listOf("session:token:abc", "session:token:def", "session:token:ghi")

        // 执行批量删除
        repo.batchDelete(keys)

        // 验证 pipeline 模式开启
        verify(exactly = 1) { connection.setAutoFlushCommands(false) }
        // 验证 flushCommands 被调用
        verify(exactly = 1) { connection.flushCommands() }
        // 验证 pipeline 模式恢复
        verify(exactly = 1) { connection.setAutoFlushCommands(true) }
    }

    @Test
    fun batchDeleteWithEmptyListShouldDoNothing() = runTest {
        val connection = createMockConnection()
        val repo = SessionRepository(connection)

        repo.batchDelete(emptyList())

        // 空列表不调用任何 pipeline 方法
        verify(exactly = 0) { connection.setAutoFlushCommands(any()) }
        verify(exactly = 0) { connection.flushCommands() }
    }

    @Test
    fun batchDeleteShouldRestoreAutoFlushOnException() = runTest {
        val connection = createMockConnection()
        val async = mockk<RedisAsyncCommands<String, String>>(relaxed = true)
        every { connection.async() } returns async
        // 模拟 flushCommands 抛异常
        every { connection.flushCommands() } throws RuntimeException("Redis connection lost")
        val repo = SessionRepository(connection)
        val keys = listOf("session:token:abc")

        // 在 runTest 协程中直接调用 batchDelete，使用 kotlin.test.assertFailsWith 捕获异常
        assertFailsWith<RuntimeException> {
            repo.batchDelete(keys)
        }

        // 即使异常，autoFlush 也恢复为 true（finally 块保证）
        verify(exactly = 1) { connection.setAutoFlushCommands(false) }
        verify(exactly = 1) { connection.flushCommands() }
        verify(exactly = 1) { connection.setAutoFlushCommands(true) }
    }
}
