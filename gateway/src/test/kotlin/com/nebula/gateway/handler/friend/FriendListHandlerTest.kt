package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendBrief
import com.nebula.chat.friend.FriendListReq
import com.nebula.chat.friend.FriendListResp
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.sessionContext
import com.nebula.service.friend.FriendService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * FriendListHandler 获取好友列表 Handler 单元测试（D-46）。
 *
 * 覆盖场景：
 * - 正常分页查询 → 返回好友列表含完整 6 个字段
 * - 空好友列表 → 返回空列表
 * - 隐藏用户过滤 → 隐藏用户在线状态显示为 0
 */
class FriendListHandlerTest {

    private lateinit var friendService: FriendService
    private lateinit var handler: FriendListHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        friendService = mockk()
        handler = FriendListHandler(friendService)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常分页查询
    // ═══════════════════════════════════════════════════════════

    @Test
    fun listShouldReturnFullFriendFields() = runTest(sessionContext(session)) {
        // Given: 当前用户有两个好友
        val expectedResp = FriendListResp.newBuilder()
            .addFriends(FriendBrief.newBuilder()
                .setUid(2001L)
                .setUsername("user2")
                .setDisplayName("User Two")
                .setAvatarUrl("https://example.com/avatar2.jpg")
                .setStatus(1)
                .build())
            .addFriends(FriendBrief.newBuilder()
                .setUid(3001L)
                .setUsername("user3")
                .setDisplayName("User Three")
                .setAvatarUrl("https://example.com/avatar3.jpg")
                .setStatus(0)
                .build())
            .build()

        coEvery { friendService.listFriends(any<FriendListReq>(), any()) } returns expectedResp

        val req = FriendListReq.newBuilder()
            .setCursor(0L)
            .setLimit(20)
            .build()

        // When: 执行查询
        val result = handler.handle(req)

        // Then: 验证返回两个好友
        assertEquals(2, result.friendsCount)

        // 验证第一个好友的完整 6 个字段（uid, username, displayName, avatarUrl, status, createdAt）
        val friend1 = result.getFriends(0)
        assertEquals(2001L, friend1.uid)
        assertEquals("user2", friend1.username)
        assertEquals("User Two", friend1.displayName)
        assertEquals("https://example.com/avatar2.jpg", friend1.avatarUrl)
        assertEquals(1, friend1.status)       // 在线

        // 验证第二个好友
        val friend2 = result.getFriends(1)
        assertEquals(3001L, friend2.uid)
        assertEquals("user3", friend2.username)
        assertEquals("User Three", friend2.displayName)
        assertEquals("https://example.com/avatar3.jpg", friend2.avatarUrl)
        assertEquals(0, friend2.status)       // 离线
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 2：空好友列表
    // ═══════════════════════════════════════════════════════════

    @Test
    fun listEmptyShouldReturnEmpty() = runTest(sessionContext(session)) {
        // Given: 当前用户没有好友记录
        val expectedResp = FriendListResp.getDefaultInstance()

        coEvery { friendService.listFriends(any<FriendListReq>(), any()) } returns expectedResp

        val req = FriendListReq.newBuilder()
            .setCursor(0L)
            .setLimit(20)
            .build()

        // When: 执行查询
        val result = handler.handle(req)

        // Then: 返回空列表（默认实例）
        assertEquals(0, result.friendsCount)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 3：隐藏用户过滤
    // ═══════════════════════════════════════════════════════════

    @Test
    fun hiddenUserFilterShouldHideOnlineStatus() = runTest(sessionContext(session)) {
        // Given: 当前用户有一个好友，但该好友设置了隐藏在线状态
        val expectedResp = FriendListResp.newBuilder()
            .addFriends(FriendBrief.newBuilder()
                .setUid(2001L)
                .setUsername("user2")
                .setDisplayName("User Two")
                .setAvatarUrl("https://example.com/avatar2.jpg")
                .setStatus(0)  // 隐藏用户在线状态为 0
                .build())
            .build()

        coEvery { friendService.listFriends(any<FriendListReq>(), any()) } returns expectedResp

        val req = FriendListReq.newBuilder()
            .setCursor(0L)
            .setLimit(20)
            .build()

        // When: 执行查询
        val result = handler.handle(req)

        // Then: 验证好友信息正常返回，但在线状态为 0（隐藏用户不暴露真实状态）
        assertEquals(1, result.friendsCount)
        val friend = result.getFriends(0)
        assertEquals(2001L, friend.uid)
        assertEquals("user2", friend.username)
        // 隐藏用户在线状态始终为 0
        assertEquals(0, friend.status)
    }

    @Test
    fun handleShouldRequireSession() = runTest {
        val exception = kotlin.test.assertFailsWith<com.nebula.common.exception.BizException> {
            val req = com.nebula.chat.friend.FriendListReq.getDefaultInstance()
            handler.handle(req)
        }
        kotlin.test.assertEquals(com.nebula.common.BizCode.UNAUTHORIZED, exception.bizCode, "无 Session 时应抛出 UNAUTHORIZED")
    }

}
