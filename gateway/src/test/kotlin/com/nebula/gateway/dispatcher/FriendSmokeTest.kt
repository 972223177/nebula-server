package com.nebula.gateway.dispatcher

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
import com.nebula.chat.friend.FriendDeleteReq
import com.nebula.chat.friend.FriendListReq
import com.nebula.chat.friend.FriendListResp
import com.nebula.chat.friend.FriendRejectReq
import com.nebula.chat.friend.FriendRequestsReq
import com.nebula.chat.friend.FriendRequestsResp
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.friend.FriendAcceptHandler
import com.nebula.gateway.handler.friend.FriendAddHandler
import com.nebula.gateway.handler.friend.FriendDeleteHandler
import com.nebula.gateway.handler.friend.FriendListHandler
import com.nebula.gateway.handler.friend.FriendRejectHandler
import com.nebula.gateway.handler.friend.FriendRequestsHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.testutil.buildTestDispatcher
import com.nebula.gateway.testutil.dispatchAs
import com.nebula.gateway.testutil.handlerEntry
import com.nebula.gateway.testutil.mockLockManager
import com.nebula.gateway.testutil.mockTransactionTemplate
import com.nebula.gateway.testutil.testUser
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendRequestRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.UserRepository
import com.nebula.service.friend.FriendService
import com.google.protobuf.ByteString
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import java.util.Optional
import kotlin.test.assertEquals

/**
 * Phase 8 Friend 集成冒烟测试。
 *
 * 通过 Dispatcher 测试完整的 request→dispatch→response 链路，
 * 覆盖 Phase 8 全部 6 个 Friend Handler 的端到端行为。
 *
 * 使用 [TestHelper] 提供的工具函数简化测试构建：
 * - [handlerEntry] 替代手写 ProtoCodec 逻辑
 * - [dispatchAs] 替代 `withContext + SessionKey + dispatch + requestEnvelope` 模式
 * - [buildTestDispatcher] 替代手写 Interceptor Pipeline
 * - [mockLockManager] / [mockTransactionTemplate] 替代手写 Mock
 */
class FriendSmokeTest {

    // ========== Mock 依赖 ==========

    private lateinit var friendService: FriendService
    private lateinit var friendRequestRepo: FriendRequestRepository
    private lateinit var friendshipRepo: FriendshipRepository
    private lateinit var conversationRepo: ConversationRepository
    private lateinit var memberRepo: ConversationMemberRepository
    private lateinit var userRepo: UserRepository
    private lateinit var onlineStatusRepo: OnlineStatusRepository
    private lateinit var privacyRepo: PrivacyRepository
    private lateinit var pushService: PushService
    private lateinit var sessionRegistry: SessionRegistry

    /** 用户 A（主动方） */
    private val userA = Session(1001L, "token-a", "MOBILE", "dev-1", "conn-1")
    /** 用户 B（被动方） */
    private val userB = Session(2001L, "token-b", "MOBILE", "dev-2", "conn-2")
    /** 用户 C（第三方） */
    private val userC = Session(3001L, "token-c", "MOBILE", "dev-3", "conn-3")

    /** 排序后的私聊会话 ID */
    private val abConvId = "private:1001:2001"

    @BeforeEach
    fun setUp() {
        friendService = mockk()
        friendRequestRepo = mockk()
        friendshipRepo = mockk()
        conversationRepo = mockk()
        memberRepo = mockk()
        userRepo = mockk(relaxed = true)
        onlineStatusRepo = mockk()
        privacyRepo = mockk()
        pushService = mockk(relaxed = true)
        sessionRegistry = mockk()
    }

    // ═══════════════════════════════════════════════════════════
    // 辅助：构建单 Handler Dispatcher
    // ═══════════════════════════════════════════════════════════

    /**
     * 构建只注册一个 Handler 的 Dispatcher。
     *
     * @param handler Handler 实例
     * @param reqClass 请求类型
     * @param respClass 响应类型
     * @param session 测试 Session
     * @return 配置好的 Dispatcher
     */
    private fun <Req : Any, Resp : Any> singleHandlerDispatcher(
        handler: com.nebula.gateway.handler.Handler<Req, Resp>,
        reqClass: kotlin.reflect.KClass<Req>,
        respClass: kotlin.reflect.KClass<Resp>,
        session: Session = userA
    ) = buildTestDispatcher(
        HandlerRegistry().apply { register(handlerEntry(handler, reqClass, respClass)) },
        session = session, sessionRegistry = sessionRegistry
    )

    // ===================================================================
    // 1. friend/add — 发送好友申请
    // ===================================================================

    @Test
    fun `friend add - 正常申请返回requestId并推送FRIEND_REQUEST`() = runTest {
        // Given: A 向 B 发起好友申请，无双向竞赛、无重复申请、非好友
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(2001L, 1001L, 0) } returns null
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(1001L, 2001L, 0) } returns null

        val savedRequest = FriendRequestEntity(fromUid = 1001L, toUid = 2001L, status = 0, message = "你好")
        savedRequest.id = 42L
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } returns savedRequest

        val dispatcher = singleHandlerDispatcher(
            FriendAddHandler(friendService, pushService, mockLockManager()),
            FriendAddReq::class, FriendAddResp::class
        )

        // When
        val response = dispatcher.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(2001L).setMessage("你好").build())

        // Then
        assertEquals(200, response.code, "正常申请应返回 200")
        assertEquals(42L, FriendAddResp.parseFrom(response.result).requestId)
        coVerify(exactly = 1) {
            pushService.pushEventToUser(2001L, PushEventType.FRIEND_REQUEST, any<ByteString>())
        }
    }

    @Test
    fun `friend add - 自我申请返回SELF_FRIEND`() = runTest {
        val dispatcher = singleHandlerDispatcher(
            FriendAddHandler(friendService, pushService, mockLockManager()),
            FriendAddReq::class, FriendAddResp::class
        )

        val response = dispatcher.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(1001L).setMessage("加自己").build())

        assertEquals(BizCode.SELF_FRIEND.code, response.code, "自我申请应返回 SELF_FRIEND")
    }

    @Test
    fun `friend add - 双向竞赛自动创建好友关系并推送FRIEND_ACCEPTED`() = runTest {
        // Given: A 向 B 申请时，B 已向 A 发送了 pending 申请
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null
        val reverseRequest = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0)
        reverseRequest.id = 5L
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(2001L, 1001L, 0) } returns reverseRequest
        coEvery { conversationRepo.findById(abConvId) } returns Optional.empty()
        coEvery { memberRepo.findByConversationIdAndUserId(abConvId, any()) } returns null
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { firstArg() }
        coEvery { friendshipRepo.save(any<FriendshipEntity>()) } answers { firstArg() }
        coEvery { conversationRepo.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { memberRepo.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            FriendAddHandler(friendService, pushService, mockLockManager()),
            FriendAddReq::class, FriendAddResp::class
        )

        val response = dispatcher.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(2001L).setMessage("你好").build())

        assertEquals(200, response.code, "双向竞赛应返回 200")
        assertEquals(5L, FriendAddResp.parseFrom(response.result).requestId)
        coVerify(exactly = 2) {
            pushService.pushEventToUser(any(), eq(PushEventType.FRIEND_ACCEPTED), any<ByteString>())
        }
    }

    // ===================================================================
    // 2. friend/accept — 接受好友申请
    // ===================================================================

    @Test
    fun `friend accept - 正常接受创建好友关系并推送FRIEND_ACCEPTED`() = runTest {
        // Given: 存在 pending 好友申请，A 接受
        val request = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0)
        request.id = 10L
        coEvery { friendRequestRepo.findById(10L) } returns Optional.of(request)
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null
        coEvery { conversationRepo.findById(abConvId) } returns Optional.empty()
        coEvery { memberRepo.findByConversationIdAndUserId(abConvId, any()) } returns null
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { firstArg() }
        coEvery { friendshipRepo.save(any<FriendshipEntity>()) } answers { firstArg() }
        coEvery { conversationRepo.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { memberRepo.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            FriendAcceptHandler(friendService, pushService, mockLockManager()),
            FriendAcceptReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/accept",
            FriendAcceptReq.newBuilder().setRequestId(10L).build())

        assertEquals(200, response.code, "接受申请应返回 200")
        coVerify(exactly = 2) {
            pushService.pushEventToUser(any(), eq(PushEventType.FRIEND_ACCEPTED), any<ByteString>())
        }
    }

    @Test
    fun `friend accept - 请求不存在返回REQUEST_NOT_FOUND`() = runTest {
        coEvery { friendRequestRepo.findById(999L) } returns Optional.empty()

        val dispatcher = singleHandlerDispatcher(
            FriendAcceptHandler(friendService, pushService, mockLockManager()),
            FriendAcceptReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/accept",
            FriendAcceptReq.newBuilder().setRequestId(999L).build())

        assertEquals(BizCode.REQUEST_NOT_FOUND.code, response.code)
    }

    // ===================================================================
    // 3. friend/reject — 拒绝好友申请
    // ===================================================================

    @Test
    fun `friend reject - 正常拒绝返回200`() = runTest {
        // Given: 存在 pending 好友申请，A 拒绝
        val request = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0)
        request.id = 10L
        coEvery { friendRequestRepo.findById(10L) } returns Optional.of(request)
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            FriendRejectHandler(friendService),
            FriendRejectReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/reject",
            FriendRejectReq.newBuilder().setRequestId(10L).build())

        assertEquals(200, response.code, "拒绝申请应返回 200")
        assertEquals(2, request.status, "申请状态应为 rejected(2)")
    }

    @Test
    fun `friend reject - 请求不存在返回REQUEST_NOT_FOUND`() = runTest {
        coEvery { friendRequestRepo.findById(999L) } returns Optional.empty()

        val dispatcher = singleHandlerDispatcher(
            FriendRejectHandler(friendService),
            FriendRejectReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/reject",
            FriendRejectReq.newBuilder().setRequestId(999L).build())

        assertEquals(BizCode.REQUEST_NOT_FOUND.code, response.code)
    }

    @Test
    fun `friend reject - 已处理的申请返回REQUEST_HANDLED`() = runTest {
        val request = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 1) // 已接受
        request.id = 10L
        coEvery { friendRequestRepo.findById(10L) } returns Optional.of(request)

        val dispatcher = singleHandlerDispatcher(
            FriendRejectHandler(friendService),
            FriendRejectReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/reject",
            FriendRejectReq.newBuilder().setRequestId(10L).build())

        assertEquals(BizCode.REQUEST_HANDLED.code, response.code)
    }

    // ===================================================================
    // 4. friend/delete — 删除好友
    // ===================================================================

    @Test
    fun `friend delete - 正常删除返回200`() = runTest {
        // Given: A 和 B 已是好友
        val friendship = FriendshipEntity(userId = 1001L, friendId = 2001L)
        friendship.deleted = 0
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns friendship
        coEvery { friendshipRepo.save(any<FriendshipEntity>()) } answers { firstArg() }

        val dispatcher = singleHandlerDispatcher(
            FriendDeleteHandler(friendService),
            FriendDeleteReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/delete",
            FriendDeleteReq.newBuilder().setUid(2001L).build())

        assertEquals(200, response.code, "删除好友应返回 200")
        assertEquals(1, friendship.deleted, "好友关系应为软删除状态")
    }

    @Test
    fun `friend delete - 好友不存在返回FRIEND_NOT_FOUND`() = runTest {
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null

        val dispatcher = singleHandlerDispatcher(
            FriendDeleteHandler(friendService),
            FriendDeleteReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/delete",
            FriendDeleteReq.newBuilder().setUid(2001L).build())

        assertEquals(BizCode.FRIEND_NOT_FOUND.code, response.code)
    }

    // ===================================================================
    // 5. friend/list — 好友列表
    // ===================================================================

    @Test
    fun `friend list - 正常查询返回好友列表`() = runTest {
        // Given: A 有 2 个好友 B 和 C
        val fb1 = FriendshipEntity(userId = 1001L, friendId = 2001L).apply { deleted = 0 }
        val fb2 = FriendshipEntity(userId = 1001L, friendId = 3001L).apply { deleted = 0 }
        coEvery {
            friendshipRepo.findFriendsByUserId(1001L, 0L, PageRequest.of(0, 20))
        } returns listOf(fb1, fb2)
        coEvery { userRepo.findAllById(listOf(2001L, 3001L)) } returns listOf(
            testUser(2001L, "userb", "用户B"),
            testUser(3001L, "userc", "用户C")
        )
        coEvery { privacyRepo.batchGetHideOnlineStatus(listOf(2001L, 3001L)) } returns emptySet()
        coEvery { onlineStatusRepo.batchGetStatus(listOf(2001L, 3001L)) } returns emptyMap()

        val dispatcher = singleHandlerDispatcher(
            FriendListHandler(friendService),
            FriendListReq::class, FriendListResp::class
        )

        val response = dispatcher.dispatchAs("friend/list",
            FriendListReq.newBuilder().setLimit(20).build())

        assertEquals(200, response.code, "好友列表应返回 200")
        assertEquals(2, FriendListResp.parseFrom(response.result).friendsCount, "应有 2 个好友")
    }

    @Test
    fun `friend list - 空列表返回200`() = runTest {
        coEvery {
            friendshipRepo.findFriendsByUserId(1001L, 0L, PageRequest.of(0, 20))
        } returns emptyList()

        val dispatcher = singleHandlerDispatcher(
            FriendListHandler(friendService),
            FriendListReq::class, FriendListResp::class
        )

        val response = dispatcher.dispatchAs("friend/list",
            FriendListReq.newBuilder().setLimit(20).build())

        assertEquals(200, response.code, "空好友列表应返回 200")
        assertEquals(0, FriendListResp.parseFrom(response.result).friendsCount)
    }

    // ===================================================================
    // 6. friend/requests — 待处理申请列表
    // ===================================================================

    @Test
    fun `friend requests - 正常查询返回pending申请列表`() = runTest {
        // Given: A 有 2 个待处理的好友申请
        val req1 = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0, message = "你好")
        req1.id = 10L
        val req2 = FriendRequestEntity(fromUid = 3001L, toUid = 1001L, status = 0, message = "加好友")
        req2.id = 11L
        coEvery {
            friendRequestRepo.findByToUidAndStatusOrderByCreatedAtDesc(1001L, 0)
        } returns listOf(req1, req2)
        coEvery { userRepo.findAllById(listOf(2001L, 3001L)) } returns listOf(
            testUser(2001L, "userb", "用户B"),
            testUser(3001L, "userc", "用户C")
        )

        val dispatcher = singleHandlerDispatcher(
            FriendRequestsHandler(friendService),
            FriendRequestsReq::class, FriendRequestsResp::class
        )

        val response = dispatcher.dispatchAs("friend/requests",
            FriendRequestsReq.getDefaultInstance())

        assertEquals(200, response.code, "申请列表应返回 200")
        assertEquals(2, FriendRequestsResp.parseFrom(response.result).requestsCount, "应有 2 个待处理申请")
    }

    @Test
    fun `friend requests - 无申请返回空列表`() = runTest {
        coEvery {
            friendRequestRepo.findByToUidAndStatusOrderByCreatedAtDesc(1001L, 0)
        } returns emptyList()

        val dispatcher = singleHandlerDispatcher(
            FriendRequestsHandler(friendService),
            FriendRequestsReq::class, FriendRequestsResp::class
        )

        val response = dispatcher.dispatchAs("friend/requests",
            FriendRequestsReq.getDefaultInstance())

        assertEquals(200, response.code, "空列表应返回 200")
        assertEquals(0, FriendRequestsResp.parseFrom(response.result).requestsCount)
    }

    // ===================================================================
    // 7. 完整流程：add → accept → list → requests → reject → delete
    // ===================================================================

    @Test
    fun `完整流程 - 好友全链路冒烟`() = runTest {
        // 预创建所有 Handler
        val addHandler = FriendAddHandler(
            friendService, pushService, mockLockManager()
        )
        val acceptHandler = FriendAcceptHandler(
            friendService, pushService, mockLockManager()
        )
        val rejectHandler = FriendRejectHandler(friendService)
        val deleteHandler = FriendDeleteHandler(friendService)
        val listHandler = FriendListHandler(friendService)
        val requestsHandler = FriendRequestsHandler(friendService)

        // 注册所有 Handler
        val registry = HandlerRegistry()
        registry.register(handlerEntry(addHandler, FriendAddReq::class, FriendAddResp::class))
        registry.register(handlerEntry(acceptHandler, FriendAcceptReq::class, Response::class))
        registry.register(handlerEntry(rejectHandler, FriendRejectReq::class, Response::class))
        registry.register(handlerEntry(deleteHandler, FriendDeleteReq::class, Response::class))
        registry.register(handlerEntry(listHandler, FriendListReq::class, FriendListResp::class))
        registry.register(handlerEntry(requestsHandler, FriendRequestsReq::class, FriendRequestsResp::class))

        val dispatcherA = buildTestDispatcher(registry, session = userA, sessionRegistry = sessionRegistry)
        val dispatcherB = buildTestDispatcher(registry, session = userB, sessionRegistry = sessionRegistry)
        val dispatcherC = buildTestDispatcher(registry, session = userC, sessionRegistry = sessionRegistry)

        // ---- 步骤 1: B 向 A 发送好友申请 ----
        val savedBtoA = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0, message = "B想加A")
        savedBtoA.id = 100L
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(1001L, 2001L, 0) } returns null
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(2001L, 1001L, 0) } returns null
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } returns savedBtoA

        val step1Resp = dispatcherB.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(1001L).setMessage("B想加A").build())
        assertEquals(200, step1Resp.code, "步骤1: B向A申请应返回 200")
        assertEquals(100L, FriendAddResp.parseFrom(step1Resp.result).requestId)

        // ---- 步骤 2: A 接受 B 的申请 ----
        val requestEntity = FriendRequestEntity(fromUid = 2001L, toUid = 1001L, status = 0, message = "B想加A")
        requestEntity.id = 100L
        coEvery { friendRequestRepo.findById(100L) } returns Optional.of(requestEntity)
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns null
        coEvery { conversationRepo.findById(abConvId) } returns Optional.empty()
        coEvery { memberRepo.findByConversationIdAndUserId(abConvId, any()) } returns null
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { firstArg() }
        coEvery { friendshipRepo.save(any<FriendshipEntity>()) } answers { firstArg() }
        coEvery { conversationRepo.save(any<ConversationEntity>()) } answers { firstArg() }
        coEvery { memberRepo.save(any<ConversationMemberEntity>()) } answers { firstArg() }

        val step2Resp = dispatcherA.dispatchAs("friend/accept",
            FriendAcceptReq.newBuilder().setRequestId(100L).build())
        assertEquals(200, step2Resp.code, "步骤2: A接受申请应返回 200")

        // ---- 步骤 3: A 查看好友列表（应有 B） ----
        val fbAB = FriendshipEntity(userId = 1001L, friendId = 2001L).apply { deleted = 0 }
        coEvery { friendshipRepo.findFriendsByUserId(1001L, 0L, PageRequest.of(0, 20)) } returns listOf(fbAB)
        coEvery { userRepo.findAllById(listOf(2001L)) } returns listOf(testUser(2001L, "userb", "用户B"))
        coEvery { privacyRepo.batchGetHideOnlineStatus(listOf(2001L)) } returns emptySet()
        coEvery { onlineStatusRepo.batchGetStatus(listOf(2001L)) } returns emptyMap()

        val step3Resp = dispatcherA.dispatchAs("friend/list",
            FriendListReq.newBuilder().setLimit(20).build())
        assertEquals(200, step3Resp.code, "步骤3: 好友列表应返回 200")
        assertEquals(1, FriendListResp.parseFrom(step3Resp.result).friendsCount, "步骤3: 应有 1 个好友")

        // ---- 步骤 4: C 向 A 发申请，A 查看待处理列表 ----
        val reqCtoA = FriendRequestEntity(fromUid = 3001L, toUid = 1001L, status = 0, message = "C加A")
        reqCtoA.id = 101L
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(1001L, 3001L, 0) } returns null
        coEvery { friendRequestRepo.findByFromUidAndToUidAndStatus(3001L, 1001L, 0) } returns null
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 3001L) } returns null
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } returns reqCtoA

        val step4aResp = dispatcherC.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(1001L).setMessage("C加A").build())
        assertEquals(200, step4aResp.code, "步骤4a: C向A申请应返回 200")

        // A 查看待处理列表
        coEvery {
            friendRequestRepo.findByToUidAndStatusOrderByCreatedAtDesc(1001L, 0)
        } returns listOf(reqCtoA)
        coEvery { userRepo.findAllById(listOf(3001L)) } returns listOf(testUser(3001L, "userc", "用户C"))

        val step4bResp = dispatcherA.dispatchAs("friend/requests",
            FriendRequestsReq.getDefaultInstance())
        assertEquals(200, step4bResp.code, "步骤4b: 待处理列表应返回 200")
        assertEquals(1, FriendRequestsResp.parseFrom(step4bResp.result).requestsCount, "步骤4b: 应有 1 个待处理申请")

        // ---- 步骤 5: A 拒绝 C 的申请 ----
        val rejectEntity = FriendRequestEntity(fromUid = 3001L, toUid = 1001L, status = 0, message = "C加A")
        rejectEntity.id = 101L
        coEvery { friendRequestRepo.findById(101L) } returns Optional.of(rejectEntity)
        coEvery { friendRequestRepo.save(any<FriendRequestEntity>()) } answers { firstArg() }

        val step5Resp = dispatcherA.dispatchAs("friend/reject",
            FriendRejectReq.newBuilder().setRequestId(101L).build())
        assertEquals(200, step5Resp.code, "步骤5: A拒绝C应返回 200")

        // ---- 步骤 6: A 删除好友 B ----
        val friendshipAB = FriendshipEntity(userId = 1001L, friendId = 2001L).apply { deleted = 0 }
        coEvery { friendshipRepo.findByUserIdAndFriendId(1001L, 2001L) } returns friendshipAB
        coEvery { friendshipRepo.save(any<FriendshipEntity>()) } answers { firstArg() }

        val step6Resp = dispatcherA.dispatchAs("friend/delete",
            FriendDeleteReq.newBuilder().setUid(2001L).build())
        assertEquals(200, step6Resp.code, "步骤6: A删除好友B应返回 200")
        assertEquals(1, friendshipAB.deleted, "步骤6: 好友关系应为软删除")
    }
}
