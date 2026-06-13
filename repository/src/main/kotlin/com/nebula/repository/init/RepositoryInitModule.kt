package com.nebula.repository.init

import com.nebula.common.init.ModuleInitializer
import org.koin.core.qualifier.named
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Repository 模块初始化器注册模块。
 *
 * 在 Koin 启动时注册 [RepositoryModuleInitializer] 为 [ModuleInitializer] 实现，
 * 供 server 层通过 getAll() 发现。
 *
 * 使用 named("repository") 限定符确保与同名接口的其他实现不冲突（D-28）。
 */
val repositoryInitModule: Module = module {
    single<ModuleInitializer>(named("repository")) { RepositoryModuleInitializer() }
}
