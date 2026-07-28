package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.ListServicesRequest
import com.nebula.service.external.ExternalServiceOrchestrator
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import kotlin.test.assertEquals

/**
 * 生产路径下 [ServiceDefinitionProvider] 经 Koin 聚合的解析测试（闭合覆盖缺口，对称于
 * server 模块的 [com.nebula.server.ExternalServiceOrchestratorResolutionTest]）。
 *
 * 背景：[ServiceRegistry] 依赖 `getAll<ServiceDefinitionProvider>()` 聚合内置服务定义，提供
 * list_services 清单。若三个 `single<ServiceDefinitionProvider>` 未用 [bind] 显式绑定接口类型，
 * Koin 会按主类型索引互相覆盖，`getAll` 只聚合出 1 个，导致 list_services 只返回 1 个服务定义
 * （与 [com.nebula.service.external.ExternalServiceOrchestrator] 同接口多实现覆盖 bug 同源）。
 *
 * 本测试锁定「bind 模式 → getAll 聚合 3 个」契约：漏写 bind 即断言失败。
 */
class ServiceDefinitionProviderResolutionTest {

    @AfterEach
    fun tearDown() {
        runCatching { GlobalContext.stopKoin() }
    }

    @Test
    fun bindModeAggregatesAllThreeServiceDefinitionProviders() {
        startKoin {
            modules(
                module {
                    // 与生产 externalHandlerModule 完全等价的 bind 语法
                    single { WeatherServiceProvider() } bind ServiceDefinitionProvider::class
                    single { WebSearchServiceProvider() } bind ServiceDefinitionProvider::class
                    single { GeoIpServiceProvider() } bind ServiceDefinitionProvider::class
                }
            )
        }

        val koin = GlobalContext.get()

        // 断言 1：getAll 聚合出全部 3 个 provider（漏写 bind 会只剩 1 个）
        val providers = koin.getAll<ServiceDefinitionProvider>()
        assertEquals(3, providers.size)

        // 断言 2：经聚合的 3 个 provider 构造的 ServiceRegistry 含 3 个服务定义（list_services 完整）
        val registry = ServiceRegistry(mockk<ExternalServiceOrchestrator>(), providers)
        val descriptors = registry.listDescriptors(ListServicesRequest.getDefaultInstance())
        assertEquals(3, descriptors.size)
        assertEquals(
            setOf("weather", "web_search", "geo_ip"),
            descriptors.map { it.id }.toSet()
        )
    }
}
