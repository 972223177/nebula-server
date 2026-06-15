package com.nebula.common.enum

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * [ConversationStatus] 枚举的单元测试。
 *
 * 测试策略：
 * - 验证 fromCode() 按 code 值正确映射到枚举常量
 * - 验证无效 code 抛出 NoSuchElementException
 * - 验证枚举值数量确保与 DDL 一致
 */
class ConversationStatusTest {

    // ─── fromCode() 正向映射 ──────────────────────────────────────────────────────

    @Test
    fun `fromCode(0) — 返回 ACTIVE`() {
        assertEquals(ConversationStatus.ACTIVE, ConversationStatus.fromCode(0))
    }

    @Test
    fun `fromCode(1) — 返回 DISMISSED`() {
        assertEquals(ConversationStatus.DISMISSED, ConversationStatus.fromCode(1))
    }

    // ─── fromCode() 异常路径 ──────────────────────────────────────────────────────

    @Test
    fun `fromCode(-1) — 无匹配时抛出 NoSuchElementException`() {
        assertThrows<NoSuchElementException> {
            ConversationStatus.fromCode(-1)
        }
    }

    @Test
    fun `fromCode(999) — 无匹配时抛出 NoSuchElementException`() {
        assertThrows<NoSuchElementException> {
            ConversationStatus.fromCode(999)
        }
    }

    // ─── 枚举完整性 ──────────────────────────────────────────────────────────────

    @Test
    fun `entries 包含两个枚举值`() {
        assertEquals(2, ConversationStatus.entries.size)
    }

    @Test
    fun `entries 包含 ACTIVE 和 DISMISSED`() {
        val all = ConversationStatus.entries
        assertEquals(setOf(ConversationStatus.ACTIVE, ConversationStatus.DISMISSED), all.toSet())
    }
}
