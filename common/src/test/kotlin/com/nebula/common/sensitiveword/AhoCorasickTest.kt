package com.nebula.common.sensitiveword

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Aho-Corasick 多模式匹配器单元验证。
 */
class AhoCorasickTest {

    @Test
    fun `contains 命中单个敏感词`() {
        val ac = AhoCorasick(listOf("敏感词", "暴力"))
        assertTrue(ac.contains("这是一句敏感词汇"))
        assertTrue(ac.contains("禁止暴力内容"))
        assertFalse(ac.contains("这是正常文本"))
    }

    @Test
    fun `contains 支持重叠词（短词与长词共存）`() {
        val ac = AhoCorasick(listOf("他妈", "他妈的"))
        assertTrue(ac.contains("他妈的"))
    }

    @Test
    fun `filter 将命中区间替换为掩码`() {
        val ac = AhoCorasick(listOf("敏感词", "测试"))
        assertEquals("abc***def", ac.filter("abc敏感词def"))
        assertEquals("***", ac.filter("敏感词"))
    }

    @Test
    fun `filter 重叠词合并为一段掩码`() {
        val ac = AhoCorasick(listOf("他妈", "他妈的"))
        assertEquals("***", ac.filter("他妈的"))
    }

    @Test
    fun `空词库不过载`() {
        val ac = AhoCorasick(emptyList())
        assertFalse(ac.contains("任意文本"))
        assertEquals("任意文本", ac.filter("任意文本"))
    }
}
