package com.nebula.gateway.dispatcher

import com.nebula.chat.Response
import com.nebula.chat.user.GetPrivacyReq
import com.nebula.chat.user.GetPrivacyResp
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.user.GetPrivacyHandler
import com.nebula.gateway.handler.user.SetPrivacyHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.testutil.dispatchAs
import com.nebula.gateway.testutil.singleHandlerDispatcher
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.service.user.UserPrivacyService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Privacy 集成冒烟测试 — 精简版。
 *
 * 只保留端到端的 setPrivacy → getPrivacy 读写一致性验证测试，
 * 单个 Handler 冒烟测试已移至对应 HandlerTest 中覆盖。
 */
class PrivacySmokeTest {

    // ========== Mock 依赖 ==========

    private lateinit var userPrivacyService: UserPrivacyService
    private lateinit var onlineStatusRepo: OnlineStatusRepository
    private lateinit var pushService: PushService
    private lateinit var friendshipRepo: FriendshipRepository
    private lateinit var sessionRegistry: SessionRegistry

    /** 测试用户 */
    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        userPrivacyService = mockk()
        onlineStatusRepo = mockk()
        pushService = mockk(relaxed = true)
        friendshipRepo = mockk(relaxed = true)
        sessionRegistry = mockk()
    }

    // ===================================================================
    // 联合冒烟：setPrivacy → getPrivacy 验证读写一致性
    // ===================================================================

    @Test
    fun fullFlowShouldHideThenVerify() = runTest {
        // ---- 步骤 1: 设置隐藏 ----
        coEvery { userPrivacyService.setHideOnlineStatus(any(), any()) } returns Unit
        coEvery { onlineStatusRepo.setHidden(1001L) } returns Unit

        val setDispatcher = singleHandlerDispatcher(
            SetPrivacyHandler(userPrivacyService, onlineStatusRepo, pushService, friendshipRepo),
            SetPrivacyReq::class, Response::class
        )

        val setResp = setDispatcher.dispatchAs("user/setPrivacy",
            SetPrivacyReq.newBuilder().setHideOnlineStatus(true).build())
        assertEquals(BizCode.OK.code, setResp.code, "步骤1: 设置隐藏应返回 200")

        // ---- 步骤 2: 读取确认 ----
        coEvery { userPrivacyService.getHideOnlineStatus(any(), any()) } returns GetPrivacyResp.newBuilder().setHideOnlineStatus(true).build()

        val getDispatcher = singleHandlerDispatcher(
            GetPrivacyHandler(userPrivacyService),
            GetPrivacyReq::class, GetPrivacyResp::class
        )

        val getResp = getDispatcher.dispatchAs("user/getPrivacy",
            GetPrivacyReq.getDefaultInstance())

        assertEquals(BizCode.OK.code, getResp.code, "步骤2: 读取应返回 200")
        assertTrue(GetPrivacyResp.parseFrom(getResp.result).hideOnlineStatus, "步骤2: 应为隐藏状态")
    }

    @Test
    fun fullFlowShouldShowThenVerify() = runTest {
        // ---- 步骤 1: 设置可见 ----
        coEvery { userPrivacyService.setHideOnlineStatus(any(), any()) } returns Unit
        coEvery { onlineStatusRepo.setOnline(1001L) } returns Unit

        val setDispatcher = singleHandlerDispatcher(
            SetPrivacyHandler(userPrivacyService, onlineStatusRepo, pushService, friendshipRepo),
            SetPrivacyReq::class, Response::class
        )

        val setResp = setDispatcher.dispatchAs("user/setPrivacy",
            SetPrivacyReq.newBuilder().setHideOnlineStatus(false).build())
        assertEquals(BizCode.OK.code, setResp.code, "步骤1: 设置可见应返回 200")

        // ---- 步骤 2: 读取确认 ----
        coEvery { userPrivacyService.getHideOnlineStatus(any(), any()) } returns GetPrivacyResp.newBuilder().setHideOnlineStatus(false).build()

        val getDispatcher = singleHandlerDispatcher(
            GetPrivacyHandler(userPrivacyService),
            GetPrivacyReq::class, GetPrivacyResp::class
        )

        val getResp = getDispatcher.dispatchAs("user/getPrivacy",
            GetPrivacyReq.getDefaultInstance())

        assertEquals(BizCode.OK.code, getResp.code, "步骤2: 读取应返回 200")
        assertFalse(GetPrivacyResp.parseFrom(getResp.result).hideOnlineStatus, "步骤2: 应为可见状态")
    }
}
