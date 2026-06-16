package com.nebula.common.enum

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals

/**
 * [FriendRequestStatus] 枚举的单元测试。
 *
 * 测试策略：
 * - 验证 fromCode() 按 code 值正确映射到三种申请状态
 * - 验证无效 code 抛出 NoSuchElementException
 * - 验证枚举值数量确保与 DDL 一致
 */
class FriendRequestStatusTest {

    // ─── fromCode() 正向映射 ──────────────────────────────────────────────────────

    @Test
    fun fromCode0ReturnsPending() {
        assertEquals(FriendRequestStatus.PENDING, FriendRequestStatus.fromCode(0))
    }

    @Test
    fun fromCode1ReturnsAccepted() {
        assertEquals(FriendRequestStatus.ACCEPTED, FriendRequestStatus.fromCode(1))
    }

    @Test
    fun fromCode2ReturnsRejected() {
        assertEquals(FriendRequestStatus.REJECTED, FriendRequestStatus.fromCode(2))
    }

    // ─── fromCode() 异常路径 ──────────────────────────────────────────────────────

    @Test
    fun fromCodeNegOneThrowsException() {
        assertThrows<NoSuchElementException> {
            FriendRequestStatus.fromCode(-1)
        }
    }

    @Test
    fun fromCode999ThrowsException() {
        assertThrows<NoSuchElementException> {
            FriendRequestStatus.fromCode(999)
        }
    }

    // ─── 枚举完整性 ──────────────────────────────────────────────────────────────

    @Test
    fun entriesHasThreeValues() {
        assertEquals(3, FriendRequestStatus.entries.size)
    }

    @Test
    fun entriesContainsPendingAcceptedAndRejected() {
        val all = FriendRequestStatus.entries
        assertEquals(
            setOf(FriendRequestStatus.PENDING, FriendRequestStatus.ACCEPTED, FriendRequestStatus.REJECTED),
            all.toSet()
        )
    }
}
