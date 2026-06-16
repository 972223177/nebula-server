package com.nebula.service.init

import com.nebula.common.init.commonInitModule
import com.nebula.repository.init.repositoryInitModule
import org.koin.core.module.Module

/**
 * Service 层模块聚合 — 将 common、repository、service 三层模块定义整合。
 *
 * 供 gateway 层的 ServerBootstrap 直接使用，避免 gateway 直接 import repository 层的模块。
 *
 * D-28: 通过 Koin Module 聚合实现模块解耦，gateway 只需依赖 service 层即可获取完整模块列表。
 */
object ServiceInitModule {
    /**
     * 所有 Koin 模块列表 — 按依赖顺序排列：common → repository → service。
     */
    val allModules: List<Module> = listOf(
        commonInitModule,
        repositoryInitModule,
        serviceKoinModule
    )
}
