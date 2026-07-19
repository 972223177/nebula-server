package com.nebula.gateway.handler.external

import com.nebula.gateway.di.register
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector

/**
 * 外部服务 Handler 收集器（D-XX）—— 注册天气与搜索两个 Handler。
 */
class ExternalHandlerCollector(
    private val queryWeatherHandler: QueryWeatherHandler,
    private val webSearchHandler: WebSearchHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(queryWeatherHandler)
        registry.register(webSearchHandler)
    }
}
