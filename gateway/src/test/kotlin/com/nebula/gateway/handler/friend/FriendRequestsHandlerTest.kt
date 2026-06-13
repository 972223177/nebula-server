package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendRequestsReq
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.UserRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FriendRequestsHandler 获取待处理好友申请列表 Handler 单元测试（D-41）。
 *
 * 覆盖场景：
 * - 正常查询待处理申请 → 返回 FriendRequestsResp 含完整字段
 * - 空列表 → 返回空 FriendRequestsResp
 *
 * Session 注入方式：使用 withContext(SessionKey(session)) 包裹 handle() 调用。
 */
class FriendRequestsHandlerTest {

    private lateinit var friendRequestRepository: FriendRequestRepository
    private lateinit var userRepository: UserRepository
    private lateinit var handler: FriendRequestsHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        friendRequestRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        handler = FriendRequestsHandler(friendRequestRepository, userRepository)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常查询待处理申请
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常查询待处理申请 — 返回含完整字段的好友申请列表`() = runTest {
        // Given: 当前用户有两条 pending 的好友申请
        val now = LocalDateTime.now()
        val request1 = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0, message = "你好，加个好友")
        request1.id = 10L
        request1.createdAt = now

        val request2 = FriendRequestEntity(fromUid = 3001L, toUid = 1001L, status = 0, message = "好久不见")
        request2.id = 11L
        request2.createdAt = now.minusHours(1)

        coEvery {
            friendRequestRepository.findByToUidAndStatusOrderByCreatedAtDesc(1001L, 0)
        } returns listOf(request1, request2)

        // 批量获取申请人信息
        val user2001 = UserEntity(username = "user2", passwordHash = "hash2", nickname = "User Two").apply {
            id = 2001L; avatar = "https://example.com/avatar2.jpg"
        }
        val user3001 = UserEntity(username = "user3", passwordHash = "hash3", nickname = "User Three").apply {
            id = 3001L; avatar = "https://example.com/avatar3.jpg"
        }
        coEvery { userRepository.findAllById(listOf(2001L, 3001L)) } returns listOf(user2001, user3001)

        val req = FriendRequestsReq.getDefaultInstance()

        // When: 执行查询
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

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
    fun `空列表 — 无待处理申请时返回空 FriendRequestsResp`() = runTest {
        // Given: 当前用户没有 pending 的好友申请
        coEvery {
            friendRequestRepository.findByToUidAndStatusOrderByCreatedAtDesc(1001L, 0)
        } returns emptyList()

        val req = FriendRequestsReq.getDefaultInstance()

        // When: 执行查询
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        // Then: 返回空列表
        assertEquals(0, result.requestsCount)
    }
}
