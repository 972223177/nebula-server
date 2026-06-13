package com.nebula.gateway.dispatcher

import com.nebula.chat.Response
import com.nebula.chat.user.GetPrivacyReq
import com.nebula.chat.user.GetPrivacyResp
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.user.GetPrivacyHandler
import com.nebula.gateway.handler.user.SetPrivacyHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.testutil.buildTestDispatcher
import com.nebula.gateway.testutil.dispatchAs
import com.nebula.gateway.testutil.handlerEntry
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.service.user.UserPrivacyService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * Privacy 集成冒烟测试。
 *
 * 通过 Dispatcher 测试完整的 request→dispatch→response 链路，
 * 覆盖 SetPrivacyHandler 和 GetPrivacyHandler 两个 Handler 的端到端行为。
 *
 * 使用 [TestHelper] 提供的工具函数简化测试构建：
 * - [handlerEntry] 替代手写 ProtoCodec 逻辑
 * - [dispatchAs] 替代 `withContext + SessionKey + dispatch + requestEnvelope` 模式
 * - [buildTestDispatcher] 替代手写 Interceptor Pipeline
 */
class PrivacySmokeTest {

    // ========== Mock 依赖 ==========

    private lateinit var userPrivacyService: UserPrivacyService
    private lateinit var privacyRepo: PrivacyRepository
    private lateinit var onlineStatusRepo: OnlineStatusRepository
    private lateinit var pushService: PushService
    private lateinit var friendshipRepo: FriendshipRepository
    private lateinit var sessionRegistry: SessionRegistry

    /** 测试用户 */
    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        userPrivacyService = mockk()
        privacyRepo = mockk()
        onlineStatusRepo = mockk()
        pushService = mockk(relaxed = true)
        friendshipRepo = mockk(relaxed = true)
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
     * @return 配置好的 Dispatcher
     */
    private fun <Req : Any, Resp : Any> singleHandlerDispatcher(
        handler: Handler<Req, Resp>,
        reqClass: kotlin.reflect.KClass<Req>,
        respClass: kotlin.reflect.KClass<Resp>
    ) = buildTestDispatcher(
        HandlerRegistry().apply { register(handlerEntry(handler, reqClass, respClass)) },
        session = session, sessionRegistry = sessionRegistry
    )

    // ===================================================================
    // 1. user/setPrivacy — 设置在线状态可见性
    // ===================================================================

    @Test
    fun `setPrivacy - 设置隐藏状态返回200并同步更新状态`() = runTest {
        // Given
        coEvery { privacyRepo.setHideOnlineStatus(1001L, true) } returns Unit
        coEvery { onlineStatusRepo.setHidden(1001L) } returns Unit

        val dispatcher = singleHandlerDispatcher(
            SetPrivacyHandler(userPrivacyService, onlineStatusRepo, pushService, friendshipRepo),
            SetPrivacyReq::class, Response::class
        )

        // When
        val response = dispatcher.dispatchAs("user/setPrivacy",
            SetPrivacyReq.newBuilder().setHideOnlineStatus(true).build())

        // Then
        assertEquals(BizCode.OK.code, response.code, "设置隐藏状态应返回 200")
        assertEquals("user/setPrivacy", response.method, "method 应正确回显")
        coVerify(exactly = 1) { privacyRepo.setHideOnlineStatus(1001L, true) }
        coVerify(exactly = 1) { onlineStatusRepo.setHidden(1001L) }
    }

    @Test
    fun `setPrivacy - 设置可见返回200并同步更新状态`() = runTest {
        // Given
        coEvery { privacyRepo.setHideOnlineStatus(1001L, false) } returns Unit
        coEvery { onlineStatusRepo.setOnline(1001L) } returns Unit

        val dispatcher = singleHandlerDispatcher(
            SetPrivacyHandler(userPrivacyService, onlineStatusRepo, pushService, friendshipRepo),
            SetPrivacyReq::class, Response::class
        )

        // When
        val response = dispatcher.dispatchAs("user/setPrivacy",
            SetPrivacyReq.newBuilder().setHideOnlineStatus(false).build())

        // Then
        assertEquals(BizCode.OK.code, response.code, "设置可见应返回 200")
        coVerify(exactly = 1) { privacyRepo.setHideOnlineStatus(1001L, false) }
        coVerify(exactly = 1) { onlineStatusRepo.setOnline(1001L) }
    }

    // ===================================================================
    // 2. user/getPrivacy — 读取在线状态可见性
    // ===================================================================

    @Test
    fun `getPrivacy - 读取隐藏状态返回true`() = runTest {
        // Given
        coEvery { privacyRepo.getHideOnlineStatus(1001L) } returns true

        val dispatcher = singleHandlerDispatcher(
            GetPrivacyHandler(userPrivacyService),
            GetPrivacyReq::class, GetPrivacyResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/getPrivacy",
            GetPrivacyReq.getDefaultInstance())

        // Then
        assertEquals(BizCode.OK.code, response.code, "读取隐私设置应返回 200")
        val resp = GetPrivacyResp.parseFrom(response.result)
        assertEquals(true, resp.hideOnlineStatus, "隐藏状态应为 true")
    }

    @Test
    fun `getPrivacy - 读取可见状态返回false`() = runTest {
        // Given
        coEvery { privacyRepo.getHideOnlineStatus(1001L) } returns false

        val dispatcher = singleHandlerDispatcher(
            GetPrivacyHandler(userPrivacyService),
            GetPrivacyReq::class, GetPrivacyResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/getPrivacy",
            GetPrivacyReq.getDefaultInstance())

        // Then
        assertEquals(BizCode.OK.code, response.code, "读取隐私设置应返回 200")
        assertEquals(false, GetPrivacyResp.parseFrom(response.result).hideOnlineStatus, "可见状态应为 false")
    }

    @Test
    fun `getPrivacy - 默认状态返回false`() = runTest {
        // Given: 未设置过隐私，默认返回 false
        coEvery { privacyRepo.getHideOnlineStatus(1001L) } returns false

        val dispatcher = singleHandlerDispatcher(
            GetPrivacyHandler(userPrivacyService),
            GetPrivacyReq::class, GetPrivacyResp::class
        )

        // When
        val response = dispatcher.dispatchAs("user/getPrivacy",
            GetPrivacyReq.getDefaultInstance())

        // Then
        assertEquals(BizCode.OK.code, response.code, "默认状态应返回 200")
        assertEquals(false, GetPrivacyResp.parseFrom(response.result).hideOnlineStatus, "默认应为可见")
    }

    // ===================================================================
    // 3. 联合冒烟：setPrivacy → getPrivacy 验证读写一致性
    // ===================================================================

    @Test
    fun `完整流程 - 设置隐藏后读取确认`() = runTest {
        // ---- 步骤 1: 设置隐藏 ----
        coEvery { privacyRepo.setHideOnlineStatus(1001L, true) } returns Unit
        coEvery { onlineStatusRepo.setHidden(1001L) } returns Unit

        val setDispatcher = singleHandlerDispatcher(
            SetPrivacyHandler(userPrivacyService, onlineStatusRepo, pushService, friendshipRepo),
            SetPrivacyReq::class, Response::class
        )

        val setResp = setDispatcher.dispatchAs("user/setPrivacy",
            SetPrivacyReq.newBuilder().setHideOnlineStatus(true).build())
        assertEquals(BizCode.OK.code, setResp.code, "步骤1: 设置隐藏应返回 200")

        // ---- 步骤 2: 读取确认 ----
        coEvery { privacyRepo.getHideOnlineStatus(1001L) } returns true

        val getDispatcher = singleHandlerDispatcher(
            GetPrivacyHandler(userPrivacyService),
            GetPrivacyReq::class, GetPrivacyResp::class
        )

        val getResp = getDispatcher.dispatchAs("user/getPrivacy",
            GetPrivacyReq.getDefaultInstance())

        assertEquals(BizCode.OK.code, getResp.code, "步骤2: 读取应返回 200")
        assertEquals(true, GetPrivacyResp.parseFrom(getResp.result).hideOnlineStatus, "步骤2: 应为隐藏状态")
    }

    @Test
    fun `完整流程 - 设置可见后读取确认`() = runTest {
        // ---- 步骤 1: 设置可见 ----
        coEvery { privacyRepo.setHideOnlineStatus(1001L, false) } returns Unit
        coEvery { onlineStatusRepo.setOnline(1001L) } returns Unit

        val setDispatcher = singleHandlerDispatcher(
            SetPrivacyHandler(userPrivacyService, onlineStatusRepo, pushService, friendshipRepo),
            SetPrivacyReq::class, Response::class
        )

        val setResp = setDispatcher.dispatchAs("user/setPrivacy",
            SetPrivacyReq.newBuilder().setHideOnlineStatus(false).build())
        assertEquals(BizCode.OK.code, setResp.code, "步骤1: 设置可见应返回 200")

        // ---- 步骤 2: 读取确认 ----
        coEvery { privacyRepo.getHideOnlineStatus(1001L) } returns false

        val getDispatcher = singleHandlerDispatcher(
            GetPrivacyHandler(userPrivacyService),
            GetPrivacyReq::class, GetPrivacyResp::class
        )

        val getResp = getDispatcher.dispatchAs("user/getPrivacy",
            GetPrivacyReq.getDefaultInstance())

        assertEquals(BizCode.OK.code, getResp.code, "步骤2: 读取应返回 200")
        assertEquals(false, GetPrivacyResp.parseFrom(getResp.result).hideOnlineStatus, "步骤2: 应为可见状态")
    }
}
