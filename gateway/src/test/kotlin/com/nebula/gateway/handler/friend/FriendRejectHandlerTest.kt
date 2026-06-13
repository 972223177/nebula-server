package com.nebula.gateway.handler.friend

import com.nebula.chat.Response
import com.nebula.chat.friend.FriendRejectReq
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.service.friend.FriendService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * FriendRejectHandler 拒绝好友申请 Handler 单元测试。
 *
 * 覆盖场景：
 * - 正常拒绝 → 委托 FriendService 拒绝，返回 Response(OK)
 * - 请求不存在 → 抛出 FriendException(REQUEST_NOT_FOUND)
 * - 请求已处理（status != 0）→ 抛出 FriendException(REQUEST_HANDLED)
 * - 非本人申请（toUid != session.userId）→ 抛出 FriendException(FORBIDDEN)
 *
 * Session 注入方式：使用 withContext(SessionKey(session)) 包裹 handle() 调用。
 */
class FriendRejectHandlerTest {

    private lateinit var friendService: FriendService
    private lateinit var handler: FriendRejectHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        friendService = mockk()
        handler = FriendRejectHandler(friendService)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常拒绝
    // ═══════════════════════════════════════════════════════════

    @Test
    fun rejectShouldDelegateServiceAndReturnOk() = runTest {
        // Given: 存在一条 pending 的好友申请，接收方是当前用户
        val req = FriendRejectReq.newBuilder()
            .setRequestId(42L)
            .build()

        coEvery { friendService.rejectFriendRequest(any<FriendRejectReq>(), any()) } returns Unit

        // When: 执行拒绝
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        // Then: 验证返回 OK 响应
        assertNotNull(result)
        assertEquals(BizCode.OK.code, result.code)
        assertEquals(BizCode.OK.msg, result.msg)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 2：请求不存在
    // ═══════════════════════════════════════════════════════════

    @Test
    fun rejectRequestNotFoundShouldThrowRequestNotFound() = runTest {
        // Given: requestId 对应的申请记录不存在
        val req = FriendRejectReq.newBuilder()
            .setRequestId(999L)
            .build()

        coEvery { friendService.rejectFriendRequest(any<FriendRejectReq>(), any()) } throws FriendException(BizCode.REQUEST_NOT_FOUND)

        // When & Then: 应抛出 FriendException(REQUEST_NOT_FOUND)
        val ex = assertFailsWith<FriendException> {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
        }
        assertEquals(BizCode.REQUEST_NOT_FOUND, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 3：请求已处理
    // ═══════════════════════════════════════════════════════════

    @Test
    fun rejectRequestHandledShouldThrowRequestHandled() = runTest {
        // Given: 申请记录存在但 status=1（已接受），不再是 pending 状态
        val req = FriendRejectReq.newBuilder()
            .setRequestId(42L)
            .build()

        coEvery { friendService.rejectFriendRequest(any<FriendRejectReq>(), any()) } throws FriendException(BizCode.REQUEST_HANDLED)

        // When & Then: 应抛出 FriendException(REQUEST_HANDLED)
        val ex = assertFailsWith<FriendException> {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
        }
        assertEquals(BizCode.REQUEST_HANDLED, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 4：非本人申请
    // ═══════════════════════════════════════════════════════════

    @Test
    fun unauthorizedRejectShouldThrowForbidden() = runTest {
        // Given: 申请记录存在，但 toUid 不是当前用户
        val req = FriendRejectReq.newBuilder()
            .setRequestId(42L)
            .build()

        coEvery { friendService.rejectFriendRequest(any<FriendRejectReq>(), any()) } throws FriendException(BizCode.FORBIDDEN)

        // When & Then: 应抛出 FriendException(FORBIDDEN)
        val ex = assertFailsWith<FriendException> {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
        }
        assertEquals(BizCode.FORBIDDEN, ex.bizCode)
    }
}
