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
    fun `fromCode(0) — 返回 PENDING`() {
        assertEquals(FriendRequestStatus.PENDING, FriendRequestStatus.fromCode(0))
    }

    @Test
    fun `fromCode(1) — 返回 ACCEPTED`() {
        assertEquals(FriendRequestStatus.ACCEPTED, FriendRequestStatus.fromCode(1))
    }

    @Test
    fun `fromCode(2) — 返回 REJECTED`() {
        assertEquals(FriendRequestStatus.REJECTED, FriendRequestStatus.fromCode(2))
    }

    // ─── fromCode() 异常路径 ──────────────────────────────────────────────────────

    @Test
    fun `fromCode(-1) — 无匹配时抛出 NoSuchElementException`() {
        assertThrows<NoSuchElementException> {
            FriendRequestStatus.fromCode(-1)
        }
    }

    @Test
    fun `fromCode(999) — 无匹配时抛出 NoSuchElementException`() {
        assertThrows<NoSuchElementException> {
            FriendRequestStatus.fromCode(999)
        }
    }

    // ─── 枚举完整性 ──────────────────────────────────────────────────────────────

    @Test
    fun `entries 包含三个枚举值`() {
        assertEquals(3, FriendRequestStatus.entries.size)
    }

    @Test
    fun `entries 包含 PENDING、ACCEPTED 和 REJECTED`() {
        val all = FriendRequestStatus.entries
        assertEquals(
            setOf(FriendRequestStatus.PENDING, FriendRequestStatus.ACCEPTED, FriendRequestStatus.REJECTED),
            all.toSet()
        )
    }
}
