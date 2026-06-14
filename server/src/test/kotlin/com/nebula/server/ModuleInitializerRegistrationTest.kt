package com.nebula.server

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.common.init.ModuleInitializer
import com.nebula.common.init.commonInitModule
import com.nebula.common.init.topologicalSort
import com.nebula.common.config.ApplicationConfig
import com.nebula.common.datasource.DataSourceProvider
import com.nebula.repository.init.repositoryInitModule
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ModuleInitializer 注册与拓扑排序集成测试（D-28）。
 *
 * 验证多个 [ModuleInitializer] 实现在 Koin 容器中注册后，
 * [GlobalContext.getAll] 能正确发现所有实例，[topologicalSort] 能按依赖正
 * 确排序。
 *
 * 背景：Koin 4.1.0 中两个 [single]<[ModuleInitializer]> 使用相同的类型键
 * 会导致后者覆盖前者。修复方案是为每个 bean 添加唯一的 [named] 限定符
 *（参见 [CommonInitModule] 和 [RepositoryInitModule] 的 named 参数）。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class ModuleInitializerRegistrationTest {

    /** 构建外部依赖 mock 模块 — 避免初始化器因缺少真实基础设施而失败 */
    private fun buildMockModule() = module {
        single { mockk<ApplicationConfig>(relaxed = true) }
        single { mockk<DataSourceProvider>(relaxed = true) }
        single { mockk<SnowflakeIdGenerator>(relaxed = true) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    // ==================== Koin 注册验证 ====================

    /**
     * 验证 commonInitModule 和 repositoryInitModule 同时注册后，
     * [GlobalContext.getAll]<[ModuleInitializer]> 能返回两个实例。
     */
    @Test
    fun `both ModuleInitializers are discoverable via getAll`() {
        startKoin {
            modules(commonInitModule, repositoryInitModule, buildMockModule())
        }

        val koin = GlobalContext.get()
        val initializers: List<ModuleInitializer> = koin.getAll()

        assertEquals(2, initializers.size,
            "应发现 2 个 ModuleInitializer：common 和 repository")

        val names = initializers.map { it.name }.toSet()
        assertTrue("common" in names,
            "应包含名为 'common' 的 ModuleInitializer（CommonModuleInitializer）")
        assertTrue("repository" in names,
            "应包含名为 'repository' 的 ModuleInitializer（RepositoryModuleInitializer）")
    }

    /**
     * 验证 [CommonModuleInitializer] 和 [RepositoryModuleInitializer]
     * 可通过接口类型 [ModuleInitializer] 配合 [named] 限定符单独解析。
     *
     * 注：在 Koin 中，[single]<[ModuleInitializer]>(named("common"))
     * 注册的 primary type 是 [ModuleInitializer] 而非具体类，因此需通过
     * [ModuleInitializer] 类型 + 限定符解析具体实例。
     */
    @Test
    fun `each ModuleInitializer is resolvable by interface type with qualifier`() {
        startKoin {
            modules(commonInitModule, repositoryInitModule, buildMockModule())
        }

        val koin = GlobalContext.get()

        val common = koin.get<ModuleInitializer>(named("common"))
        assertEquals("common", common.name,
            "通过 named(\"common\") 应解析到 CommonModuleInitializer")

        val repository = koin.get<ModuleInitializer>(named("repository"))
        assertEquals("repository", repository.name,
            "通过 named(\"repository\") 应解析到 RepositoryModuleInitializer")
    }

    // ==================== 拓扑排序验证 ====================

    /**
     * 验证 [CommonModuleInitializer]（无依赖）和
     * [RepositoryModuleInitializer]（依赖 "common"）经拓扑排序后，
     * "common" 排在 "repository" 之前。
     */
    @Test
    fun `topologicalSort places common before repository`() {
        startKoin {
            modules(commonInitModule, repositoryInitModule, buildMockModule())
        }

        val koin = GlobalContext.get()
        val initializers: List<ModuleInitializer> = koin.getAll()
        val sorted = initializers.topologicalSort()

        // 拓扑排序前后元素数一致
        assertEquals(initializers.size, sorted.size,
            "拓扑排序不应丢失或增加元素")

        val names = sorted.map { it.name }
        // "common" 必须在 "repository" 之前（因为 repository 依赖 common）
        assertTrue(
            names.indexOf("common") < names.indexOf("repository"),
            "拓扑排序结果中 'common' 应出现在 'repository' 之前"
        )
    }

    /**
     * 验证 topologySort 结果中每个元素都按依赖顺序排列，
     * 与原始 [ModuleInitializerRegistrationTest] 中的顺序无关。
     */
    @Test
    fun `topologicalSort is idempotent and produces valid order`() {
        startKoin {
            modules(commonInitModule, repositoryInitModule, buildMockModule())
        }

        val koin = GlobalContext.get()
        val initializers: List<ModuleInitializer> = koin.getAll()

        // 第一次排序
        val sorted1 = initializers.topologicalSort()
        val names1 = sorted1.map { it.name }

        // 第二次排序（验证幂等性）
        val sorted2 = sorted1.topologicalSort()
        val names2 = sorted2.map { it.name }

        assertContentEquals(names1, names2,
            "拓扑排序应满足幂等性：对已排序结果再次排序结果不变")

        // 验证依赖顺序：对每个 initializer，其所有依赖必须出现在它之前
        val indexMap = names1.mapIndexed { idx, name -> name to idx }.toMap()
        for (initializer in sorted1) {
            for (dep in initializer.dependencies) {
                assertTrue(
                    indexMap.getValue(dep) < indexMap.getValue(initializer.name),
                    "依赖 '$dep' 应在 '${initializer.name}' 之前"
                )
            }
        }
    }
}
