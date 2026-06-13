package com.nebula.gateway.handler.system

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.di.register

/**
 * 系统级 Handler 收集器 — 注册 PingHandler 等系统级 Handler。
 */
class SystemHandlerCollector(
    private val pingHandler: PingHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(pingHandler)
    }
}
