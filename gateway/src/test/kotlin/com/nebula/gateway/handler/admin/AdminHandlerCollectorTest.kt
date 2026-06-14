package com.nebula.gateway.handler.admin

import com.nebula.gateway.dispatcher.HandlerRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * AdminHandlerCollector 单元测试 — 验证死信查询/重试 Handler 的注册逻辑（Phase 10）。
 */
class AdminHandlerCollectorTest {

    @Test
    fun registerAllShouldRegisterBothHandlers() = runTest {
        val registry = HandlerRegistry()
        val collector = AdminHandlerCollector(
            deadLetterQueryHandler = mockk {
                every { method } returns "admin/dead-letters"
            },
            retryDeadLetterHandler = mockk {
                every { method } returns "admin/retry-dead-letter"
            }
        )

        collector.registerAll(registry)

        assertNotNull(registry.get("admin/dead-letters"), "admin/dead-letters 应已注册")
        assertNotNull(registry.get("admin/retry-dead-letter"), "admin/retry-dead-letter 应已注册")
    }
}
