package com.nebula.gateway.di

import com.nebula.gateway.handler.external.IpLocationHandler
import com.nebula.gateway.handler.external.QueryWeatherHandler
import com.nebula.gateway.handler.external.WebSearchHandler
import com.nebula.gateway.handler.external.agent.CallServiceHandler
import com.nebula.gateway.handler.external.agent.GeoIpServiceProvider
import com.nebula.gateway.handler.external.agent.ListServicesHandler
import com.nebula.gateway.handler.external.agent.ServiceDefinitionProvider
import com.nebula.gateway.handler.external.agent.ServiceRegistry
import com.nebula.gateway.handler.external.agent.WebSearchServiceProvider
import com.nebula.gateway.handler.external.agent.WeatherServiceProvider
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * 外部服务 Handler Koin 模块（D-XX）—— 注册天气、搜索、IP 定位、服务发现、通用调用 Handler 及其 Collector。
 *
 * 仅注册 Handler + Collector + 注册表；业务 Service 层组件（Orchestrator / QuotaManager 等）
 * 由 serviceKoinModule 注册，此处通过 get() 按类型注入，不放在本模块内重复创建。
 * 各内置服务以 [ServiceDefinitionProvider] 注册，[ServiceRegistry] 经 getAll() 自动聚合，新增服务只加一行。
 */
val externalHandlerModule = module {
    single { QueryWeatherHandler(get()) } bind com.nebula.gateway.handler.Handler::class
    single { WebSearchHandler(get()) } bind com.nebula.gateway.handler.Handler::class
    single { IpLocationHandler(get()) } bind com.nebula.gateway.handler.Handler::class
    // Phase 10：服务发现 + 通用调用（复用既有 Orchestrator 作为执行体）
    // 内置服务逐个注册为 ServiceDefinitionProvider，注册表聚合，文件不再随服务数增长
    // 注意：同接口多实现必须用 bind 显式绑定接口类型，否则三个 single<ServiceDefinitionProvider>
    // 主 key 相同会互相覆盖（Koin 按主类型索引），getAll<ServiceDefinitionProvider>() 只会聚合出 1 个，
    // 导致 ServiceRegistry 实际只持有最后注册的服务（list_services 只返回 1 个服务定义）。
    // bind 把每个实现的主类型设为具体类、同时挂接接口类型，getAll 才能正确聚合全部实现。
    single { WeatherServiceProvider() } bind ServiceDefinitionProvider::class
    single { WebSearchServiceProvider() } bind ServiceDefinitionProvider::class
    single { GeoIpServiceProvider() } bind ServiceDefinitionProvider::class
    single { ServiceRegistry(get(), getAll()) }
    single { ListServicesHandler(get()) } bind com.nebula.gateway.handler.Handler::class
    single { CallServiceHandler(get()) } bind com.nebula.gateway.handler.Handler::class

}
