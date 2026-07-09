package com.nebula.common.sensitiveword

import com.nebula.common.config.ApplicationConfig
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 敏感词功能 Koin 模块（位于 common 层，供 gateway 聚合引入，D-106）。
 *
 * 注册：
 * - [SensitiveWordFetcher]：配置了远程源（source-url 非空）时绑定 [GiteeHttpWordFetcher]，
 *   否则绑定 [NoopWordFetcher]（不发起网络请求，仅用内嵌资源）
 * - [SensitiveWordService]：抽象层接口，绑定 houbb 实现 [HoubbSensitiveWordService]（接口隔离，D-120）
 * - [SensitiveWordInitializer]：启动初始化器（ModuleInitializer），在 ServerBootstrap 启动时加载词库
 */
val sensitiveWordModule: Module = module {
    single<SensitiveWordFetcher> {
        val cfg = get<ApplicationConfig>().sensitiveWord
        if (cfg.sourceUrl.isBlank()) NoopWordFetcher() else GiteeHttpWordFetcher(cfg.sourceUrl)
    }
    single<SensitiveWordService> {
        val cfg = get<ApplicationConfig>().sensitiveWord
        HoubbSensitiveWordService(
            fetcher = get(),
            resourcePath = cfg.resourcePath,
            remoteEnabled = cfg.sourceUrl.isNotBlank()
        )
    }
    single<com.nebula.common.init.ModuleInitializer>(named("sensitive-word")) { SensitiveWordInitializer() }
}
