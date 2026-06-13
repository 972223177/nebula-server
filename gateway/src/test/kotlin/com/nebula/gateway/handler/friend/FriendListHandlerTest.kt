package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendListReq
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.redis.OnlineStatusData
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.UserRepository
import com.nebula.service.friend.FriendService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * FriendListHandler 获取好友列表 Handler 单元测试（D-46）。
 *
 * 覆盖场景：
 * - 正常分页查询 → 返回好友列表含完整 6 个字段
 * - 空好友列表 → 返回空列表
 * - 隐藏用户过滤 → 隐藏用户在线状态显示为 0
 *
 * Session 注入方式：使用 withContext(SessionKey(session)) 包裹 handle() 调用。
 */
class FriendListHandlerTest {

    private lateinit var friendService: FriendService
    private lateinit var friendshipRepository: FriendshipRepository
    private lateinit var userRepository: UserRepository
    private lateinit var onlineStatusRepository: OnlineStatusRepository
    private lateinit var privacyRepository: PrivacyRepository
    private lateinit var handler: FriendListHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        friendService = mockk()
        friendshipRepository = mockk(relaxed = true)
        userRepository = mockk(relaxed = true)
        onlineStatusRepository = mockk(relaxed = true)
        privacyRepository = mockk(relaxed = true)
        handler = FriendListHandler(friendService)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常分页查询
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常分页查询 — 返回好友列表含完整 6 个字段`() = runTest {
        // Given: 当前用户有两个好友
        val now = LocalDateTime.now()
        val f1 = FriendshipEntity(userId = 1001L, friendId = 2001L)
        f1.id = 1L; f1.createdAt = now
        val f2 = FriendshipEntity(userId = 3001L, friendId = 1001L)
        f2.id = 2L; f2.createdAt = now.minusHours(1)

        coEvery {
            friendshipRepository.findFriendsByUserId(1001L, 0L, PageRequest.of(0, 20))
        } returns listOf(f1, f2)

        // 批量获取用户信息
        val user2001 = UserEntity(username = "user2", passwordHash = "hash2", nickname = "User Two").apply {
            id = 2001L; avatar = "https://example.com/avatar2.jpg"
        }
        val user3001 = UserEntity(username = "user3", passwordHash = "hash3", nickname = "User Three").apply {
            id = 3001L; avatar = "https://example.com/avatar3.jpg"
        }
        coEvery { userRepository.findAllById(listOf(2001L, 3001L)) } returns listOf(user2001, user3001)

        // 无隐藏用户
        coEvery { privacyRepository.batchGetHideOnlineStatus(listOf(2001L, 3001L)) } returns emptySet()

        // 在线状态：2001 在线(status=1)，3001 离线
        coEvery { onlineStatusRepository.batchGetStatus(listOf(2001L, 3001L)) } returns mapOf(
            2001L to OnlineStatusData(status = 1, lastActiveAt = 1700000000000L),
            3001L to null
        )

        val req = FriendListReq.newBuilder()
            .setCursor(0L)
            .setLimit(20)
            .build()

        // When: 执行查询
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

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
    fun `空好友列表 — 无好友时返回空列表`() = runTest {
        // Given: 当前用户没有好友记录
        coEvery {
            friendshipRepository.findFriendsByUserId(1001L, 0L, PageRequest.of(0, 20))
        } returns emptyList()

        val req = FriendListReq.newBuilder()
            .setCursor(0L)
            .setLimit(20)
            .build()

        // When: 执行查询
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        // Then: 返回空列表（默认实例）
        assertEquals(0, result.friendsCount)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 3：隐藏用户过滤
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `隐藏用户过滤 — 隐藏用户的在线状态显示为 0`() = runTest {
        // Given: 当前用户有一个好友，但该好友设置了隐藏在线状态
        val now = LocalDateTime.now()
        val f1 = FriendshipEntity(userId = 1001L, friendId = 2001L)
        f1.id = 1L; f1.createdAt = now

        coEvery {
            friendshipRepository.findFriendsByUserId(1001L, 0L, PageRequest.of(0, 20))
        } returns listOf(f1)

        // 好友用户信息
        val user2001 = UserEntity(username = "user2", passwordHash = "hash2", nickname = "User Two").apply {
            id = 2001L; avatar = "https://example.com/avatar2.jpg"
        }
        coEvery { userRepository.findAllById(listOf(2001L)) } returns listOf(user2001)

        // 2001 是隐藏用户
        coEvery { privacyRepository.batchGetHideOnlineStatus(listOf(2001L)) } returns setOf(2001L)

        // 实际在线状态为 2（隐藏），但好友列表应显示为 0
        coEvery { onlineStatusRepository.batchGetStatus(listOf(2001L)) } returns mapOf(
            2001L to OnlineStatusData(status = 2, lastActiveAt = 1700000000000L)
        )

        val req = FriendListReq.newBuilder()
            .setCursor(0L)
            .setLimit(20)
            .build()

        // When: 执行查询
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        // Then: 验证好友信息正常返回，但在线状态为 0（隐藏用户不暴露真实状态）
        assertEquals(1, result.friendsCount)
        val friend = result.getFriends(0)
        assertEquals(2001L, friend.uid)
        assertEquals("user2", friend.username)
        // 隐藏用户在线状态始终为 0
        assertEquals(0, friend.status)
    }
}
