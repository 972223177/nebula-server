package com.nebula.gateway.di

import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.external.ExternalHandlerCollector
import com.nebula.gateway.handler.external.QueryWeatherHandler
import com.nebula.gateway.handler.external.WebSearchHandler
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 外部服务 Handler Koin 模块（D-XX）—— 注册天气与搜索 Handler 及其 Collector。
 *
 * 仅注册 Handler + Collector；业务 Service 层组件（Orchestrator / QuotaManager 等）
 * 由 serviceKoinModule 注册，此处通过 get() 按类型注入，不放在本模块内重复创建。
 */
val externalHandlerModule = module {
    single { QueryWeatherHandler(get()) }
    single { WebSearchHandler(get()) }

    single<HandlerCollector>(named("external")) {
        ExternalHandlerCollector(get(), get())
    }
}
