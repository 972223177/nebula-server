package com.nebula.gateway.handler.friend

import com.nebula.chat.Response
import com.nebula.chat.friend.FriendDeleteReq
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
 * FriendDeleteHandler 删除好友 Handler 单元测试（软删除）。
 *
 * 覆盖场景：
 * - 正常删除 → 委托 FriendService 删除，返回 Response(OK)
 * - 好友不存在 → 抛出 FriendException(FRIEND_NOT_FOUND)
 * - 已删除（deleted == 1）→ 抛出 FriendException(FRIEND_NOT_FOUND)
 *
 * Session 注入方式：使用 withContext(SessionKey(session)) 包裹 handle() 调用。
 */
class FriendDeleteHandlerTest {

    private lateinit var friendService: FriendService
    private lateinit var handler: FriendDeleteHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        friendService = mockk()
        handler = FriendDeleteHandler(friendService)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 1：正常删除
    // ═══════════════════════════════════════════════════════════

    @Test
    fun deleteShouldDelegateServiceAndReturnOk() = runTest {
        // Given: 当前用户（1001L）与目标用户（2001L）存在正常的好友关系
        val req = FriendDeleteReq.newBuilder()
            .setUid(2001L)
            .build()

        coEvery { friendService.deleteFriend(any<FriendDeleteReq>(), any()) } returns Unit

        // When: 执行删除
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        // Then: 验证返回 OK 响应
        assertNotNull(result)
        assertEquals(BizCode.OK.code, result.code)
        assertEquals(BizCode.OK.msg, result.msg)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 2：好友不存在
    // ═══════════════════════════════════════════════════════════

    @Test
    fun deleteFriendNotFoundShouldThrowFriendNotFound() = runTest {
        // Given: 当前用户与目标用户之间不存在好友关系记录
        val req = FriendDeleteReq.newBuilder()
            .setUid(2001L)
            .build()

        coEvery { friendService.deleteFriend(any<FriendDeleteReq>(), any()) } throws FriendException(BizCode.FRIEND_NOT_FOUND)

        // When & Then: 应抛出 FriendException(FRIEND_NOT_FOUND)
        val ex = assertFailsWith<FriendException> {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
        }
        assertEquals(BizCode.FRIEND_NOT_FOUND, ex.bizCode)
    }

    // ═══════════════════════════════════════════════════════════
    // 场景 3：已删除
    // ═══════════════════════════════════════════════════════════

    @Test
    fun alreadyDeletedShouldThrowFriendNotFound() = runTest {
        // Given: 好友关系记录存在但已软删除（deleted=1）
        val req = FriendDeleteReq.newBuilder()
            .setUid(2001L)
            .build()

        coEvery { friendService.deleteFriend(any<FriendDeleteReq>(), any()) } throws FriendException(BizCode.FRIEND_NOT_FOUND)

        // When & Then: 应抛出 FriendException(FRIEND_NOT_FOUND)
        val ex = assertFailsWith<FriendException> {
            withContext(SessionKey(session)) {
                handler.handle(req)
            }
        }
        assertEquals(BizCode.FRIEND_NOT_FOUND, ex.bizCode)
    }
}
