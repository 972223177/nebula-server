package com.nebula.server

import com.nebula.common.external.ExternalServiceQuotaConfig
import com.nebula.common.init.ModuleInitializer
import com.nebula.gateway.bootstrap.ServerBootstrap
import com.nebula.service.external.QuotaManager
import com.nebula.service.init.serviceKoinModule
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import io.lettuce.core.ExperimentalLettuceCoroutinesApi

/**
 * ModuleInitializer 装配时序端到端测试（回归防止 D-XX 时序 bug）。
 *
 * 背景：2026-07 出现过一次 [com.nebula.service.init.QuotaModuleInitializer] 启动崩溃
 * （NoDefinitionFoundException: StatefulRedisConnection）。根因是 `StatefulRedisConnection`
 * 由 [com.nebula.repository.init.RepositoryModuleInitializer.init] 在**运行时**通过
 * `koin.declare(...)` 动态注册，并非静态 `single {}` 定义；而 `ServerBootstrap.initializeModules`
 * 在收集阶段 `koin.getAll<ModuleInitializer>()` 会**急切实例化**所有 ModuleInitializer 构造函数，
 * 若某 initializer 在构造函数中急切依赖运行时才 declare 的 bean，就会在彼时（Redis 连接尚未就绪）崩溃。
 *
 * 现有 [ModuleInitializerRegistrationTest] 只验证了 `getAll()` + `topologicalSort`，从不调用 `init()`，
 * 因此未触发该时序；[com.nebula.gateway.di.GatewayModuleTest] 与 [com.nebula.server.KoinVerificationTest]
 * 则把 `StatefulRedisConnection` 当静态 `single` mock 掉，直接绕开了生产装配路径。
 *
 * 本测试补上缺口：忠实复刻生产的「运行时 declare」契约（用 relaxed mock 替代真实网络，毫秒级、零基础设施依赖），
 * 跑真实的 `ServerBootstrap.initializeModules` 排序逻辑，断言：
 * 1. `init()` 执行前 `StatefulRedisConnection` 不可解析（证明它是运行时 declare，而非静态 single）；
 * 2. `initializeModules` 全流程无异常完成（若 QuotaModuleInitializer 被改回构造函数注入则此处必崩）；
 * 3. `init()` 执行后 `StatefulRedisConnection` 与 `QuotaManager` 均可解析（配额管理器在正确时机被懒解析）。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class ModuleInitializerAssemblyOrderTest {

    /**
     * Fake Repository 初始化器 —— 仅复刻生产 [com.nebula.repository.init.RepositoryModuleInitializer]
     * 的「运行时 declare StatefulRedisConnection」契约，不连真实 Redis。
     *
     * 名称定为 "repository" 以匹配 [com.nebula.service.init.QuotaModuleInitializer]
     * 的 `dependencies = ["repository"]`，保证拓扑排序中 repository 先于 quota 执行。
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
    fun initializeModulesResolvesRuntimeDeclaredRedisConnectionAfterRepositoryInit() {
        startKoin {
            modules(
                serviceKoinModule,
                module {
                    // 配额配置为纯 value object（无基础设施依赖），静态注册与生产 server 层注入一致
                    single {
                        ExternalServiceQuotaConfig(
                            filePath = "${System.getProperty("java.io.tmpdir")}/nebula-test-quota.properties",
                            weatherDailyLimit = 1000,
                            searchMonthlyLimit = 2500,
                            weatherPerUserDailyLimit = 250,
                            searchPerUserMonthlyLimit = 500,
                            warnThreshold = 80,
                            rejectThreshold = 95,
                            flushIntervalSeconds = 10
                        )
                    }
                    // Fake repository initializer：复刻运行时 declare 契约，命名为 "repository"
                    single<ModuleInitializer>(named("repository")) { FakeRepositoryInitializer() }
                }
            )
        }

        val koin = GlobalContext.get()

        // 断言 1：init() 执行前，StatefulRedisConnection 不可解析（证明其为运行时 declare，非静态 single）
        // 若此处可解析，说明有人把它注册成了静态 single，会掩盖本测试要防的时序 bug
        assertNull(
            runCatching { koin.get<StatefulRedisConnection<String, String>>() }.getOrNull(),
            "init() 前 StatefulRedisConnection 应不可解析（运行时 declare 契约）"
        )

        // 断言 2：真实排序逻辑跑通（含 getAll() 急切实例化 → 拓扑排序 → 依次 init()）
        // QuotaModuleInitializer 在 init() 阶段才懒解析 QuotaManager（依赖运行时 declare 的 Redis 连接），
        // 若被改回构造函数注入，getAll() 时即因连接未 declare 而 NoDefinitionFoundException，本行必崩
        ServerBootstrap.initializeModules(koin)

        // 断言 3：init() 后 Redis 连接与 QuotaManager 均可解析（配额管理器在正确时机被懒解析）
        assertNotNull(
            runCatching { koin.get<StatefulRedisConnection<String, String>>() }.getOrNull(),
            "init() 后 StatefulRedisConnection 应已由 repository initializer declare"
        )
        assertNotNull(
            runCatching { koin.get<QuotaManager>() }.getOrNull(),
            "init() 后 QuotaManager 应已被 QuotaModuleInitializer 懒解析"
        )
    }
}
