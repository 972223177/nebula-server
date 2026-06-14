package com.nebula.gateway.handler.admin

import com.nebula.chat.admin.RetryDeadLetterReq
import com.nebula.service.admin.DeadLetterService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RetryDeadLetterHandler 单元测试（Phase 10）。
 *
 * 覆盖场景：
 * - 手动重试成功：返回 success = true
 * - 手动重试失败：返回 success = false
 */
class RetryDeadLetterHandlerTest {

    private lateinit var deadLetterService: DeadLetterService
    private lateinit var handler: RetryDeadLetterHandler

    @BeforeEach
    fun setUp() {
        deadLetterService = mockk<DeadLetterService>(relaxed = true)
        handler = RetryDeadLetterHandler(deadLetterService)
    }

    @Test
    fun handleShouldReturnSuccessWhenRetrySucceeds() = runTest {
        // 准备：retry 返回 true
        coEvery { deadLetterService.retry(100L) } returns true

        // 执行
        val req = RetryDeadLetterReq.newBuilder()
            .setDeadLetterId(100L)
            .build()
        val resp = handler.handle(req)

        // 验证
        assertTrue(resp.success, "重试成功时 success 应为 true")
    }

    @Test
    fun handleShouldReturnFalseWhenRetryFails() = runTest {
        // 准备：retry 返回 false
        coEvery { deadLetterService.retry(101L) } returns false

        // 执行
        val req = RetryDeadLetterReq.newBuilder()
            .setDeadLetterId(101L)
            .build()
        val resp = handler.handle(req)

        // 验证
        assertFalse(resp.success, "重试失败时 success 应为 false")
    }
}
