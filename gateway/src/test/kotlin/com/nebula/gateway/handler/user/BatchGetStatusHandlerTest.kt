package com.nebula.gateway.handler.user

import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.BatchGetStatusResp
import com.nebula.service.user.OnlineStatusInfo
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * BatchGetStatusHandler 批量在线状态查询 Handler 单元测试（BIZ-USER-04, D-10, D-57）。
 *
 * D-57 三值状态适配：使用 getStatus 替代旧的 isOnline。
 *
 * 覆盖场景：
 * - 查询在线状态 — 无隐藏用户：正常返回所有用户的在线状态
 * - 隐藏用户被过滤：hideOnlineStatus=true 的用户不在结果中返回
 * - 混合状态：同时存在隐藏、在线、离线三种状态的用户
 * - 空列表：空输入返回空结果
 */
class BatchGetStatusHandlerTest {

    private lateinit var onlineStatusService: OnlineStatusService
    private lateinit var privacyService: UserPrivacyService
    private lateinit var handler: BatchGetStatusHandler

    @BeforeEach
    fun setup() {
        onlineStatusService = mockk()
        privacyService = mockk()
        handler = BatchGetStatusHandler(onlineStatusService, privacyService)
    }

    @Test
    fun queryOnlineStatusWithoutHiddenUsers() = runTest {
        coEvery { privacyService.batchGetHideOnlineStatus(listOf(1L)) } returns emptySet()
        // D-57: 使用 getStatus 替代 isOnline
        coEvery { onlineStatusService.getStatus(1L) } returns OnlineStatusInfo(status = 1)

        val req = BatchIdRequest.newBuilder().addAllUids(listOf(1L)).build()
        val resp = handler.handle(req)

        assertEquals(1, resp.statusesList.size)
        assertEquals(1L, resp.getStatuses(0).uid)
        assertEquals(1, resp.getStatuses(0).status)  // 1=在线
    }

    @Test
    fun hiddenUsersShouldBeFiltered() = runTest {
        coEvery { privacyService.batchGetHideOnlineStatus(listOf(1L, 2L)) } returns setOf(1L)
        coEvery { onlineStatusService.getStatus(2L) } returns OnlineStatusInfo(status = 1)

        val req = BatchIdRequest.newBuilder().addAllUids(listOf(1L, 2L)).build()
        val resp = handler.handle(req)

        // uid=1（隐藏用户）不在结果中，只有 uid=2
        assertEquals(1, resp.statusesList.size)
        assertEquals(2L, resp.getStatuses(0).uid)
    }

    @Test
    fun mixedStatusShouldBeHandled() = runTest {
        val hiddenUid = 1L
        val onlineUid = 2L
        val offlineUid = 3L

        coEvery { privacyService.batchGetHideOnlineStatus(listOf(1L, 2L, 3L)) } returns setOf(hiddenUid)
        coEvery { onlineStatusService.getStatus(onlineUid) } returns OnlineStatusInfo(status = 1)
        coEvery { onlineStatusService.getStatus(offlineUid) } returns null  // null = 离线

        val req = BatchIdRequest.newBuilder().addAllUids(listOf(1L, 2L, 3L)).build()
        val resp = handler.handle(req)

        // 隐藏用户 1 被过滤，只有用户 2（在线）和用户 3（离线）
        assertEquals(2, resp.statusesList.size)
        assertEquals(onlineUid, resp.getStatuses(0).uid)
        assertEquals(1, resp.getStatuses(0).status)   // 在线
        assertEquals(offlineUid, resp.getStatuses(1).uid)
        assertEquals(0, resp.getStatuses(1).status)   // 离线
    }

    @Test
    fun batchStatusEmptyListShouldReturnEmpty() = runTest {
        coEvery { privacyService.batchGetHideOnlineStatus(emptyList()) } returns emptySet()

        val req = BatchIdRequest.getDefaultInstance()
        val resp = handler.handle(req)

        assertEquals(0, resp.statusesList.size)
    }
}
