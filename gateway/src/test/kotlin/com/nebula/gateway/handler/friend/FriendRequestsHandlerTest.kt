package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendRequestItem
import com.nebula.chat.friend.FriendRequestsReq
import com.nebula.chat.friend.FriendRequestsResp
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.sessionContext
import com.nebula.service.friend.FriendService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FriendRequestsHandler 获取待处理好友申请列表 Handler 单元测试（D-41）。
 *
 * 覆盖场景：
 * - 正常查询待处理申请 → 返回 FriendRequestsResp 含完整字段
 * - 空列表 → 返回空 FriendRequestsResp
 */
class FriendRequestsHandlerTest {

    private lateinit var friendService: FriendService
    private lateinit var handler: FriendRequestsHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        friendService = mockk()
        handler = FriendRequestsHandler(friendService)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常查询待处理申请
    // ═══════════════════════════════════════════════════════════

    @Test
    fun queryShouldReturnFullFriendRequestFields() = runTest(sessionContext(session)) {
        // Given: 当前用户有两条 pending 的好友申请
        val expectedResp = FriendRequestsResp.newBuilder()
            .addRequests(FriendRequestItem.newBuilder()
                .setRequestId(10L)
                .setFromUid(2001L)
                .setFromUsername("user2")
                .setFromAvatar("https://example.com/avatar2.jpg")
                .setMessage("你好，加个好友")
                .setStatus("pending")
                .setCreatedAt(1700000000000L)
                .build())
            .addRequests(FriendRequestItem.newBuilder()
                .setRequestId(11L)
                .setFromUid(3001L)
                .setFromUsername("user3")
                .setFromAvatar("https://example.com/avatar3.jpg")
                .setMessage("好久不见")
                .setStatus("pending")
                .setCreatedAt(1700000000000L)
                .build())
            .build()

        coEvery { friendService.getFriendRequests(any<FriendRequestsReq>(), any()) } returns expectedResp

        val req = FriendRequestsReq.getDefaultInstance()

        // When: 执行查询
        val result = handler.handle(req)

        // Then: 验证返回两条申请
        assertEquals(2, result.requestsCount)

        // 验证第一条申请的完整字段
        val item1 = result.getRequests(0)
        assertEquals(10L, item1.requestId)
        assertEquals(2001L, item1.fromUid)
        assertEquals("user2", item1.fromUsername)
        assertEquals("https://example.com/avatar2.jpg", item1.fromAvatar)
        assertEquals("你好，加个好友", item1.message)
        assertEquals("pending", item1.status)
        assertTrue(item1.createdAt > 0)

        // 验证第二条申请的字段
        val item2 = result.getRequests(1)
        assertEquals(11L, item2.requestId)
        assertEquals(3001L, item2.fromUid)
        assertEquals("user3", item2.fromUsername)
        assertEquals("https://example.com/avatar3.jpg", item2.fromAvatar)
        assertEquals("好久不见", item2.message)
        assertEquals("pending", item2.status)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 2：空列表
    // ═══════════════════════════════════════════════════════════

    @Test
    fun requestsEmptyShouldReturnEmptyResp() = runTest(sessionContext(session)) {
        // Given: 当前用户没有 pending 的好友申请
        val expectedResp = FriendRequestsResp.getDefaultInstance()

        coEvery { friendService.getFriendRequests(any<FriendRequestsReq>(), any()) } returns expectedResp

        val req = FriendRequestsReq.getDefaultInstance()

        // When: 执行查询
        val result = handler.handle(req)

        // Then: 返回空列表
        assertEquals(0, result.requestsCount)
    }

    @Test
    fun handleShouldRequireSession() = runTest {
        val exception = kotlin.test.assertFailsWith<com.nebula.common.exception.BizException> {
            val req = com.nebula.chat.friend.FriendRequestsReq.getDefaultInstance()
            handler.handle(req)
        }
        kotlin.test.assertEquals(com.nebula.common.BizCode.UNAUTHORIZED, exception.bizCode, "无 Session 时应抛出 UNAUTHORIZED")
    }

}
