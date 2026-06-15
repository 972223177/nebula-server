package com.nebula.common.enum

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * [ConversationType] 枚举的单元测试。
 *
 * 测试策略：
 * - 验证 fromCode() 按 code 值正确映射到枚举常量
 * - 验证无效 code 抛出 NoSuchElementException
 * - 验证枚举值数量确保注册完整性
 */
class ConversationTypeTest {

    // ─── fromCode() 正向映射 ──────────────────────────────────────────────────────

    @Test
    fun `fromCode(1) — 返回 PRIVATE`() {
        assertEquals(ConversationType.PRIVATE, ConversationType.fromCode(1))
    }

    @Test
    fun `fromCode(2) — 返回 GROUP`() {
        assertEquals(ConversationType.GROUP, ConversationType.fromCode(2))
    }

    // ─── fromCode() 异常路径 ──────────────────────────────────────────────────────

    @Test
    fun `fromCode(999) — 无匹配时抛出 NoSuchElementException`() {
        assertThrows<NoSuchElementException> {
            ConversationType.fromCode(999)
        }
    }

    // ─── 枚举完整性 ──────────────────────────────────────────────────────────────

    @Test
    fun `entries 包含两个枚举值`() {
        assertEquals(2, ConversationType.entries.size)
    }

    @Test
    fun `entries 包含 PRIVATE 和 GROUP`() {
        val all = ConversationType.entries
        assertEquals(setOf(ConversationType.PRIVATE, ConversationType.GROUP), all.toSet())
    }
}
