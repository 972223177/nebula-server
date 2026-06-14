package com.nebula.common.init

/**
 * 模块初始化器拓扑排序扩展函数。
 *
 * 根据 [ModuleInitializer.dependencies] 声明的依赖关系进行拓扑排序，
 * 确保依赖模块在被依赖模块之前执行。
 *
 * 使用深度优先搜索 + 访问标记（visited/visiting）实现拓扑排序，
 * 同时检测循环依赖（visiting 集合中再次遇到同一节点）。
 *
 * @param this 待排序的 [ModuleInitializer] 列表
 * @return 按依赖关系拓扑排序后的列表，依赖模块在前
 * @throws IllegalStateException 如果检测到循环依赖或引用了不存在的依赖
 */
fun List<ModuleInitializer>.topologicalSort(): List<ModuleInitializer> {
    val sorted = mutableListOf<ModuleInitializer>()
    val visited = mutableSetOf<String>()
    val visiting = mutableSetOf<String>()
    val byName = associateBy { it.name }

    fun visit(initializer: ModuleInitializer) {
        if (initializer.name in visited) return
        if (initializer.name in visiting) {
            throw IllegalStateException(
                "检测到模块循环依赖: ${initializer.name}"
            )
        }
        visiting.add(initializer.name)
        for (dep in initializer.dependencies) {
            val depInit = byName[dep]
                ?: throw IllegalStateException(
                    "模块 '${initializer.name}' 依赖 '$dep'，但不存在名为 '$dep' 的 ModuleInitializer"
                )
            visit(depInit)
        }
        visiting.remove(initializer.name)
        visited.add(initializer.name)
        sorted.add(initializer)
    }

    forEach { visit(it) }
    return sorted
}
