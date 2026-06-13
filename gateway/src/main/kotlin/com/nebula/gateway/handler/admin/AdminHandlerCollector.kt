package com.nebula.gateway.handler.admin

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.di.register

/**
 * Admin 管理 Handler 收集器 — 注册所有 Admin 模块的 Handler（Phase 10）。
 *
 * 注册的 Handler：
 * - [DeadLetterQueryHandler]：死信记录查询（admin/dead-letters）
 * - [RetryDeadLetterHandler]：死信手动重试（admin/retry-dead-letter）
 */
class AdminHandlerCollector(
    private val deadLetterQueryHandler: DeadLetterQueryHandler,
    private val retryDeadLetterHandler: RetryDeadLetterHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(deadLetterQueryHandler)
        registry.register(retryDeadLetterHandler)
    }
}
