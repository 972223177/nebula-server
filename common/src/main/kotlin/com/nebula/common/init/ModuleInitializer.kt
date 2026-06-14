package com.nebula.common.init

/**
 * 模块初始化器接口。
 *
 * 每个需要启动时初始化的模块实现此接口，并注册为 Koin single。
 * Server 层通过 Koin getAll() 发现所有实现，按 [dependencies] 拓扑排序后依次执行 init()。
 *
 * 初始化方法通过 KoinComponent.get() 获取已注册的依赖，通过 Koin API 注册本模块的产物。
 */
interface ModuleInitializer {

    /** 本模块的唯一标识名称，用于拓扑排序的依赖声明 */
    val name: String

    /** 依赖的其他模块名称列表，拓扑排序确保依赖模块先执行 */
    val dependencies: List<String>

    /**
     * 执行本模块的初始化逻辑。
     *
     * 通过 Koin 容器管理依赖以支持模块间解耦和测试替换（D-17）。
     *
     * 实现步骤：
     * 1. 通过 get<Xxx>() 获取已注册的依赖（如 ApplicationConfig）
     * 2. 创建本模块所需的实例
     * 3. 通过 Koin API（如 koin.declare()）将产物注册到容器
     */
    fun init()
}
