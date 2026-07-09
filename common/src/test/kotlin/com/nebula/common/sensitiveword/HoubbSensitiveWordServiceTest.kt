package com.nebula.common.sensitiveword

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [HoubbSensitiveWordService] 行为验证：覆盖常见变形绕过（D-108 绕过防御）与脱敏保留分隔符（D-119）。
 */
class HoubbSensitiveWordServiceTest {

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
        // houbb 命中后替换整个匹配区间（含被忽略的分隔符/空格），统一打码
        assertEquals("你**好", service.filter("你傻逼好"))
        assertEquals("***", service.filter("傻 逼"))
        assertEquals("*******", service.filter("f.u.c.k"))
        assertEquals("*******", service.filter("f u c k"))
        assertEquals("***", service.filter("傻*逼"))
    }
}
