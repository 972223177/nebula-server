package com.nebula.gateway.testutil

/**
 * 测试分类标签常量。
 *
 * JUnit5 @Tag 用于在 Gradle test task 中过滤测试集：
 * - [KOIN_DI] — Koin DI 容器测试，单独 Gradle task 运行（forkEvery=0，关闭 JVM fork 开销）
 * - 其余测试保持 forkEvery=1（每类独立 JVM，隔离 Koin 状态污染）
 */
object TestTags {
    /** Koin DI 容器测试标签 */
    const val KOIN_DI = "koin-di"
}
