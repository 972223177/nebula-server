package com.nebula.gateway.handler.external

import com.nebula.gateway.di.register
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector

/**
 * 外部服务 Handler 收集器（D-XX）—— 注册天气、搜索、IP 定位三个 Handler。
 */
class ExternalHandlerCollector(
    private val queryWeatherHandler: QueryWeatherHandler,
    private val webSearchHandler: WebSearchHandler,
    private val ipLocationHandler: IpLocationHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(queryWeatherHandler)
        registry.register(webSearchHandler)
        registry.register(ipLocationHandler)
    }
}
