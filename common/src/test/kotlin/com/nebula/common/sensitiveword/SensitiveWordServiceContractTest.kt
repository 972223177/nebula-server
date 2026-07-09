package com.nebula.common.sensitiveword

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SensitiveWordService] 契约验证：覆盖敏感词服务的对外能力约定，而非具体引擎实现。
 *
 * 验证点（均为接口契约，与底层匹配引擎无关）：
 * - 变形绕过防御（D-108）：大小写 / 全角半角 / 插入分隔符 / 零宽字符等变形应被命中
 * - 脱敏不阻断投递（D-119）：命中即替换为 `*`、保留非命中文本
 *
 * 注：测试夹具需注入词库，而 `swap` 是具体实现类的内部替换方法（不在接口契约内），
 * 故此处直接构造具体实现作为夹具；这与「生产调用方只依赖接口」的隔离原则不冲突。
 */
class SensitiveWordServiceContractTest {

    private val service = HoubbSensitiveWordService(NoopWordFetcher(), "sensitive/does-not-exist.txt", false).apply {
        swap(listOf("fuck", "傻逼"))
    }

    @Test
    fun containsDetectsCaseBypass() {
        assertTrue(service.contains("FUCK"))
        assertTrue(service.contains("Fuck"))
    }

    @Test
    fun containsDetectsFullWidthBypass() {
        assertTrue(service.contains("ｆｕｃｋ"))
    }

    @Test
    fun containsDetectsSeparatorInsertion() {
        assertTrue(service.contains("f u c k"))
        assertTrue(service.contains("f.u.c.k"))
        assertTrue(service.contains("傻 逼"))
        assertTrue(service.contains("傻*逼"))
    }

    @Test
    fun containsDetectsZeroWidthBypass() {
        assertTrue(service.contains("傻﻿逼")) // 含零宽字符 U+200B
    }

    @Test
    fun containsDoesNotFalsePositiveOnCleanText() {
        assertFalse(service.contains("hello world"))
        assertFalse(service.contains("你好世界"))
    }

    @Test
    fun filterMasksMatchedRegion() {
        // 命中后替换整个匹配区间（含被忽略的分隔符/空格），统一打码
        assertEquals("你**好", service.filter("你傻逼好"))
        assertEquals("***", service.filter("傻 逼"))
        assertEquals("*******", service.filter("f.u.c.k"))
        assertEquals("*******", service.filter("f u c k"))
        assertEquals("***", service.filter("傻*逼"))
    }
}
