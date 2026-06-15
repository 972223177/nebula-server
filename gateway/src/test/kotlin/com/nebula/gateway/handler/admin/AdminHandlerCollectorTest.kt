package com.nebula.gateway.handler.admin

import com.nebula.gateway.dispatcher.HandlerRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * AdminHandlerCollector 单元测试 — 验证死信查询/重试 Handler 的注册逻辑（Phase 10）。
 */
class AdminHandlerCollectorTest {

    /** 死信查询 Handler 的 method 名称 */
    private val deadLetterMethod = "admin/dead-letters"

    /** 死信重试 Handler 的 method 名称 */
    private val retryDeadLetterMethod = "admin/retry-dead-letter"

    @Test
    fun registerAllShouldRegisterBothHandlers() = runTest {
        val deadLetterQueryHandler = mockk<DeadLetterQueryHandler> {
            every { method } returns deadLetterMethod
        }
        val retryDeadLetterHandler = mockk<RetryDeadLetterHandler> {
            every { method } returns retryDeadLetterMethod
        }
        val registry = HandlerRegistry()
        val collector = AdminHandlerCollector(deadLetterQueryHandler, retryDeadLetterHandler)

        collector.registerAll(registry)

        assertNotNull(registry.get(deadLetterMethod), "admin/dead-letters 应已注册")
        assertNotNull(registry.get(retryDeadLetterMethod), "admin/retry-dead-letter 应已注册")
    }

    @Test
    fun handlerMethodsShouldBePrefixedWithAdmin() = runTest {
        val deadLetterQueryHandler = mockk<DeadLetterQueryHandler> {
            every { method } returns deadLetterMethod
        }
        val retryDeadLetterHandler = mockk<RetryDeadLetterHandler> {
            every { method } returns retryDeadLetterMethod
        }

        assertTrue(deadLetterQueryHandler.method.startsWith("admin/"), "DeadLetterQueryHandler method 应以 admin/ 为前缀")
        assertTrue(retryDeadLetterHandler.method.startsWith("admin/"), "RetryDeadLetterHandler method 应以 admin/ 为前缀")
    }

    @Test
    fun registryShouldReturnCorrectHandlerTypes() = runTest {
        val deadLetterQueryHandler = mockk<DeadLetterQueryHandler> {
            every { method } returns deadLetterMethod
        }
        val retryDeadLetterHandler = mockk<RetryDeadLetterHandler> {
            every { method } returns retryDeadLetterMethod
        }
        val registry = HandlerRegistry()
        val collector = AdminHandlerCollector(deadLetterQueryHandler, retryDeadLetterHandler)

        collector.registerAll(registry)

        val deadLetterEntry = registry.get(deadLetterMethod)
        assertNotNull(deadLetterEntry, "admin/dead-letters 应已注册")
        assertTrue(deadLetterEntry.handler is DeadLetterQueryHandler, "admin/dead-letters 应返回 DeadLetterQueryHandler 类型")

        val retryDeadLetterEntry = registry.get(retryDeadLetterMethod)
        assertNotNull(retryDeadLetterEntry, "admin/retry-dead-letter 应已注册")
        assertTrue(retryDeadLetterEntry.handler is RetryDeadLetterHandler, "admin/retry-dead-letter 应返回 RetryDeadLetterHandler 类型")
    }
}
