package com.nebula.gateway.handler.user

import com.nebula.chat.Response
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.service.friend.FriendService
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * SetPrivacyHandler 在线状态可见性设置 Handler 单元测试（BIZ-USER-05, D-57）。
 *
 * 覆盖场景：
 * - 设置隐藏在线状态：验证 setHideOnlineStatus + setHidden 被正确调用
 * - 设置可见：验证 setHideOnlineStatus(false) + setOnline 被正确调用
 *
 * Session 注入方式：使用 withContext(SessionKey(session)) 包裹 handle() 调用。
 */
class SetPrivacyHandlerTest {

    private lateinit var userPrivacyService: UserPrivacyService
    private lateinit var onlineStatusService: OnlineStatusService
    private lateinit var pushService: PushService
    private lateinit var friendService: FriendService
    private lateinit var handler: SetPrivacyHandler

    /** fire-and-forget 推送专用协程作用域 */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setup() {
        userPrivacyService = mockk()
        onlineStatusService = mockk()
        pushService = mockk()
        friendService = mockk(relaxed = true)
        handler = SetPrivacyHandler(userPrivacyService, onlineStatusService, pushService, friendService, scope)

        // UserPrivacyService 默认返回成功（handler 先委托 service，再执行自有逻辑）
        coEvery { userPrivacyService.setHideOnlineStatus(any(), any<SetPrivacyReq>()) } returns Unit
    }

    @Test
    fun setPrivacyShouldHideOnlineStatus() = runTest {
        coEvery { onlineStatusService.setHidden(1001L) } returns Unit

        val req = SetPrivacyReq.newBuilder().setHideOnlineStatus(true).build()
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        coVerify(exactly = 1) { userPrivacyService.setHideOnlineStatus(any(), any<SetPrivacyReq>()) }
        coVerify(exactly = 1) { onlineStatusService.setHidden(1001L) }
        assertEquals(BizCode.OK.code, result.code)
        assertEquals("user/setPrivacy", result.method)
    }

    @Test
    fun setPrivacyShouldShowOnlineStatus() = runTest {
        coEvery { onlineStatusService.setOnline(1001L) } returns Unit

        val req = SetPrivacyReq.newBuilder().setHideOnlineStatus(false).build()
        val result = withContext(SessionKey(session)) {
            handler.handle(req)
        }

        coVerify(exactly = 1) { userPrivacyService.setHideOnlineStatus(any(), any<SetPrivacyReq>()) }
        coVerify(exactly = 1) { onlineStatusService.setOnline(1001L) }
        assertEquals(BizCode.OK.code, result.code)
    }

    /**
     * 取消协程作用域，释放调度器线程，避免非守护线程阻止 JVM 退出。
     */
    @AfterEach
    fun tearDown() {
        scope.cancel()
    }
}
