package com.nebula.common.init

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 用于拓扑排序测试的 [ModuleInitializer] 桩实现。
 *
 * @param name 模块名称
 * @param dependencies 依赖的其他模块名称列表
 */
class TestModuleInitializer(
    override val name: String,
    override val dependencies: List<String> = emptyList()
) : ModuleInitializer {
    override fun init() { /* no-op */ }
}

/**
 * [List<ModuleInitializer>.topologicalSort] 的单元测试。
 *
 * 覆盖以下场景：
 * - 空列表 / 单元素 / 无依赖多元素
 * - 简单依赖链 / 多依赖
 * - 循环依赖异常 / 缺失依赖异常
 */
class ModuleInitializerSorterTest {

    private fun sort(vararg modules: ModuleInitializer): List<ModuleInitializer> =
        modules.toList().topologicalSort()

    // ==================== 正常场景 ====================

    /** 空列表返回空列表 */
    @Test
    fun emptyListReturnsEmptyList() {
        val result = emptyList<ModuleInitializer>().topologicalSort()
        assertTrue(result.isEmpty(), "topologicalSort on empty list should return empty list")
    }

    /** 单个元素返回包含该元素的列表 */
    @Test
    fun singleElementReturnsThatElement() {
        val a = TestModuleInitializer("A")
        val result = sort(a)
        assertEquals(1, result.size, "result should contain exactly one element")
        assertEquals("A", result[0].name, "the only element should be A")
    }

    /** 无依赖关系的多个元素保持原始顺序 */
    @Test
    fun independentElementsPreserveOriginalOrder() {
        val a = TestModuleInitializer("A")
        val b = TestModuleInitializer("B")
        val c = TestModuleInitializer("C")
        val result = sort(a, b, c)
        assertEquals(3, result.size, "result should contain all 3 elements")
        assertEquals(listOf("A", "B", "C"), result.map { it.name },
            "independent elements should preserve original order")
    }

    /** 简单依赖链：A -> B -> C，排序后为 [C, B, A] */
    @Test
    fun simpleDependencyChain() {
        val c = TestModuleInitializer("C")
        val b = TestModuleInitializer("B", dependencies = listOf("C"))
        val a = TestModuleInitializer("A", dependencies = listOf("B"))
        val result = sort(a, b, c)
        assertEquals(3, result.size, "result should contain all 3 elements")
        val names = result.map { it.name }
        // C 必须在 B 之前，B 必须在 A 之前
        assertTrue(names.indexOf("C") < names.indexOf("B"),
            "C should appear before B")
        assertTrue(names.indexOf("B") < names.indexOf("A"),
            "B should appear before A")
    }

    /** 多依赖：A 依赖 B 和 C，B 和 C 必须在 A 之前 */
    @Test
    fun multipleDependencies() {
        val b = TestModuleInitializer("B")
        val c = TestModuleInitializer("C")
        val a = TestModuleInitializer("A", dependencies = listOf("B", "C"))
        val result = sort(a, b, c)
        assertEquals(3, result.size, "result should contain all 3 elements")
        val names = result.map { it.name }
        assertTrue(names.indexOf("B") < names.indexOf("A"),
            "B should appear before A")
        assertTrue(names.indexOf("C") < names.indexOf("A"),
            "C should appear before A")
    }

    // ==================== 异常场景 ====================

    /** 循环依赖（A -> B -> A）抛出 IllegalStateException */
    @Test
    fun cycleDetectionThrowsException() {
        val a = TestModuleInitializer("A", dependencies = listOf("B"))
        val b = TestModuleInitializer("B", dependencies = listOf("A"))
        val exception = assertThrows<IllegalStateException>(
            "cycle between A and B should throw IllegalStateException"
        ) {
            sort(a, b)
        }
        assertTrue(exception.message!!.contains("循环依赖"),
            "exception message should mention cycle")
    }

    /** 缺失依赖抛出 IllegalStateException */
    @Test
    fun missingDependencyThrowsException() {
        val a = TestModuleInitializer("A", dependencies = listOf("non_existent"))
        val exception = assertThrows<IllegalStateException>(
            "missing dependency should throw IllegalStateException"
        ) {
            sort(a)
        }
        assertTrue(exception.message!!.contains("non_existent"),
            "exception message should mention the missing dependency name")
    }
}
