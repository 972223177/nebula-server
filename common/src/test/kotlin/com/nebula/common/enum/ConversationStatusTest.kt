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
    fun fromCode0ReturnsActive() {
        assertEquals(ConversationStatus.ACTIVE, ConversationStatus.fromCode(0))
    }

    @Test
    fun fromCode1ReturnsDismissed() {
        assertEquals(ConversationStatus.DISMISSED, ConversationStatus.fromCode(1))
    }

    // ─── fromCode() 异常路径 ──────────────────────────────────────────────────────

    @Test
    fun fromCodeNegOneThrowsException() {
        assertThrows<NoSuchElementException> {
            ConversationStatus.fromCode(-1)
        }
    }

    @Test
    fun fromCode999ThrowsException() {
        assertThrows<NoSuchElementException> {
            ConversationStatus.fromCode(999)
        }
    }

    // ─── 枚举完整性 ──────────────────────────────────────────────────────────────

    @Test
    fun entriesHasTwoValues() {
        assertEquals(2, ConversationStatus.entries.size)
    }

    @Test
    fun entriesContainsActiveAndDismissed() {
        val all = ConversationStatus.entries
        assertEquals(setOf(ConversationStatus.ACTIVE, ConversationStatus.DISMISSED), all.toSet())
    }
}
