package com.nebula.gateway.handler.friend

import com.google.protobuf.ByteString
import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.mockLockManager
import com.nebula.gateway.testutil.mockTransactionTemplate
import com.nebula.service.friend.FriendAcceptResult
import com.nebula.service.friend.FriendService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * FriendAcceptHandler 接受好友申请单元测试（D-43, D-45, D-52）。
 *
 * 覆盖场景：
 * - 正常接受 → 委托 FriendService + 推送 FRIEND_ACCEPTED 给双方
 * - 请求不存在 → 抛出 FriendException(REQUEST_NOT_FOUND)
 * - 请求已处理（status != 0） → 抛出 FriendException(REQUEST_HANDLED)
 * - 越权操作 → 抛出 FriendException(FORBIDDEN)
 */
class FriendAcceptHandlerTest {

    private lateinit var friendService: FriendService
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
        friendService = mockk()
        pushService = mockk(relaxed = true)

        val lockManager = mockLockManager()
        val transactionTemplate = mockTransactionTemplate()

        handler = FriendAcceptHandler(
            friendService,
            pushService,
            lockManager,
            transactionTemplate,
            mockk()
        )
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常接受
    // ═══════════════════════════════════════════════════════════

    @Test
    fun acceptShouldDelegateServiceAndPushFriendAccepted() = runTest(sessionContext) {
        // Given: B 接受 A 的好友申请
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(10L)
            .build()

        coEvery { friendService.acceptFriendRequest(any<FriendAcceptReq>(), any()) } returns FriendAcceptResult(
            fromUid = fromUid,
            toUid = toUid,
            convId = "private:1001:2001"
        )

        // When: 执行 handle
        val result = handler.handle(req)

        // Then: 返回 OK 响应
        assertNotNull(result)
        assertEquals(BizCode.OK.code, result.code)

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
    fun acceptRequestNotFoundShouldThrowRequestNotFound() = runTest(sessionContext) {
        // Given: 请求 ID 对应的申请记录不存在
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(999L)
            .build()

        coEvery { friendService.acceptFriendRequest(any<FriendAcceptReq>(), any()) } throws FriendException(BizCode.REQUEST_NOT_FOUND)

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
    fun acceptRequestHandledShouldThrowRequestHandled() = runTest(sessionContext) {
        // Given: 申请已被处理
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(10L)
            .build()

        coEvery { friendService.acceptFriendRequest(any<FriendAcceptReq>(), any()) } throws FriendException(BizCode.REQUEST_HANDLED)

        // When & Then: 应抛出 FriendException(REQUEST_HANDLED)
        val ex = assertFailsWith<FriendException> {
            handler.handle(req)
        }
        assertEquals(BizCode.REQUEST_HANDLED, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 4：越权操作（toUid 不匹配当前用户）
    // ═══════════════════════════════════════════════════════════

    @Test
    fun unauthorizedAcceptShouldThrowForbidden() = runTest(sessionContext) {
        // Given: 申请的 toUid 不属于当前用户
        val req = FriendAcceptReq.newBuilder()
            .setRequestId(10L)
            .build()

        coEvery { friendService.acceptFriendRequest(any<FriendAcceptReq>(), any()) } throws FriendException(BizCode.FORBIDDEN)

        // When & Then: 应抛出 FriendException(FORBIDDEN)
        val ex = assertFailsWith<FriendException> {
            handler.handle(req)
        }
        assertEquals(BizCode.FORBIDDEN, ex.bizCode)
    }
}
