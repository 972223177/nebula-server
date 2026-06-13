package com.nebula.gateway.service

import com.nebula.repository.redis.SessionRepository
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * SessionRepository.batchDelete 的单元测试（Plan 9-1）。
 *
 * 验证 Redis pipeline 批量删除方法的正确性：
 * - 正常批量删除场景
 * - 空列表边界情况
 * - autoFlush 异常恢复
 */
class ReconnectCleanupTest {

    /** 创建 mock StatefulRedisConnection */
    private fun createMockConnection(): StatefulRedisConnection<String, String> {
        return mockk(relaxed = true)
    }

    @Test
    fun `batchDelete should delete multiple keys via pipeline`() = runTest {
        val connection = createMockConnection()
        val repo = SessionRepository(connection)
        val keys = listOf("session:token:abc", "session:token:def", "session:token:ghi")

        // 执行批量删除
        repo.batchDelete(keys)

        // 验证 pipeline 模式开启
        verify(exactly = 1) { connection.setAutoFlush(false) }
        // 验证 flushCommands 被调用
        verify(exactly = 1) { connection.flushCommands() }
        // 验证 pipeline 模式恢复
        verify(exactly = 1) { connection.setAutoFlush(true) }
    }

    @Test
    fun `batchDelete with empty list should do nothing`() = runTest {
        val connection = createMockConnection()
        val repo = SessionRepository(connection)

        repo.batchDelete(emptyList())

        // 空列表不调用任何 pipeline 方法
        verify(exactly = 0) { connection.setAutoFlush(any()) }
        verify(exactly = 0) { connection.flushCommands() }
    }

    @Test
    fun `batchDelete should restore autoFlush on exception`() = runTest {
        val connection = createMockConnection()
        // 模拟 flushCommands 抛异常
        every { connection.flushCommands() } throws RuntimeException("Redis connection lost")
        val repo = SessionRepository(connection)
        val keys = listOf("session:token:abc")

        repo.batchDelete(keys)

        // 即使异常，autoFlush 也恢复为 true
        verify(exactly = 1) { connection.setAutoFlush(false) }
        verify(exactly = 1) { connection.flushCommands() }
        verify(exactly = 1) { connection.setAutoFlush(true) }
    }
}
