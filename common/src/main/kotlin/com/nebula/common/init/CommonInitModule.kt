package com.nebula.common.init

import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Common 模块初始化器注册模块。
 *
 * 在 Koin 启动时注册 [CommonModuleInitializer] 为 [ModuleInitializer] 实现，
 * 供 server 层通过 getAll() 发现。
 */
val commonInitModule: Module = module {
    single<ModuleInitializer> { CommonModuleInitializer() }
}
