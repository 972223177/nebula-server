package com.nebula.gateway.handler.external

import com.nebula.gateway.di.register
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.external.agent.CallServiceHandler
import com.nebula.gateway.handler.external.agent.ListServicesHandler

/**
 * 外部服务 Handler 收集器（D-XX）—— 注册天气、搜索、IP 定位、服务发现、通用调用五个 Handler。
 */
class ExternalHandlerCollector(
    private val queryWeatherHandler: QueryWeatherHandler,
    private val webSearchHandler: WebSearchHandler,
    private val ipLocationHandler: IpLocationHandler,
    private val listServicesHandler: ListServicesHandler,
    private val callServiceHandler: CallServiceHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(queryWeatherHandler)
        registry.register(webSearchHandler)
        registry.register(ipLocationHandler)
        registry.register(listServicesHandler)
        registry.register(callServiceHandler)
    }
}
