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
import com.nebula.service.friend.FriendAcceptResult
import com.nebula.service.friend.FriendAddResult
import com.nebula.service.friend.FriendService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Phase 8 Friend 集成冒烟测试 — 精简版。
 *
 * 只保留完整的端到端流程测试（添加 → 接受 → 列表 → 待处理列表 → 拒绝 → 删除），
 * 单个 Handler 冒烟测试已移至对应 HandlerTest 中覆盖。
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

    // ===================================================================
    // 完整流程：add → accept → list → requests → reject → delete
    // ===================================================================

    @Test
    fun fullFlowShouldCompleteFriendLifecycle() = runTest {
        // 预创建所有 Handler
        val transactionTemplate = mockTransactionTemplate()
        val friendshipRepository = mockk<com.nebula.repository.repository.FriendshipRepository>()
        val addHandler = FriendAddHandler(
            friendService, pushService, mockLockManager(), transactionTemplate, friendshipRepository
        )
        val acceptHandler = FriendAcceptHandler(
            friendService, pushService, mockLockManager(), transactionTemplate, friendshipRepository
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
