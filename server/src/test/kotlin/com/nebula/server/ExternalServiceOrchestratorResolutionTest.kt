package com.nebula.server

import com.nebula.common.external.ExternalServiceCacheConfig
import com.nebula.common.external.ExternalServiceConfig
import com.nebula.common.external.ExternalServiceQuotaConfig
import com.nebula.common.external.IpGeoConfig
import com.nebula.common.external.L1Config
import com.nebula.common.external.L2Config
import com.nebula.common.external.QWeatherConfig
import com.nebula.common.external.SerperConfig
import com.nebula.common.external.WttrConfig
import com.nebula.common.init.ModuleInitializer
import com.nebula.gateway.bootstrap.ServerBootstrap
import com.nebula.service.external.ExternalServiceInvoker
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.GeoIpInvoker
import com.nebula.service.external.WebSearchInvoker
import com.nebula.service.external.WeatherInvoker
import com.nebula.service.init.serviceKoinModule
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.component.KoinComponent
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 生产路径下 [ExternalServiceOrchestrator] 解析端到端测试（闭合审查发现的覆盖缺口）。
 *
 * 背景：[com.nebula.gateway.di.GatewayModuleTest] 把 orchestrator 用 mockk 替换、且不加载真实
 * 配置 bean；[KoinVerificationTest] 不加载 [serviceKoinModule] 的完整外部服务子图；
 * [ModuleInitializerAssemblyOrderTest] 只验证 QuotaManager 解析、不碰 ExternalServiceCache /
 * ExternalServicePipeline / ExternalServiceOrchestrator。三处合计没有任何测试用**真实 config bean**
 * 端到端解析 orchestrator，存在「新增 Invoker 漏注册 Koin 没人发现」的隐患。
 *
 * 本测试补上缺口：忠实复刻生产「运行时 declare Redis 连接」契约（用 relaxed mock 替代真实网络），
 * 跑真实 [ServerBootstrap.initializeModules] 时序，然后解析 orchestrator，断言：
 * 1. 完整依赖图闭合 —— [ExternalServiceOrchestrator] 可被 Koin 解析（聚合
 *    getAll<ExternalServiceInvoker<*>> → 3 个 Invoker → ExternalServicePipeline /
 *    ExternalServiceCache / QuotaManager / PerUserQuotaStore 全部可达）；
 * 2. getAll 聚合出全部 3 个 Invoker，serviceId 契约正确（锁定「新增 Invoker = 1 文件 + 1 行 Koin」
 *    的零增长契约，漏注册即断言失败）。
 *
 * 置于 server 模块：本测试需调用 [ServerBootstrap.initializeModules]（gateway 层），而 service 模块
 * 依分层约束不可反向依赖 gateway，故与 [ModuleInitializerAssemblyOrderTest] 同层放置。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class ExternalServiceOrchestratorResolutionTest {

    /**
     * Fake Repository 初始化器 —— 复刻 [com.nebula.repository.init.RepositoryModuleInitializer]
     * 的「运行时 declare StatefulRedisConnection」契约，不连真实 Redis。
     * 命名为 "repository" 以匹配 [com.nebula.service.init.QuotaModuleInitializer] 的
     * `dependencies = ["repository"]`，保证拓扑排序中 repository 先于 quota 执行。
     */
    private class FakeRepositoryInitializer : ModuleInitializer, KoinComponent {
        override val name: String = "repository"
        override val dependencies: List<String> = emptyList()

        override fun init() {
            // 复刻生产契约：Redis 连接只在 initializer 运行时才 declare 到容器
            GlobalContext.get().declare<StatefulRedisConnection<String, String>>(mockk(relaxed = true))
        }
    }

    @AfterEach
    fun tearDown() {
        // 释放 QuotaManager 自有协程作用域，避免非守护线程阻止 JVM 退出
        runCatching {
            GlobalContext.get().getAll<ModuleInitializer>()
                .firstOrNull { it.name == "external-quota" }
                ?.shutdown()
        }
        runCatching { GlobalContext.stopKoin() }
    }

    @Test
    fun productionPathResolvesOrchestratorWithAllThreeInvokers() {
        val quotaConfig = ExternalServiceQuotaConfig(
            filePath = "${System.getProperty("java.io.tmpdir")}/nebula-orch-test-quota.properties",
            weatherDailyLimit = 1000,
            searchMonthlyLimit = 2500,
            weatherPerUserDailyLimit = 250,
            searchPerUserMonthlyLimit = 500,
            geoDailyLimit = 500,
            geoPerUserDailyLimit = 50,
            geoMonthlyLimit = 15000,
            warnThreshold = 80,
            rejectThreshold = 95,
            flushIntervalSeconds = 10
        )
        val cacheConfig = ExternalServiceCacheConfig(
            l1 = L1Config(weatherTtlSeconds = 120, searchTtlSeconds = 300, maxEntries = 100),
            l2 = L2Config(
                keyPrefix = "ext:",
                weatherTtlSeconds = 1800,
                geoTtlSeconds = 86400,
                searchTtlSeconds = 3600,
                searchStableTtlSeconds = 21600,
                searchNewsTtlSeconds = 600,
                ipGeoTtlSeconds = 86400
            )
        )
        val config = ExternalServiceConfig(
            qweather = QWeatherConfig(apiKey = "test", baseUrl = "https://devapi.qweather.com", timeoutMs = 3000),
            wttr = WttrConfig(baseUrl = "https://wttr.in", lang = "zh", timeoutMs = 3000),
            serper = SerperConfig(apiKey = "test", baseUrl = "https://google.serper.dev", timeoutMs = 3000),
            ipGeo = IpGeoConfig(baseUrl = "https://restapi.amap.com/v3/ip", apiKey = "test", timeoutMs = 3000),
            quota = quotaConfig,
            cache = cacheConfig
        )

        startKoin {
            modules(
                serviceKoinModule,
                module {
                    // 配置 bean 由 server 层在 startKoin 注册（与生产注入一致），此处静态补齐
                    single<ExternalServiceConfig> { config }
                    single<ExternalServiceQuotaConfig> { quotaConfig }
                    single<ExternalServiceCacheConfig> { cacheConfig }
                    // Fake repository initializer：复刻运行时 declare 契约，命名为 "repository"
                    single<ModuleInitializer>(named("repository")) { FakeRepositoryInitializer() }
                }
            )
        }

        val koin = GlobalContext.get()

        // 复刻生产时序：repository initializer 运行时 declare Redis，quota initializer 懒解析 QuotaManager
        ServerBootstrap.initializeModules(koin)

        // 断言 1：完整依赖图闭合，ExternalServiceOrchestrator 可被 Koin 解析
        // （聚合 getAll<ExternalServiceInvoker<*>> → 3 个 Invoker → ExternalServicePipeline /
        // ExternalServiceCache / QuotaManager / PerUserQuotaStore 全部可达）
        val orchestrator = koin.get<ExternalServiceOrchestrator>()
        assertNotNull(orchestrator)

        // 断言 2：getAll 聚合出全部 3 个 Invoker，serviceId 契约正确
        // （锁定「新增 Invoker = 1 文件 + 1 行 Koin」的零增长契约，漏注册即断言失败）
        val invokers = koin.getAll<ExternalServiceInvoker<*>>()
        assertEquals(3, invokers.size)
        assertEquals(
            setOf(WeatherInvoker.SERVICE_ID, WebSearchInvoker.SERVICE_ID, GeoIpInvoker.SERVICE_ID),
            invokers.map { it.serviceId }.toSet()
        )
    }
}
