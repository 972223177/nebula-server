package com.nebula.gateway.delivery

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * DeliveryTrackingService 单元测试（D-70, D-71, D-72）。
 *
 * 覆盖三态转换规则：
 * - sent（0）→ delivered（1）：正常投递确认
 * - sent（0）→ read（2）：允许跳级（离线阅读后上线）
 * - delivered（1）→ read（2）：正常已读路径
 * - delivered / read 状态拒绝降级
 * - read 状态拒绝重复已读
 * - 键不存在时 markRead 正常处理
 */
class DeliveryTrackingServiceTest {

    /** mock Redis 低层投递跟踪器（严格模式） */
    private lateinit var tracker: RedisDeliveryTracker

    /** 被测对象 */
    private lateinit var service: DeliveryTrackingService

    /** 测试用消息 ID */
    private val msgId = 10001L

    /** 测试用用户 ID */
    private val uid = 2001L

    @BeforeEach
    fun setUp() {
        tracker = mockk()
        service = DeliveryTrackingService(tracker)
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ──────────────────────────────────────────────
    // markSent
    // ──────────────────────────────────────────────

    @Test
    fun markSentShouldAlwaysSucceed() = runTest {
        coEvery { tracker.setStatus(msgId, uid, 0) } returns true

        val result = service.markSent(msgId, uid)

        assertEquals(true, result, "markSent 应无条件标记成功")
        coVerify { tracker.setStatus(msgId, uid, 0) }
    }

    // ──────────────────────────────────────────────
    // markDelivered
    // ──────────────────────────────────────────────

    @Test
    fun markDeliveredShouldSucceedWhenCurrentIsSent() = runTest {
        coEvery { tracker.getStatus(msgId, uid) } returns 0
        coEvery { tracker.setStatus(msgId, uid, 1) } returns true

        val result = service.markDelivered(msgId, uid)

        assertEquals(true, result, "当前状态为 sent（0）时 markDelivered 应成功")
        coVerify {
            tracker.getStatus(msgId, uid)
            tracker.setStatus(msgId, uid, 1)
        }
    }

    @Test
    fun markDeliveredShouldRejectWhenCurrentIsDelivered() = runTest {
        coEvery { tracker.getStatus(msgId, uid) } returns 1

        val result = service.markDelivered(msgId, uid)

        assertEquals(false, result, "当前状态为 delivered（1）时 markDelivered 应拒绝降级")
        coVerify {
            tracker.getStatus(msgId, uid)
            // setStatus 不应被调用
        }
        coVerify(exactly = 0) { tracker.setStatus(any(), any(), any()) }
    }

    @Test
    fun markDeliveredShouldRejectWhenCurrentIsRead() = runTest {
        coEvery { tracker.getStatus(msgId, uid) } returns 2

        val result = service.markDelivered(msgId, uid)

        assertEquals(false, result, "当前状态为 read（2）时 markDelivered 应拒绝降级")
        coVerify(exactly = 0) { tracker.setStatus(any(), any(), any()) }
    }

    @Test
    fun markDeliveredShouldSucceedWhenCurrentIsNull() = runTest {
        // 键不存在（getStatus 返回 null）→ 应正常写入 delivered 状态（D-70 首次标记）
        coEvery { tracker.getStatus(msgId, uid) } returns null
        coEvery { tracker.setStatus(msgId, uid, 1) } returns true

        val result = service.markDelivered(msgId, uid)

        assertEquals(true, result, "当前状态为 null（键不存在）时 markDelivered 应成功")
        coVerify {
            tracker.getStatus(msgId, uid)
            tracker.setStatus(msgId, uid, 1)
        }
    }

    @Test
    fun markDeliveredShouldPreventDuplicateFromMultipleDevices() = runTest {
        // 模拟多设备场景：第一设备标记 sent→delivered 成功
        coEvery { tracker.getStatus(msgId, uid) } returns 0 andThen 1
        coEvery { tracker.setStatus(msgId, uid, 1) } returns true

        val result1 = service.markDelivered(msgId, uid)
        assertEquals(true, result1, "第一设备 markDelivered 应成功")

        // 第二设备尝试标记 → 因已 delivered 被拒绝（HSETNX 语义）
        val result2 = service.markDelivered(msgId, uid)
        assertEquals(false, result2, "多设备重复 markDelivered 应被拒绝")

        coVerify(exactly = 1) { tracker.setStatus(msgId, uid, 1) }
    }

    // ──────────────────────────────────────────────
    // markRead
    // ──────────────────────────────────────────────

    @Test
    fun markReadShouldSucceedWhenCurrentIsSent() = runTest {
        coEvery { tracker.getStatus(msgId, uid) } returns 0
        coEvery { tracker.setStatus(msgId, uid, 2) } returns true

        val result = service.markRead(msgId, uid)

        assertEquals(true, result, "当前状态为 sent（0）时 markRead 应允许跳级")
        coVerify {
            tracker.getStatus(msgId, uid)
            tracker.setStatus(msgId, uid, 2)
        }
    }

    @Test
    fun markReadShouldSucceedWhenCurrentIsDelivered() = runTest {
        coEvery { tracker.getStatus(msgId, uid) } returns 1
        coEvery { tracker.setStatus(msgId, uid, 2) } returns true

        val result = service.markRead(msgId, uid)

        assertEquals(true, result, "当前状态为 delivered（1）时 markRead 应成功")
        coVerify {
            tracker.getStatus(msgId, uid)
            tracker.setStatus(msgId, uid, 2)
        }
    }

    @Test
    fun markReadShouldRejectWhenCurrentIsRead() = runTest {
        coEvery { tracker.getStatus(msgId, uid) } returns 2

        val result = service.markRead(msgId, uid)

        assertEquals(false, result, "当前状态为 read（2）时 markRead 应拒绝重复标记")
        coVerify(exactly = 0) { tracker.setStatus(any(), any(), any()) }
    }

    @Test
    fun markReadShouldSucceedWhenCurrentIsNull() = runTest {
        // 键不存在（getStatus 返回 null）→ 应正常写入 read 状态
        coEvery { tracker.getStatus(msgId, uid) } returns null
        coEvery { tracker.setStatus(msgId, uid, 2) } returns true

        val result = service.markRead(msgId, uid)

        assertEquals(true, result, "当前状态为 null（键不存在）时 markRead 应成功")
        coVerify {
            tracker.getStatus(msgId, uid)
            tracker.setStatus(msgId, uid, 2)
        }
    }
}
