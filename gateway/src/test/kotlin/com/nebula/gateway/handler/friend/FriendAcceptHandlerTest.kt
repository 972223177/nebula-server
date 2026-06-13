package com.nebula.gateway.handler.friend

import com.google.protobuf.ByteString
import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.handler.conversation.ConversationLockManager
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
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate
import java.util.Optional
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * FriendAcceptHandler 接受好友申请单元测试（D-43, D-45, D-52）。
 *
 * 覆盖场景：
 * - 正常接受 → 单事务创建好友+私聊会话+2成员+推送 FRIEND_ACCEPTED
 * - 请求不存在 → 抛出 FriendException(REQUEST_NOT_FOUND)
 * - 请求已处理（status != 0） → 抛出 FriendException(REQUEST_HANDLED)
 * - D-45 重加恢复 → 检测 deleted=1 记录并恢复
 */
class FriendAcceptHandlerTest {

    private lateinit var friendRequestRepo: FriendRequestRepository
    private lateinit var friendshipRepo: FriendshipRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var memberRepo: ConversationMemberRepository
    private lateinit var lockManager: ConversationLockManager
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var pushService: PushService
    private lateinit var handler: FriendAcceptHandler

    /** 当前用户（申请接收方） */
    private val session = Session(2001L, "token-y", "MOBILE", "dev-2", "conn-2")
    /** 注入 Session 的协程上下文 */
    private val sessionContext = EmptyCoroutineContext + SessionKey(session)

    /** 发起方 uid */
    private val fromUid = 1001L
    /** 接收方 uid（与 session.userId 一致） */
    private val toUid = 2001L

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

        handler = FriendAcceptHandler(
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
    // 场景 1：正常接受
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `正常接受 — 单事务创建好友关系和私聊会话并推送 FRIEND_ACCEPTED`() = runTest(sessionContext) {
        // Given: B 接受 A 的好友申请，申请状态为 pending
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(10L)
            .build()

        val friendRequest = FriendRequestEntity(fromUid = fromUid, toUid = toUid, status = 0)
        friendRequest.id = 10L
        coEvery { friendRequestRepo.findById(10L) } returns Optional.of(friendRequest)

        // 非好友
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null

        // 私聊会话不存在
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

        // Then: 返回 OK 响应
        assertNotNull(result)
        assertEquals(BizCode.OK.code, result.code)

        // 验证申请状态被更新为 accepted
        assertEquals(1, friendRequest.status)
        coVerify(exactly = 1) { friendRequestRepo.save(friendRequest) }

        // 验证好友关系被保存
        coVerify(exactly = 1) { friendshipRepo.save(any<FriendshipEntity>()) }

        // 验证私聊会话被创建
        coVerify(exactly = 1) { conversationRepo.save(any<ConversationEntity>()) }

        // 验证双方会话成员被创建（各一次）
        coVerify(exactly = 2) { memberRepo.save(any<ConversationMemberEntity>()) }

        // 验证 FRIEND_ACCEPTED 推送给双方
        coVerify(exactly = 1) {
            pushService.pushEventToUser(
                targetUid = fromUid,
                eventType = PushEventType.FRIEND_ACCEPTED,
                payloadBytes = any<ByteString>()
            )
        }
        coVerify(exactly = 1) {
            pushService.pushEventToUser(
                targetUid = toUid,
                eventType = PushEventType.FRIEND_ACCEPTED,
                payloadBytes = any<ByteString>()
            )
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 2：请求不存在
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `请求不存在 — 抛出 REQUEST_NOT_FOUND 异常`() = runTest(sessionContext) {
        // Given: 请求 ID 对应的申请记录不存在
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(999L)
            .build()

        coEvery { friendRequestRepo.findById(999L) } returns Optional.empty()

        // When & Then: 应抛出 FriendException(REQUEST_NOT_FOUND)
        val ex = assertFailsWith<FriendException> {
            handler.handle(req)
        }
        assertEquals(BizCode.REQUEST_NOT_FOUND, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 3：请求已处理（status != 0）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `请求已处理 — 抛出 REQUEST_HANDLED 异常`() = runTest(sessionContext) {
        // Given: 申请已被处理（status=1，已接受）
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(10L)
            .build()

        val friendRequest = FriendRequestEntity(fromUid = fromUid, toUid = toUid, status = 1)  // 已处理
        friendRequest.id = 10L
        coEvery { friendRequestRepo.findById(10L) } returns Optional.of(friendRequest)

        // When & Then: 应抛出 FriendException(REQUEST_HANDLED)
        val ex = assertFailsWith<FriendException> {
            handler.handle(req)
        }
        assertEquals(BizCode.REQUEST_HANDLED, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 4：D-45 重加恢复
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `D-45 重加恢复 — 检测 deleted=1 记录并恢复好友关系`() = runTest(sessionContext) {
        // Given: 申请正常，但之前的好友关系已被软删除（deleted=1）
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(10L)
            .build()

        val friendRequest = FriendRequestEntity(fromUid = fromUid, toUid = toUid, status = 0)
        friendRequest.id = 10L
        coEvery { friendRequestRepo.findById(10L) } returns Optional.of(friendRequest)

        // 已删除的好友关系（D-45 场景）
        val deletedFriendship = FriendshipEntity(userId = 1001L, friendId = 2001L)
        deletedFriendship.deleted = 1
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns deletedFriendship

        // 私聊会话不存在
        coEvery { conversationRepo.findById("private:1001:2001") } returns Optional.empty()
        coEvery { memberRepo.findByConversationIdAndUserId("private:1001:2001", any()) } returns null

        // Mock save 方法：返回传入的实体
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { firstArg() }
        coEvery { friendshipRepo.save(any<FriendshipEntity>()) } answers { firstArg() }
        coEvery { conversationRepo.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { memberRepo.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        // When: 执行 handle
        handler.handle(req)

        // Then: 验证好友关系被恢复（deleted 被置为 0），而不是创建新记录
        assertEquals(0, deletedFriendship.deleted)
        coVerify(exactly = 1) { friendshipRepo.save(deletedFriendship) }
    }

    // ═══════════════════════════════════════════════════════════
    // 补充：toUid 不匹配当前用户（越权）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun `越权操作 — 非申请接收方抛出 FORBIDDEN 异常`() = runTest(sessionContext) {
        // Given: 当前用户 uid=2001，但申请的 toUid=3001（不属于当前用户）
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(10L)
            .build()

        val friendRequest = FriendRequestEntity(fromUid = 1001L, toUid = 3001L, status = 0)
        friendRequest.id = 10L
        coEvery { friendRequestRepo.findById(10L) } returns Optional.of(friendRequest)

        // When & Then: 应抛出 FriendException(FORBIDDEN)
        val ex = assertFailsWith<FriendException> {
            handler.handle(req)
        }
        assertEquals(BizCode.FORBIDDEN, ex.bizCode)
    }
}
