package com.nebula.service.friend

import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendBrief
import com.nebula.chat.friend.FriendDeleteReq
import com.nebula.chat.friend.FriendListReq
import com.nebula.chat.friend.FriendListResp
import com.nebula.chat.friend.FriendRejectReq
import com.nebula.chat.friend.FriendRequestItem
import com.nebula.chat.friend.FriendRequestsReq
import com.nebula.chat.friend.FriendRequestsResp
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.redis.OnlineStatusData
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows

/**
 * FriendService 单元测试。
 *
 * 覆盖好友添加、接受、拒绝、删除、列表和申请查询共 6 个方法的所有关键场景，
 * 包括参数校验、业务规则和边界条件。
 *
 * 依赖七大 Repository/Redis 模块，全部通过 MockK mock 注入。
 */
class FriendServiceTest {

    // ═══════════════════════════════════════════════════════════════
    // Mock 依赖
    // ═══════════════════════════════════════════════════════════════

    private lateinit var friendRequestRepository: FriendRequestRepository
    private lateinit var friendshipRepository: FriendshipRepository
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var userRepository: UserRepository
    private lateinit var onlineStatusRepository: OnlineStatusRepository
    private lateinit var privacyRepository: PrivacyRepository
    private lateinit var service: FriendService

    /** 测试用固定 UID */
    private val fromUid = 1001L
    private val toUid = 2002L
    private val larger = maxOf(fromUid, toUid)   // 2002
    private val smaller = minOf(fromUid, toUid)  // 1001
    private val expectedConvId = "private:$smaller:$larger"

    /**
     * 每个测试前创建 mock 实例并构建 FriendService。
     */
    @BeforeEach
    fun setUp() {
        friendRequestRepository = mockk()
        friendshipRepository = mockk()
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        userRepository = mockk()
        onlineStatusRepository = mockk()
        privacyRepository = mockk()
        service = FriendService(
            friendRequestRepository = friendRequestRepository,
            friendshipRepository = friendshipRepository,
            conversationRepository = conversationRepository,
            conversationMemberRepository = conversationMemberRepository,
            userRepository = userRepository,
            onlineStatusRepository = onlineStatusRepository,
            privacyRepository = privacyRepository
        )
    }

    // ═══════════════════════════════════════════════════════════════
    // 测试数据工厂（遵循 TestHelper.kt 风格）
    // ═══════════════════════════════════════════════════════════════

    /** 创建测试 UserEntity。 */
    private fun testUser(userId: Long, username: String = "user$userId", nickname: String = "用户$userId"): UserEntity {
        return UserEntity(
            username = username,
            passwordHash = "hash",
            nickname = nickname
        ).apply { id = userId }
    }

    /** 创建测试 FriendRequestEntity。 */
    private fun testFriendRequest(
        fromUid: Long,
        toUid: Long,
        status: Int = 0,
        message: String = "",
        id: Long? = null
    ): FriendRequestEntity {
        return FriendRequestEntity(
            fromUid = fromUid,
            toUid = toUid,
            status = status,
            message = message
        ).apply {
            this.id = id
            createdAt = java.time.LocalDateTime.now()
            updatedAt = java.time.LocalDateTime.now()
        }
    }

    /** 创建测试 FriendshipEntity。 */
    private fun testFriendship(userId: Long, friendId: Long, deleted: Int = 0): FriendshipEntity {
        return FriendshipEntity(userId = userId, friendId = friendId).apply { this.deleted = deleted }
    }

    /** 创建测试 ConversationEntity（私聊类型）。 */
    private fun testPrivateConv(convId: String): ConversationEntity {
        return ConversationEntity(type = 0, name = "").apply { id = convId }
    }

    /** 创建测试 ConversationMemberEntity。 */
    private fun testMember(convId: String, userId: Long): ConversationMemberEntity {
        return ConversationMemberEntity(conversationId = convId, userId = userId)
    }

    // ═══════════════════════════════════════════════════════════════
    // — addFriend — 发送好友申请
    // ═══════════════════════════════════════════════════════════════

    /**
     * 向自己发送好友申请，应抛出 SELF_FRIEND 异常。
     */
    @Test
    fun shouldThrowSelfFriendWhenFromUidEqualsToUid() = runTest {
        val req = FriendAddReq.newBuilder().setToUid(fromUid).build()

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.addFriend(req, fromUid) } }

        assertEquals(BizCode.SELF_FRIEND, ex.bizCode)
    }

    /**
     * 已是好友时发送申请，应抛出 ALREADY_FRIEND 异常。
     */
    @Test
    fun shouldThrowAlreadyFriendWhenAlreadyFriends() = runTest {
        val req = FriendAddReq.newBuilder().setToUid(toUid).build()
        val existingFriendship = testFriendship(smaller, larger, deleted = 0)
        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns existingFriendship

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.addFriend(req, fromUid) } }

        assertEquals(BizCode.ALREADY_FRIEND, ex.bizCode)
    }

    /**
     * 对方已发送 pending 申请时触发双向竞赛（mutual accept），
     * 应自动创建好友关系 + 私聊会话 + 双方成员。
     */
    @Test
    fun shouldMutualAcceptWhenReverseRequestExists() = runTest {
        val req = FriendAddReq.newBuilder().setToUid(toUid).setMessage("hello").build()
        val reverseRequest = testFriendRequest(fromUid = toUid, toUid = fromUid, status = 0, id = 99L)

        // 检查好友关系 → null（尚无关系）
        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns null
        // 反向申请存在 → 触发双向竞赛
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(toUid, fromUid, 0) } returns reverseRequest
        // save 返回传入实体
        every { friendRequestRepository.saveAndFlush(any<FriendRequestEntity>()) } answers { firstArg() }
        every { friendshipRepository.saveAndFlush(any<FriendshipEntity>()) } answers { firstArg() }
        // 私聊会话不存在 → 需要创建
        every { conversationRepository.findById(expectedConvId) } returns Optional.empty()
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        // 成员不存在 → 需要创建
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, smaller) } returns null
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, larger) } returns null
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val result = service.addFriend(req, fromUid)

        assertTrue(result.isMutualAccept)
        assertEquals(99L, result.requestId)
        assertEquals(expectedConvId, result.convId)
        assertEquals(fromUid, result.fromUid)
        assertEquals(toUid, result.toUid)
        // 对方申请状态已更新为 accepted
        assertEquals(1, reverseRequest.status)
        // 验证会话被创建
        verify { conversationRepository.save(any<ConversationEntity>()) }
        verify(exactly = 2) { conversationMemberRepository.save(any<ConversationMemberEntity>()) }
    }

    /**
     * 双向竞赛时已有好友关系（deleted=1），应恢复为 deleted=0。
     */
    @Test
    fun shouldRestoreDeletedFriendshipOnMutualAccept() = runTest {
        val req = FriendAddReq.newBuilder().setToUid(toUid).build()
        val reverseRequest = testFriendRequest(fromUid = toUid, toUid = fromUid, status = 0, id = 99L)
        val existingFriendship = testFriendship(smaller, larger, deleted = 1)

        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns existingFriendship
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(toUid, fromUid, 0) } returns reverseRequest
        every { friendRequestRepository.saveAndFlush(any<FriendRequestEntity>()) } answers { firstArg() }
        every { friendshipRepository.saveAndFlush(any<FriendshipEntity>()) } answers { firstArg() }
        every { conversationRepository.findById(expectedConvId) } returns Optional.empty()
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, smaller) } returns null
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, larger) } returns null
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        service.addFriend(req, fromUid)

        assertEquals(0, existingFriendship.deleted)
    }

    /**
     * 双向竞赛时私聊会话已存在，不应重复创建会话。
     */
    @Test
    fun shouldReuseExistingConversationOnMutualAccept() = runTest {
        val req = FriendAddReq.newBuilder().setToUid(toUid).build()
        val reverseRequest = testFriendRequest(fromUid = toUid, toUid = fromUid, status = 0, id = 99L)
        val existingConv = testPrivateConv(expectedConvId)

        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns null
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(toUid, fromUid, 0) } returns reverseRequest
        every { friendRequestRepository.saveAndFlush(any<FriendRequestEntity>()) } answers { firstArg() }
        every { friendshipRepository.saveAndFlush(any<FriendshipEntity>()) } answers { firstArg() }
        every { conversationRepository.findById(expectedConvId) } returns Optional.of(existingConv)
        // 成员已存在 → 跳过创建
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, smaller) } returns testMember(expectedConvId, smaller)
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, larger) } returns testMember(expectedConvId, larger)

        service.addFriend(req, fromUid)

        // 会话不应被重复创建
        verify(exactly = 0) { conversationRepository.save(any<ConversationEntity>()) }
        verify(exactly = 0) { conversationMemberRepository.save(any<ConversationMemberEntity>()) }
    }

    /**
     * 已存在待处理的同向申请，应抛出 REQUEST_HANDLED 异常。
     */
    @Test
    fun shouldThrowRequestHandledWhenDuplicatePendingRequestExists() = runTest {
        val req = FriendAddReq.newBuilder().setToUid(toUid).build()
        val existingRequest = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0)

        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns null
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(toUid, fromUid, 0) } returns null
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(fromUid, toUid, 0) } returns existingRequest

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.addFriend(req, fromUid) } }

        assertEquals(BizCode.REQUEST_HANDLED, ex.bizCode)
    }

    /**
     * 正常场景：创建好友申请并返回 FriendAddResult。
     */
    @Test
    fun shouldCreateFriendRequestAndReturnResult() = runTest {
        val req = FriendAddReq.newBuilder().setToUid(toUid).setMessage("hello").build()
        val savedRequest = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 100L, message = "hello")

        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns null
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(toUid, fromUid, 0) } returns null
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(fromUid, toUid, 0) } returns null
        every { friendRequestRepository.save(any<FriendRequestEntity>()) } returns savedRequest

        val result = service.addFriend(req, fromUid)

        assertFalse(result.isMutualAccept)
        assertEquals(100L, result.requestId)
        assertNull(result.convId)
        assertEquals(fromUid, result.fromUid)
        assertEquals(toUid, result.toUid)
    }

    /**
     * T03/D-80: 双向竞赛应只创建一对好友关系。
     *
     * A 向 B 发送好友申请，B 已向 A 发送 pending 申请 → A 走 mutualAccept 创建好友关系。
     * B 随后执行时发现已是好友 → 抛出 ALREADY_FRIEND，不会重复创建。
     * 验证好友关系 saveAndFlush 仅调用一次。
     */
    @Test
    fun shouldCreateOnlyOneFriendshipPairOnConcurrentMutualAdd() = runTest {
        val reqA = FriendAddReq.newBuilder().setToUid(toUid).setMessage("hello from A").build()
        val reqB = FriendAddReq.newBuilder().setToUid(fromUid).setMessage("hello from B").build()
        val reverseRequest = testFriendRequest(fromUid = toUid, toUid = fromUid, status = 0, id = 99L)
        val existingFriendship = testFriendship(smaller, larger, deleted = 0)

        // A: B 已发送 pending 申请 → mutualAccept 路径
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(toUid, fromUid, 0) } returns reverseRequest
        // B: 不会找到 A 的申请（A 是主叫方）
        every { friendRequestRepository.findByFromUidAndToUidAndStatus(fromUid, toUid, 0) } returns null
        // 好友关系检查：第一次 null（A 的检查），第二次返回现有关系（B 的检查）
        var friendCheckCount = 0
        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } answers {
            if (friendCheckCount++ == 0) null else existingFriendship
        }
        every { friendRequestRepository.saveAndFlush(any<FriendRequestEntity>()) } answers { firstArg() }
        every { friendRequestRepository.save(any<FriendRequestEntity>()) } answers { firstArg() }
        every { friendshipRepository.saveAndFlush(any<FriendshipEntity>()) } answers { firstArg() }
        every { conversationRepository.findById(any()) } returns Optional.empty()
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.findByConversationIdAndUserId(any(), any()) } returns null
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        // 先执行 A → mutualAccept 创建好友关系
        val resultA = service.addFriend(reqA, fromUid)
        assertTrue(resultA.isMutualAccept)
        assertEquals(expectedConvId, resultA.convId)

        // 再执行 B → 已是好友，抛出 ALREADY_FRIEND
        val ex = assertThrows(FriendException::class.java) {
            runBlocking { service.addFriend(reqB, toUid) }
        }
        assertEquals(BizCode.ALREADY_FRIEND, ex.bizCode)

        // 好友关系仅创建一次
        verify(exactly = 1) { friendshipRepository.saveAndFlush(any<FriendshipEntity>()) }
    }

    // ═══════════════════════════════════════════════════════════════
    // — acceptFriendRequest —

    /**
     * 申请的 requestId 不存在，应抛出 REQUEST_NOT_FOUND 异常。
     */
    @Test
    fun acceptShouldThrowRequestNotFoundWhenRequestMissing() = runTest {
        val req = FriendAcceptReq.newBuilder().setRequestId(999L).build()
        every { friendRequestRepository.findById(999L) } returns Optional.empty()

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.acceptFriendRequest(req, toUid) } }

        assertEquals(BizCode.REQUEST_NOT_FOUND, ex.bizCode)
    }

    /**
     * 申请状态不是 pending（status != 0），应抛出 REQUEST_HANDLED 异常。
     */
    @Test
    fun acceptShouldThrowRequestHandledWhenRequestStatusIsNotPending() = runTest {
        val request = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 1, id = 1L)
        val req = FriendAcceptReq.newBuilder().setRequestId(1L).build()
        every { friendRequestRepository.findById(1L) } returns Optional.of(request)

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.acceptFriendRequest(req, toUid) } }

        assertEquals(BizCode.REQUEST_HANDLED, ex.bizCode)
    }

    /**
     * 当前用户不是申请的目标用户（toUid != userId），应抛出 FORBIDDEN 异常。
     */
    @Test
    fun acceptShouldThrowForbiddenWhenUserIdIsNotTheTarget() = runTest {
        val request = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 1L)
        val req = FriendAcceptReq.newBuilder().setRequestId(1L).build()
        val wrongUserId = 3003L
        every { friendRequestRepository.findById(1L) } returns Optional.of(request)

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.acceptFriendRequest(req, wrongUserId) } }

        assertEquals(BizCode.FORBIDDEN, ex.bizCode)
    }

    /**
     * 正常接受好友申请：更新状态、创建好友关系、创建私聊会话和双方成员。
     */
    @Test
    fun shouldAcceptFriendRequestSuccessfully() = runTest {
        val request = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 1L)
        val req = FriendAcceptReq.newBuilder().setRequestId(1L).build()

        every { friendRequestRepository.findById(1L) } returns Optional.of(request)
        every { friendRequestRepository.save(any<FriendRequestEntity>()) } answers { firstArg() }
        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns null
        every { friendshipRepository.save(any<FriendshipEntity>()) } answers { firstArg() }
        every { conversationRepository.findById(expectedConvId) } returns Optional.empty()
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, smaller) } returns null
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, larger) } returns null
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val result = service.acceptFriendRequest(req, toUid)

        // 验证申请状态已更新
        assertEquals(1, request.status)
        // 验证返回结果
        assertEquals(fromUid, result.fromUid)
        assertEquals(toUid, result.toUid)
        assertEquals(expectedConvId, result.convId)
    }

    /**
     * 接受申请时好友关系已存在（deleted=1），应恢复为 deleted=0。
     */
    @Test
    fun acceptShouldRestoreDeletedFriendship() = runTest {
        val request = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 1L)
        val existingFriendship = testFriendship(smaller, larger, deleted = 1)
        val req = FriendAcceptReq.newBuilder().setRequestId(1L).build()

        every { friendRequestRepository.findById(1L) } returns Optional.of(request)
        every { friendRequestRepository.save(any<FriendRequestEntity>()) } answers { firstArg() }
        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns existingFriendship
        every { friendshipRepository.save(any<FriendshipEntity>()) } answers { firstArg() }
        every { conversationRepository.findById(expectedConvId) } returns Optional.empty()
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, smaller) } returns null
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, larger) } returns null
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        service.acceptFriendRequest(req, toUid)

        assertEquals(0, existingFriendship.deleted)
    }

    /**
     * 接受申请时私聊会话已存在，不应重复创建。
     */
    @Test
    fun acceptShouldReuseExistingConversation() = runTest {
        val request = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 1L)
        val existingConv = testPrivateConv(expectedConvId)
        val req = FriendAcceptReq.newBuilder().setRequestId(1L).build()

        every { friendRequestRepository.findById(1L) } returns Optional.of(request)
        every { friendRequestRepository.save(any<FriendRequestEntity>()) } answers { firstArg() }
        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns null
        every { friendshipRepository.save(any<FriendshipEntity>()) } answers { firstArg() }
        every { conversationRepository.findById(expectedConvId) } returns Optional.of(existingConv)
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, smaller) } returns testMember(expectedConvId, smaller)
        every { conversationMemberRepository.findByConversationIdAndUserId(expectedConvId, larger) } returns testMember(expectedConvId, larger)

        service.acceptFriendRequest(req, toUid)

        verify(exactly = 0) { conversationRepository.save(any<ConversationEntity>()) }
        verify(exactly = 0) { conversationMemberRepository.save(any<ConversationMemberEntity>()) }
    }

    // ═══════════════════════════════════════════════════════════════
    // — rejectFriendRequest — 拒绝好友申请
    // ═══════════════════════════════════════════════════════════════

    /**
     * 拒绝不存在的申请，应抛出 REQUEST_NOT_FOUND 异常。
     */
    @Test
    fun rejectShouldThrowRequestNotFoundWhenRequestMissing() = runTest {
        val req = FriendRejectReq.newBuilder().setRequestId(999L).build()
        every { friendRequestRepository.findById(999L) } returns Optional.empty()

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.rejectFriendRequest(req, toUid) } }

        assertEquals(BizCode.REQUEST_NOT_FOUND, ex.bizCode)
    }

    /**
     * 拒绝已处理的申请（status != 0），应抛出 REQUEST_HANDLED 异常。
     */
    @Test
    fun rejectShouldThrowRequestHandledWhenRequestStatusIsNotPending() = runTest {
        val request = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 1, id = 1L)
        val req = FriendRejectReq.newBuilder().setRequestId(1L).build()
        every { friendRequestRepository.findById(1L) } returns Optional.of(request)

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.rejectFriendRequest(req, toUid) } }

        assertEquals(BizCode.REQUEST_HANDLED, ex.bizCode)
    }

    /**
     * 当前用户不是申请的目标用户，应抛出 FORBIDDEN 异常。
     */
    @Test
    fun rejectShouldThrowForbiddenWhenUserIdIsNotTheTarget() = runTest {
        val request = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 1L)
        val req = FriendRejectReq.newBuilder().setRequestId(1L).build()
        val wrongUserId = 3003L
        every { friendRequestRepository.findById(1L) } returns Optional.of(request)

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.rejectFriendRequest(req, wrongUserId) } }

        assertEquals(BizCode.FORBIDDEN, ex.bizCode)
    }

    /**
     * 正常拒绝好友申请：设置 status=2 并保存。
     */
    @Test
    fun shouldRejectFriendRequestSuccessfully() = runTest {
        val request = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 1L)
        val req = FriendRejectReq.newBuilder().setRequestId(1L).build()

        every { friendRequestRepository.findById(1L) } returns Optional.of(request)
        every { friendRequestRepository.save(any<FriendRequestEntity>()) } answers { firstArg() }

        service.rejectFriendRequest(req, toUid)

        // 申请状态应被标记为拒绝
        assertEquals(2, request.status)
        verify { friendRequestRepository.save(request) }
    }

    // ═══════════════════════════════════════════════════════════════
    // — deleteFriend — 删除好友（软删除）
    // ═══════════════════════════════════════════════════════════════

    /**
     * 删除不存在的好友记录，应抛出 FRIEND_NOT_FOUND 异常。
     */
    @Test
    fun deleteShouldThrowFriendNotFoundWhenFriendshipIsNull() = runTest {
        val req = FriendDeleteReq.newBuilder().setUid(toUid).build()
        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns null

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.deleteFriend(req, fromUid) } }

        assertEquals(BizCode.FRIEND_NOT_FOUND, ex.bizCode)
    }

    /**
     * 删除已软删除的好友记录，应抛出 FRIEND_NOT_FOUND 异常。
     */
    @Test
    fun deleteShouldThrowFriendNotFoundWhenFriendshipAlreadyDeleted() = runTest {
        val friendship = testFriendship(smaller, larger, deleted = 1)
        val req = FriendDeleteReq.newBuilder().setUid(toUid).build()

        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns friendship

        val ex = assertThrows(FriendException::class.java) { runBlocking { service.deleteFriend(req, fromUid) } }

        assertEquals(BizCode.FRIEND_NOT_FOUND, ex.bizCode)
    }

    /**
     * 正常删除好友：设置 deleted=1 并保存。
     */
    @Test
    fun shouldDeleteFriendSuccessfully() = runTest {
        val friendship = testFriendship(smaller, larger, deleted = 0)
        val req = FriendDeleteReq.newBuilder().setUid(toUid).build()

        every { friendshipRepository.findByUserIdAndFriendId(smaller, larger) } returns friendship
        every { friendshipRepository.save(any<FriendshipEntity>()) } answers { firstArg() }

        service.deleteFriend(req, fromUid)

        assertEquals(1, friendship.deleted)
        verify { friendshipRepository.save(friendship) }
    }

    // ═══════════════════════════════════════════════════════════════
    // — listFriends — 查询好友列表
    // ═══════════════════════════════════════════════════════════════

    /**
     * 好友列表为空时，应返回空的 FriendListResp。
     */
    @Test
    fun listFriendsShouldReturnEmptyResponseWhenNoFriends() = runTest {
        val req = FriendListReq.newBuilder().setCursor(0L).setLimit(10).build()
        every { friendshipRepository.findFriendsByUserId(fromUid, 0L, PageRequest.of(0, 11)) } returns emptyList()

        val result = service.listFriends(req, fromUid)

        assertEquals(0, result.friendsCount)
    }

    /**
     * 正常查询好友列表：返回好友信息、在线状态、隐私过滤。
     */
    @Test
    fun listFriendsShouldReturnFriendsWithUserInfoAndOnlineStatus() = runTest {
        val req = FriendListReq.newBuilder().setCursor(0L).setLimit(10).build()
        val friendUid = 2002L
        val friendship = testFriendship(fromUid, friendUid, deleted = 0).apply { id = 1L }
        val friendUser = testUser(friendUid)

        // 查询好友列表返回一条记录
        every { friendshipRepository.findFriendsByUserId(fromUid, 0L, PageRequest.of(0, 11)) } returns listOf(friendship)
        // 批量查询用户信息
        every { userRepository.findAllById(listOf(friendUid)) } returns listOf(friendUser)
        // 在线状态：用户在线
        coEvery { onlineStatusRepository.batchGetStatus(listOf(friendUid)) } returns mapOf(friendUid to OnlineStatusData(1, 1234567890))
        // 隐私设置：未隐藏
        coEvery { privacyRepository.batchGetHideOnlineStatus(listOf(friendUid)) } returns emptySet()

        val result = service.listFriends(req, fromUid)

        assertEquals(1, result.friendsCount)
        val brief = result.getFriends(0)
        assertEquals(friendUid, brief.uid)
        assertEquals("user$friendUid", brief.username)
        assertEquals("用户$friendUid", brief.displayName)
        assertEquals("", brief.avatarUrl)
        // 在线且未隐藏 → status=1
        assertEquals(1, brief.status)
    }

    /**
     * 游标分页：超过 limit 时 hasMore=true 且丢弃最后一条。
     */
    @Test
    fun listFriendsShouldHandleCursorPaginationWithHasMore() = runTest {
        val req = FriendListReq.newBuilder().setCursor(0L).setLimit(2).build()
        val friendUid1 = 2002L
        val friendUid2 = 3003L
        val friendUid3 = 4004L
        // 返回 limit+1=3 条，暗示还有更多
        val friendships = listOf(
            testFriendship(fromUid, friendUid1, deleted = 0).apply { id = 3L },
            testFriendship(fromUid, friendUid2, deleted = 0).apply { id = 2L },
            testFriendship(fromUid, friendUid3, deleted = 0).apply { id = 1L }
        )

        every { friendshipRepository.findFriendsByUserId(fromUid, 0L, PageRequest.of(0, 3)) } returns friendships
        every { userRepository.findAllById(listOf(friendUid1, friendUid2)) } returns listOf(
            testUser(friendUid1), testUser(friendUid2)
        )
        coEvery { onlineStatusRepository.batchGetStatus(listOf(friendUid1, friendUid2)) } returns emptyMap()
        coEvery { privacyRepository.batchGetHideOnlineStatus(listOf(friendUid1, friendUid2)) } returns emptySet()

        val result = service.listFriends(req, fromUid)

        // 应返回前 2 条，最后一条被丢弃
        assertEquals(2, result.friendsCount)
        assertEquals(friendUid1, result.getFriends(0).uid)
        assertEquals(friendUid2, result.getFriends(1).uid)
    }

    /**
     * 隐藏在线状态的用户，在线状态应被过滤为 status=0。
     */
    @Test
    fun listFriendsShouldFilterHiddenUserOnlineStatus() = runTest {
        val req = FriendListReq.newBuilder().setCursor(0L).setLimit(10).build()
        val uidOnline = 2002L
        val uidHidden = 3003L
        val friendships = listOf(
            testFriendship(fromUid, uidOnline, deleted = 0).apply { id = 2L },
            testFriendship(fromUid, uidHidden, deleted = 0).apply { id = 1L }
        )

        every { friendshipRepository.findFriendsByUserId(fromUid, 0L, PageRequest.of(0, 11)) } returns friendships
        every { userRepository.findAllById(listOf(uidOnline, uidHidden)) } returns listOf(
            testUser(uidOnline), testUser(uidHidden)
        )
        // 两个用户都在线
        coEvery { onlineStatusRepository.batchGetStatus(listOf(uidOnline, uidHidden)) } returns mapOf(
            uidOnline to OnlineStatusData(1, 1000),
            uidHidden to OnlineStatusData(1, 2000)
        )
        // uidHidden 设置了隐藏在线状态
        coEvery { privacyRepository.batchGetHideOnlineStatus(listOf(uidOnline, uidHidden)) } returns setOf(uidHidden)

        val result = service.listFriends(req, fromUid)

        assertEquals(2, result.friendsCount)
        // 未隐藏用户 → status=1
        assertEquals(1, result.getFriends(0).status)
        // 隐藏用户 → status=0
        assertEquals(0, result.getFriends(1).status)
    }

    // ═══════════════════════════════════════════════════════════════
    // — getFriendRequests — 查询好友申请列表
    // ═══════════════════════════════════════════════════════════════

    /**
     * 没有待处理的申请时，应返回空的 FriendRequestsResp。
     */
    @Test
    fun getFriendRequestsShouldReturnEmptyResponseWhenNoRequests() = runTest {
        val req = FriendRequestsReq.getDefaultInstance()
        every { friendRequestRepository.findByToUidAndStatus(toUid, 0) } returns emptyList()

        val result = service.getFriendRequests(req, toUid)

        assertEquals(0, result.requestsCount)
    }

    /**
     * 正常返回好友申请列表，并正确填充发起方用户信息。
     */
    @Test
    fun getFriendRequestsShouldReturnRequestsWithUserInfo() = runTest {
        val req = FriendRequestsReq.getDefaultInstance()
        val requestEntity = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 10L, message = "hello")
        val fromUser = testUser(fromUid)

        every { friendRequestRepository.findByToUidAndStatus(toUid, 0) } returns listOf(requestEntity)
        every { userRepository.findAllById(any()) } returns listOf(fromUser)

        val result = service.getFriendRequests(req, toUid)

        assertEquals(1, result.requestsCount)
        val item = result.getRequests(0)
        assertEquals(10L, item.requestId)
        assertEquals(fromUid, item.fromUid)
        assertEquals("user$fromUid", item.fromUsername)
        assertEquals("", item.fromAvatar)
        assertEquals("hello", item.message)
        assertEquals("0", item.status)
        assertTrue(item.createdAt > 0)
    }

    /**
     * 多个申请时，正确映射每个申请对应的发起方用户信息。
     */
    @Test
    fun getFriendRequestsShouldHandleMultipleRequestsWithDistinctUsers() = runTest {
        val req = FriendRequestsReq.getDefaultInstance()
        val request1 = testFriendRequest(fromUid = 2002L, toUid = toUid, status = 0, id = 1L, message = "hi")
        val request2 = testFriendRequest(fromUid = 3003L, toUid = toUid, status = 0, id = 2L, message = "hello")
        val user1 = testUser(2002L)
        val user2 = testUser(3003L)

        every { friendRequestRepository.findByToUidAndStatus(toUid, 0) } returns listOf(request1, request2)
        every { userRepository.findAllById(any()) } returns listOf(user1, user2)

        val result = service.getFriendRequests(req, toUid)

        assertEquals(2, result.requestsCount)
        assertEquals(1L, result.getRequests(0).requestId)
        assertEquals(2002L, result.getRequests(0).fromUid)
        assertEquals("user2002", result.getRequests(0).fromUsername)
        assertEquals(2L, result.getRequests(1).requestId)
        assertEquals(3003L, result.getRequests(1).fromUid)
        assertEquals("user3003", result.getRequests(1).fromUsername)
    }

    /**
     * 发起方用户不存在时，fromUsername 和 fromAvatar 应返回空字符串。
     */
    @Test
    fun getFriendRequestsShouldHandleMissingFromUserGracefully() = runTest {
        val req = FriendRequestsReq.getDefaultInstance()
        val requestEntity = testFriendRequest(fromUid = fromUid, toUid = toUid, status = 0, id = 10L, message = "hello")

        every { friendRequestRepository.findByToUidAndStatus(toUid, 0) } returns listOf(requestEntity)
        // 用户不存在，findAllById 返回空列表
        every { userRepository.findAllById(listOf(fromUid)) } returns emptyList()

        val result = service.getFriendRequests(req, toUid)

        assertEquals(1, result.requestsCount)
        val item = result.getRequests(0)
        assertEquals("", item.fromUsername)
        assertEquals("", item.fromAvatar)
    }
}
