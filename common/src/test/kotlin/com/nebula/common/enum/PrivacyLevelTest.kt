package com.nebula.common.enum

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * [PrivacyLevel] 枚举的单元测试。
 *
 * 测试策略：
 * - 验证 fromCode() 按 code 值正确映射到三个隐私级别
 * - 验证无效 code 抛出 NoSuchElementException
 * - 验证枚举值数量确保注册完整性
 */
class PrivacyLevelTest {

    // ─── fromCode() 正向映射 ──────────────────────────────────────────────────────

    @Test
    fun fromCode0ReturnsPublic() {
        assertEquals(PrivacyLevel.PUBLIC, PrivacyLevel.fromCode(0))
    }

    @Test
    fun fromCode1ReturnsFriendsOnly() {
        assertEquals(PrivacyLevel.FRIENDS_ONLY, PrivacyLevel.fromCode(1))
    }

    @Test
    fun fromCode2ReturnsPrivate() {
        assertEquals(PrivacyLevel.PRIVATE, PrivacyLevel.fromCode(2))
    }

    // ─── fromCode() 异常路径 ──────────────────────────────────────────────────────

    @Test
    fun fromCodeNegOneThrowsException() {
        assertThrows<NoSuchElementException> {
            PrivacyLevel.fromCode(-1)
        }
    }

    @Test
    fun fromCode999ThrowsException() {
        assertThrows<NoSuchElementException> {
            PrivacyLevel.fromCode(999)
        }
    }

    // ─── 枚举完整性 ──────────────────────────────────────────────────────────────

    @Test
    fun entriesHasThreeValues() {
        assertEquals(3, PrivacyLevel.entries.size)
    }

    @Test
    fun entriesContainsPublicFriendsOnlyAndPrivate() {
        val all = PrivacyLevel.entries
        assertEquals(
            setOf(PrivacyLevel.PUBLIC, PrivacyLevel.FRIENDS_ONLY, PrivacyLevel.PRIVATE),
            all.toSet()
        )
    }
}
