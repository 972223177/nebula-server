package com.nebula.gateway.dispatcher

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
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
import com.nebula.service.friend.FriendAcceptResult
import com.nebula.service.friend.FriendAddResult
import com.nebula.service.friend.FriendService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
        handler: Handler<Req, Resp>,
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
    fun friendAddShouldReturnRequestIdAndPushFriendRequest() = runTest {
        // Given: A 向 B 发起好友申请
        coEvery { friendService.addFriend(any(), any()) } returns FriendAddResult(
            requestId = 42L, isMutualAccept = false, convId = null, fromUid = 1001L, toUid = 2001L
        )

        val dispatcher = singleHandlerDispatcher(
            FriendAddHandler(friendService, pushService, mockLockManager(), mockk(), mockk()),
            FriendAddReq::class, FriendAddResp::class
        )

        // When
        val response = dispatcher.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(2001L).setMessage("你好").build())

        // Then
        assertEquals(BizCode.OK.code, response.code, "正常申请应返回 200")
        assertEquals(42L, FriendAddResp.parseFrom(response.result).requestId)
        coVerify(exactly = 1) {
            pushService.pushEventToUser(2001L, PushEventType.FRIEND_REQUEST, any())
        }
    }

    @Test
    fun friendAddSelfApplicationShouldReturnSelfFriend() = runTest {
        val dispatcher = singleHandlerDispatcher(
            FriendAddHandler(friendService, pushService, mockLockManager(), mockk(), mockk()),
            FriendAddReq::class, FriendAddResp::class
        )
        coEvery { friendService.addFriend(any(), any()) } throws FriendException(BizCode.SELF_FRIEND)

        val response = dispatcher.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(1001L).setMessage("加自己").build())

        assertEquals(BizCode.SELF_FRIEND.code, response.code, "自我申请应返回 SELF_FRIEND")
    }

    @Test
    fun friendAddMutualShouldCreateFriendshipAndPushAccepted() = runTest {
        // Given: 双向竞赛场景
        coEvery { friendService.addFriend(any(), any()) } returns FriendAddResult(
            requestId = 5L, isMutualAccept = true, convId = abConvId, fromUid = 1001L, toUid = 2001L
        )

        val dispatcher = singleHandlerDispatcher(
            FriendAddHandler(friendService, pushService, mockLockManager(), mockk(), mockk()),
            FriendAddReq::class, FriendAddResp::class
        )

        val response = dispatcher.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(2001L).setMessage("你好").build())

        assertEquals(BizCode.OK.code, response.code, "双向竞赛应返回 200")
        assertEquals(5L, FriendAddResp.parseFrom(response.result).requestId)
        coVerify(exactly = 2) {
            pushService.pushEventToUser(any(), eq(PushEventType.FRIEND_ACCEPTED), any())
        }
    }

    // ===================================================================
    // 2. friend/accept — 接受好友申请
    // ===================================================================

    @Test
    fun friendAcceptShouldCreateFriendshipAndPushAccepted() = runTest {
        // Given: 存在 pending 好友申请，A 接受
        coEvery { friendService.acceptFriendRequest(any(), any()) } returns FriendAcceptResult(
            fromUid = 2001L, toUid = 1001L, convId = abConvId
        )

        val dispatcher = singleHandlerDispatcher(
            FriendAcceptHandler(friendService, pushService, mockLockManager(), mockk(), mockk()),
            FriendAcceptReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/accept",
            FriendAcceptReq.newBuilder().setRequestId(10L).build())

        assertEquals(BizCode.OK.code, response.code, "接受申请应返回 200")
        coVerify(exactly = 2) {
            pushService.pushEventToUser(any(), eq(PushEventType.FRIEND_ACCEPTED), any())
        }
    }

    @Test
    fun friendAcceptWithNonExistentRequestShouldReturnNotFound() = runTest {
        coEvery { friendService.acceptFriendRequest(any(), any()) } throws FriendException(BizCode.REQUEST_NOT_FOUND)

        val dispatcher = singleHandlerDispatcher(
            FriendAcceptHandler(friendService, pushService, mockLockManager(), mockk(), mockk()),
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
    fun friendRejectShouldReturn200() = runTest {
        coEvery { friendService.rejectFriendRequest(any(), any()) } returns Unit

        val dispatcher = singleHandlerDispatcher(
            FriendRejectHandler(friendService),
            FriendRejectReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/reject",
            FriendRejectReq.newBuilder().setRequestId(10L).build())

        assertEquals(BizCode.OK.code, response.code, "拒绝申请应返回 200")
    }

    @Test
    fun friendRejectWithNonExistentRequestShouldReturnNotFound() = runTest {
        coEvery { friendService.rejectFriendRequest(any(), any()) } throws FriendException(BizCode.REQUEST_NOT_FOUND)

        val dispatcher = singleHandlerDispatcher(
            FriendRejectHandler(friendService),
            FriendRejectReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/reject",
            FriendRejectReq.newBuilder().setRequestId(999L).build())

        assertEquals(BizCode.REQUEST_NOT_FOUND.code, response.code)
    }

    @Test
    fun friendRejectHandledRequestShouldReturnRequestHandled() = runTest {
        coEvery { friendService.rejectFriendRequest(any(), any()) } throws FriendException(BizCode.REQUEST_HANDLED)

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
    fun friendDeleteShouldReturn200() = runTest {
        coEvery { friendService.deleteFriend(any(), any()) } returns Unit

        val dispatcher = singleHandlerDispatcher(
            FriendDeleteHandler(friendService),
            FriendDeleteReq::class, Response::class
        )

        val response = dispatcher.dispatchAs("friend/delete",
            FriendDeleteReq.newBuilder().setUid(2001L).build())

        assertEquals(BizCode.OK.code, response.code, "删除好友应返回 200")
    }

    @Test
    fun friendDeleteNotFoundShouldReturnFriendNotFound() = runTest {
        coEvery { friendService.deleteFriend(any(), any()) } throws FriendException(BizCode.FRIEND_NOT_FOUND)

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
    fun friendListShouldReturnFriendList() = runTest {
        // Given: A 有 2 个好友 B 和 C
        coEvery { friendService.listFriends(any(), any()) } returns FriendListResp.newBuilder()
            .addFriends(FriendBrief.newBuilder().setUid(2001L).build())
            .addFriends(FriendBrief.newBuilder().setUid(3001L).build())
            .build()

        val dispatcher = singleHandlerDispatcher(
            FriendListHandler(friendService),
            FriendListReq::class, FriendListResp::class
        )

        val response = dispatcher.dispatchAs("friend/list",
            FriendListReq.newBuilder().setLimit(20).build())

        assertEquals(BizCode.OK.code, response.code, "好友列表应返回 200")
        assertEquals(2, FriendListResp.parseFrom(response.result).friendsCount, "应有 2 个好友")
    }

    @Test
    fun friendListEmptyShouldReturn200() = runTest {
        coEvery { friendService.listFriends(any(), any()) } returns FriendListResp.getDefaultInstance()

        val dispatcher = singleHandlerDispatcher(
            FriendListHandler(friendService),
            FriendListReq::class, FriendListResp::class
        )

        val response = dispatcher.dispatchAs("friend/list",
            FriendListReq.newBuilder().setLimit(20).build())

        assertEquals(BizCode.OK.code, response.code, "空好友列表应返回 200")
        assertEquals(0, FriendListResp.parseFrom(response.result).friendsCount)
    }

    // ===================================================================
    // 6. friend/requests — 待处理申请列表
    // ===================================================================

    @Test
    fun friendRequestsShouldReturnPendingList() = runTest {
        // Given: A 有 2 个待处理的好友申请
        coEvery { friendService.getFriendRequests(any(), any()) } returns FriendRequestsResp.newBuilder()
            .addRequests(FriendRequestItem.newBuilder().setRequestId(10L).build())
            .addRequests(FriendRequestItem.newBuilder().setRequestId(11L).build())
            .build()

        val dispatcher = singleHandlerDispatcher(
            FriendRequestsHandler(friendService),
            FriendRequestsReq::class, FriendRequestsResp::class
        )

        val response = dispatcher.dispatchAs("friend/requests",
            FriendRequestsReq.getDefaultInstance())

        assertEquals(BizCode.OK.code, response.code, "申请列表应返回 200")
        assertEquals(2, FriendRequestsResp.parseFrom(response.result).requestsCount, "应有 2 个待处理申请")
    }

    @Test
    fun friendRequestsEmptyShouldReturnEmptyList() = runTest {
        coEvery { friendService.getFriendRequests(any(), any()) } returns FriendRequestsResp.getDefaultInstance()

        val dispatcher = singleHandlerDispatcher(
            FriendRequestsHandler(friendService),
            FriendRequestsReq::class, FriendRequestsResp::class
        )

        val response = dispatcher.dispatchAs("friend/requests",
            FriendRequestsReq.getDefaultInstance())

        assertEquals(BizCode.OK.code, response.code, "空列表应返回 200")
        assertEquals(0, FriendRequestsResp.parseFrom(response.result).requestsCount)
    }

    // ===================================================================
    // 7. 完整流程：add → accept → list → requests → reject → delete
    // ===================================================================

    @Test
    fun fullFlowShouldCompleteFriendLifecycle() = runTest {
        // 预创建所有 Handler
        val addHandler = FriendAddHandler(
            friendService, pushService, mockLockManager(), mockk(), mockk()
        )
        val acceptHandler = FriendAcceptHandler(
            friendService, pushService, mockLockManager(), mockk(), mockk()
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
        coEvery { friendService.addFriend(any(), any()) } returns FriendAddResult(
            requestId = 100L, isMutualAccept = false, convId = null, fromUid = 2001L, toUid = 1001L
        )

        val step1Resp = dispatcherB.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(1001L).setMessage("B想加A").build())
        assertEquals(BizCode.OK.code, step1Resp.code, "步骤1: B向A申请应返回 200")
        assertEquals(100L, FriendAddResp.parseFrom(step1Resp.result).requestId)

        // ---- 步骤 2: A 接受 B 的申请 ----
        coEvery { friendService.acceptFriendRequest(any(), any()) } returns FriendAcceptResult(
            fromUid = 2001L, toUid = 1001L, convId = abConvId
        )

        val step2Resp = dispatcherA.dispatchAs("friend/accept",
            FriendAcceptReq.newBuilder().setRequestId(100L).build())
        assertEquals(BizCode.OK.code, step2Resp.code, "步骤2: A接受申请应返回 200")

        // ---- 步骤 3: A 查看好友列表（应有 B） ----
        coEvery { friendService.listFriends(any(), any()) } returns FriendListResp.newBuilder()
            .addFriends(FriendBrief.newBuilder().setUid(2001L).build())
            .build()

        val step3Resp = dispatcherA.dispatchAs("friend/list",
            FriendListReq.newBuilder().setLimit(20).build())
        assertEquals(BizCode.OK.code, step3Resp.code, "步骤3: 好友列表应返回 200")
        assertEquals(1, FriendListResp.parseFrom(step3Resp.result).friendsCount, "步骤3: 应有 1 个好友")

        // ---- 步骤 4: C 向 A 发申请，A 查看待处理列表 ----
        coEvery { friendService.addFriend(any(), any()) } returns FriendAddResult(
            requestId = 101L, isMutualAccept = false, convId = null, fromUid = 3001L, toUid = 1001L
        )

        val step4aResp = dispatcherC.dispatchAs("friend/add",
            FriendAddReq.newBuilder().setToUid(1001L).setMessage("C加A").build())
        assertEquals(BizCode.OK.code, step4aResp.code, "步骤4a: C向A申请应返回 200")

        // A 查看待处理列表
        coEvery { friendService.getFriendRequests(any(), any()) } returns FriendRequestsResp.newBuilder()
            .addRequests(FriendRequestItem.newBuilder().setRequestId(101L).build())
            .build()

        val step4bResp = dispatcherA.dispatchAs("friend/requests",
            FriendRequestsReq.getDefaultInstance())
        assertEquals(BizCode.OK.code, step4bResp.code, "步骤4b: 待处理列表应返回 200")
        assertEquals(1, FriendRequestsResp.parseFrom(step4bResp.result).requestsCount, "步骤4b: 应有 1 个待处理申请")

        // ---- 步骤 5: A 拒绝 C 的申请 ----
        coEvery { friendService.rejectFriendRequest(any(), any()) } returns Unit

        val step5Resp = dispatcherA.dispatchAs("friend/reject",
            FriendRejectReq.newBuilder().setRequestId(101L).build())
        assertEquals(BizCode.OK.code, step5Resp.code, "步骤5: A拒绝C应返回 200")

        // ---- 步骤 6: A 删除好友 B ----
        coEvery { friendService.deleteFriend(any(), any()) } returns Unit

        val step6Resp = dispatcherA.dispatchAs("friend/delete",
            FriendDeleteReq.newBuilder().setUid(2001L).build())
        assertEquals(BizCode.OK.code, step6Resp.code, "步骤6: A删除好友B应返回 200")
    }
}
