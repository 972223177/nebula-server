package com.nebula.common.sensitiveword

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 归一化层（M1 review）验证：确保常见变形绕过无法逃逸检测。
 */
class SensitiveWordNormalizerTest {

    private val service = SensitiveWordService(NoopWordFetcher(), "sensitive/does-not-exist.txt", false).apply {
        swap(listOf("fuck", "傻逼"))
    }

    @Test
    fun normalizeFoldsCaseAndWidthAndStripsNoise() {
        val n = SensitiveWordNormalizer.normalize("ＦＵＣＫ") // 全角大写
        assertEquals("fuck", n.text, "全角大写应折叠为半角小写")
        assertEquals(listOf(0, 1, 2, 3), n.srcIndex.toList())
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
    fun filterMasksOriginalIncludingSeparators() {
        // 掩码区间映射回原串，连被剔除的分隔符/空格一并覆盖
        assertEquals("你**好", service.filter("你傻逼好"))
        assertEquals("***", service.filter("傻 逼"))
        assertEquals("*******", service.filter("f.u.c.k"))
    }
}
