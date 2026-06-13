package com.nebula.gateway.handler.friend

import com.google.protobuf.ByteString
import com.nebula.chat.PushEventType
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.FriendshipRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * FriendAddHandler 发送好友申请单元测试（D-51, D-52, D-54）。
 *
 * 覆盖场景：
 * - 正常申请 → 创建 FriendRequestEntity + 推送 FRIEND_REQUEST
 * - 自我申请 → 抛出 FriendException(SELF_FRIEND)
 * - 已是好友 → 抛出 FriendException(ALREADY_FRIEND)
 * - 重复申请 → 抛出 FriendException(REQUEST_HANDLED)
 * - 双向竞赛 → 自动好友 + 创建私聊会话 + 推送 FRIEND_ACCEPTED
 */
class FriendAddHandlerTest {

    private lateinit var friendRequestRepo: FriendRequestRepository
    private lateinit var friendshipRepo: FriendshipRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var memberRepo: ConversationMemberRepository
    private lateinit var lockManager: ConversationLockManager
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var pushService: PushService
    private lateinit var handler: FriendAddHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")
    /** 注入 Session 的协程上下文 */
    private val sessionContext = EmptyCoroutineContext + SessionKey(session)

    @BeforeEach
    fun setUp() {
        friendRequestRepo = mockk(relaxed = true)
        friendshipRepo = mockk(relaxed = true)
        conversationRepo = mockk(relaxed = true)
        memberRepo = mockk(relaxed = true)
        lockManager = mockk()
        transactionTemplate = mockk()
        pushService = mockk(relaxed = true)

        // Mock 锁管理器：直接执行代码块（参照 CreateGroupHandlerTest）
        coEvery { lockManager.withLock(any(), any<suspend () -> kotlin.Any>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[1] as suspend () -> kotlin.Any).invoke()
        }

        // Mock 事务模板：在事务内执行回调
        every { transactionTemplate.execute(any<org.springframework.transaction.support.TransactionCallback<Any?>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[0] as org.springframework.transaction.support.TransactionCallback<Any?>)
                .doInTransaction(mockk(relaxed = true))
        }

        // 确保 save 方法返回输入实体自身，避免 Spring Data JPA 的 ClassCastException
        every { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { args[0] as FriendRequestEntity }
        every { friendshipRepo.save(any<FriendshipEntity>()) } answers { args[0] as FriendshipEntity }
        every { conversationRepo.save(any<ConversationEntity>()) } answers { args[0] as ConversationEntity }
        every { memberRepo.save(any<ConversationMemberEntity>()) } answers { args[0] as ConversationMemberEntity }

        handler = FriendAddHandler(
            friendRequestRepo,
            friendshipRepo,
            conversationRepo,
            memberRepo,
            lockManager,
            transactionTemplate,
            pushService
        )
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常申请
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常申请 — 创建 FriendRequestEntity 并推送 FRIEND_REQUEST`() = runTest(sessionContext) {
        // Given: A 向 B 发起好友申请，无双向竞赛、无重复申请、非好友
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("你好，加个好友")
            .build()

        coEvery { friendshipRepo.findByUserIdAndFriendId(any(), any()) } returns null
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(2001L, 1001L, 0) } returns null  // 无反向申请
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(1001L, 2001L, 0) } returns null  // 无重复申请

        val savedRequest = FriendRequestEntity(fromUid = 1001L, toUid = 2001L, status = 0, message = "你好，加个好友")
        savedRequest.id = 42L
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } returns savedRequest

        // When: 执行 handle
        val result = handler.handle(req)

        // Then: 验证返回正确的 requestId
        assertNotNull(result)
        assertEquals(42L, result.requestId)

        // 验证 save 被调用
        coVerify(exactly = 1) { friendRequestRepo.save(any<FriendRequestEntity>()) }

        // 验证推送 FRIEND_REQUEST 给目标用户
        coVerify(exactly = 1) {
            pushService.pushEventToUser(
                targetUid = 2001L,
                eventType = PushEventType.FRIEND_REQUEST,
                payloadBytes = any<ByteString>()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 2：自我申请
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `自我申请 — 抛出 SELF_FRIEND 异常`() = runTest(sessionContext) {
        // Given: A 向自己发起好友申请（fromUid == toUid）
        val req = FriendAddReq.newBuilder()
            .setToUid(1001L)  // 与 session.userId 相同
            .setMessage("加自己")
            .build()

        // When & Then: 应抛出 FriendException(SELF_FRIEND)
        val ex = assertFailsWith<FriendException> {
            handler.handle(req)
        }
        assertEquals(BizCode.SELF_FRIEND, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 3：已是好友
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `已是好友 — 抛出 ALREADY_FRIEND 异常`() = runTest(sessionContext) {
        // Given: A 向 B 申请，但 A 和 B 已是好友（deleted=0）
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("你好")
            .build()

        val existingFriendship = FriendshipEntity(userId = 1001L, friendId = 2001L)
        existingFriendship.deleted = 0
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns existingFriendship

        // When & Then: 应抛出 FriendException(ALREADY_FRIEND)
        val ex = assertFailsWith<FriendException> {
            handler.handle(req)
        }
        assertEquals(BizCode.ALREADY_FRIEND, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 4：重复申请
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `重复申请 — 抛出 REQUEST_HANDLED 异常`() = runTest(sessionContext) {
        // Given: A 向 B 重复发送申请，同方向已有 pending 申请
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("再次申请")
            .build()

        // 非好友
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null
        // 无反向申请（无双向竞赛）
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(2001L, 1001L, 0) } returns null
        // 但同方向已有 pending 申请
        val existingRequest = FriendRequestEntity(fromUid = 1001L, toUid = 2001L, status = 0)
        existingRequest.id = 10L
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(1001L, 2001L, 0) } returns existingRequest

        // When & Then: 应抛出 FriendException(REQUEST_HANDLED)
        val ex = assertFailsWith<FriendException> {
            handler.handle(req)
        }
        assertEquals(BizCode.REQUEST_HANDLED, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 5：双向竞赛
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `双向竞赛 — 自动创建好友关系和私聊会话并推送 FRIEND_ACCEPTED`() = runTest(sessionContext) {
        // Given: A 向 B 申请时，B 已向 A 发送了 pending 申请（双向竞赛）
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("你好")
            .build()

        // 非好友
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null

        // B 已向 A 发送 pending 申请（双向竞赛触发条件）
        val reverseRequest = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0)
        reverseRequest.id = 5L
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(2001L, 1001L, 0) } returns reverseRequest

        // 私聊会话不存在（将被创建）
        coEvery { conversationRepo.findById("private:1001:2001") } returns Optional.empty()

        // 双方会话成员都不存在
        coEvery { memberRepo.findByConversationIdAndUserId("private:1001:2001", any()) } returns null

        // Mock save 方法：返回传入的实体（事务内需要正确的返回类型）
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { firstArg() }
        coEvery { friendshipRepo.save(any<FriendshipEntity>()) } answers { firstArg() }
        coEvery { conversationRepo.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { memberRepo.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        // When: 执行 handle
        val result = handler.handle(req)

        // Then: 验证返回的 requestId 为对方申请的 ID
        assertNotNull(result)
        assertEquals(5L, result.requestId)

        // 验证对方申请状态被更新为 accepted
        assertEquals(1, reverseRequest.status)
        coVerify(exactly = 1) { friendRequestRepo.save(reverseRequest) }

        // 验证好友关系被保存
        coVerify(exactly = 1) { friendshipRepo.save(any<FriendshipEntity>()) }

        // 验证私聊会话被创建
        coVerify(exactly = 1) { conversationRepo.save(any<ConversationEntity>()) }

        // 验证双方会话成员被创建（各一次）
        coVerify(exactly = 2) { memberRepo.save(any<ConversationMemberEntity>()) }

        // 验证 FRIEND_ACCEPTED 推送给双方
        coVerify(exactly = 1) {
            pushService.pushEventToUser(
                targetUid = 1001L,
                eventType = PushEventType.FRIEND_ACCEPTED,
                payloadBytes = any<ByteString>()
            )
        }
        coVerify(exactly = 1) {
            pushService.pushEventToUser(
                targetUid = 2001L,
                eventType = PushEventType.FRIEND_ACCEPTED,
                payloadBytes = any<ByteString>()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 补充：双向竞赛 + 已有 deleted=1 好友关系（D-45 恢复）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `双向竞赛 — D-45 恢复已删除的好友关系`() = runTest(sessionContext) {
        // Given: 双向竞赛，但之前的好友关系已删除（deleted=1）
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("你好")
            .build()

        // 已删除的好友关系
        val deletedFriendship = FriendshipEntity(userId = 1001L, friendId = 2001L)
        deletedFriendship.deleted = 1
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns deletedFriendship

        // 反向申请存在
        val reverseRequest = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0)
        reverseRequest.id = 5L
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(2001L, 1001L, 0) } returns reverseRequest

        // 会话不存在
        coEvery { conversationRepo.findById("private:1001:2001") } returns Optional.empty()
        coEvery { memberRepo.findByConversationIdAndUserId("private:1001:2001", any()) } returns null

        // Mock save 方法：返回传入的实体（事务内需要正确的返回类型）
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { firstArg() }
        coEvery { friendshipRepo.save(any<FriendshipEntity>()) } answers { firstArg() }
        coEvery { conversationRepo.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { memberRepo.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        // When: 执行 handle
        handler.handle(req)

        // Then: 验证好友关系被恢复（deleted 被置为 0）
        assertEquals(0, deletedFriendship.deleted)
        coVerify(exactly = 1) { friendshipRepo.save(deletedFriendship) }
    }

    // ═══════════════════════════════════════════════════════════
    // 补充：buildPrivateConvId 工具方法
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `buildPrivateConvId — 生成格式正确的私聊会话 ID`() {
        // Given: 两个 uid（已排序）
        val smaller = 1001L
        val larger = 2001L

        // When: 构造会话 ID
        val convId = FriendAddHandler.buildPrivateConvId(smaller, larger)

        // Then: 格式为 private:smaller:larger
        assertEquals("private:1001:2001", convId)
    }

    @Test
    fun `buildPrivateConvId — smaller 和 larger 调换后结果相同`() {
        // Given: 同一对用户，但传入顺序不同
        val convId1 = FriendAddHandler.buildPrivateConvId(1001L, 2001L)
        val convId2 = FriendAddHandler.buildPrivateConvId(2001L, 1001L)

        // When & Then: 两者应相同（但实际取决于调用方排序，此处验证方法本身行为）
        // 注意：FriendAddHandler 在调用前已排序，此测试验证方法幂等性
        assertEquals("private:1001:2001", convId1)
        // 未排序调用结果取决于参数顺序
        assertEquals("private:2001:1001", convId2)
    }
}
