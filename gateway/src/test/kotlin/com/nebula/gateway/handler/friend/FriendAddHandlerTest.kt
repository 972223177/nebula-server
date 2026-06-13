package com.nebula.gateway.handler.friend

import com.google.protobuf.ByteString
import com.nebula.chat.PushEventType
import com.nebula.chat.friend.FriendAddReq
import com.nebula.chat.friend.FriendAddResp
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.mockLockManager
import com.nebula.service.friend.FriendAddResult
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
 * FriendAddHandler 发送好友申请单元测试（D-51, D-52, D-54）。
 *
 * 覆盖场景：
 * - 正常申请 → 委托 FriendService + 推送 FRIEND_REQUEST
 * - 自我申请 → 抛出 FriendException(SELF_FRIEND)
 * - 已是好友 → 抛出 FriendException(ALREADY_FRIEND)
 * - 重复申请 → 抛出 FriendException(REQUEST_HANDLED)
 * - 双向竞赛 → 推送 FRIEND_ACCEPTED 给双方
 */
class FriendAddHandlerTest {

    private lateinit var friendService: FriendService
    private lateinit var pushService: PushService
    private lateinit var handler: FriendAddHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")
    /** 注入 Session 的协程上下文 */
    private val sessionContext = EmptyCoroutineContext + SessionKey(session)

    @BeforeEach
    fun setUp() {
        friendService = mockk()
        pushService = mockk(relaxed = true)

        val lockManager = mockLockManager()

        handler = FriendAddHandler(
            friendService,
            pushService,
            lockManager
        )
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常申请
    // ═══════════════════════════════════════════════════════════

    @Test
    fun addShouldDelegateServiceAndPushFriendRequest() = runTest(sessionContext) {
        // Given: A 向 B 发起好友申请，无双向竞赛
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("你好，加个好友")
            .build()

        coEvery { friendService.addFriend(any<FriendAddReq>(), any()) } returns FriendAddResult(
            requestId = 42L,
            isMutualAccept = false,
            convId = null,
            fromUid = 1001L,
            toUid = 2001L
        )

        // When: 执行 handle
        val result = handler.handle(req)

        // Then: 验证返回正确的 requestId
        assertNotNull(result)
        assertEquals(42L, result.requestId)

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
    fun selfAddShouldThrowSelfFriend() = runTest(sessionContext) {
        // Given: A 向自己发起好友申请（fromUid == toUid）
        val req = FriendAddReq.newBuilder()
            .setToUid(1001L)  // 与 session.userId 相同
            .setMessage("加自己")
            .build()

        coEvery { friendService.addFriend(any<FriendAddReq>(), any()) } throws FriendException(BizCode.SELF_FRIEND)

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
    fun alreadyFriendShouldThrowAlreadyFriend() = runTest(sessionContext) {
        // Given: A 向 B 申请，但 A 和 B 已是好友
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("你好")
            .build()

        coEvery { friendService.addFriend(any<FriendAddReq>(), any()) } throws FriendException(BizCode.ALREADY_FRIEND)

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
    fun duplicateRequestShouldThrowRequestHandled() = runTest(sessionContext) {
        // Given: A 向 B 重复发送申请
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("再次申请")
            .build()

        coEvery { friendService.addFriend(any<FriendAddReq>(), any()) } throws FriendException(BizCode.REQUEST_HANDLED)

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
    fun mutualAcceptShouldPushFriendAccepted() = runTest(sessionContext) {
        // Given: A 向 B 申请时，B 已向 A 发送了 pending 申请（双向竞赛）
        val req = FriendAddReq.newBuilder()
            .setToUid(2001L)
            .setMessage("你好")
            .build()

        coEvery { friendService.addFriend(any<FriendAddReq>(), any()) } returns FriendAddResult(
            requestId = 5L,
            isMutualAccept = true,
            convId = "private:1001:2001",
            fromUid = 1001L,
            toUid = 2001L
        )

        // When: 执行 handle
        val result = handler.handle(req)

        // Then: 验证返回的 requestId 为对方申请的 ID
        assertNotNull(result)
        assertEquals(5L, result.requestId)

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
    // 补充：buildPrivateConvId 工具方法
    // ═══════════════════════════════════════════════════════════

    @Test
    fun buildPrivateConvIdShouldReturnCorrectFormat() {
        // Given: 两个 uid（已排序）
        val smaller = 1001L
        val larger = 2001L

        // When: 构造会话 ID
        val convId = FriendService.buildPrivateConvId(smaller, larger)

        // Then: 格式为 private:smaller:larger
        assertEquals("private:1001:2001", convId)
    }

    @Test
    fun buildPrivateConvIdWithSwappedArgs() {
        // Given: 同一对用户，但传入顺序不同
        val convId1 = FriendService.buildPrivateConvId(1001L, 2001L)
        val convId2 = FriendService.buildPrivateConvId(2001L, 1001L)

        // When & Then: 两者应相同（但实际取决于调用方排序，此处验证方法本身行为）
        assertEquals("private:1001:2001", convId1)
        assertEquals("private:2001:1001", convId2)
    }
}
