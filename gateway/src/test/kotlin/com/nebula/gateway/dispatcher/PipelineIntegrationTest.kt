package com.nebula.gateway.dispatcher

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.chat.user.GetProfileReq
import com.nebula.chat.user.GetProfileResp
import com.nebula.chat.user.LoginReq
import com.nebula.chat.user.LoginResp
import com.nebula.chat.user.RegisterReq
import com.nebula.chat.user.RegisterResp
import com.nebula.chat.user.SearchUserReq
import com.nebula.chat.user.SearchUserResp
import com.nebula.chat.user.UserBrief
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.handler.user.GetProfileHandler
import com.nebula.gateway.handler.user.LoginHandler
import com.nebula.gateway.handler.user.RegisterHandler
import com.nebula.gateway.handler.user.SearchUserHandler
import com.nebula.gateway.interceptor.AuthInterceptor
import com.nebula.gateway.interceptor.ExceptionInterceptor
import com.nebula.gateway.interceptor.LogInterceptor
import com.nebula.gateway.interceptor.RateLimitInterceptor
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.redis.SessionRepository
import com.nebula.repository.repository.UserRepository
import com.nebula.service.user.UserService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.EntityTransaction
import java.util.Optional
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 全链路集成测试 — Dispatcher → Interceptor Pipeline → Handler 完整路径（D-24, D-26）。
 *
 * 覆盖场景：
 * - Phase 4: system/ping（无认证）+ test/authenticated（需认证注入 Session）
 * - Phase 5（Review 修复）：user/login 分发测试 + user/register/user/search/user/getProfile 分发
 *
 * Koin 加载 frameworkModule + handlerModule 模拟真实 DI 装配，用于 SessionRepository mock。
 */
class PipelineIntegrationTest : KoinTest {

    /** Mock SessionRepository — SessionRegistry 依赖此实例 */
    private val sessionRepo = mockk<SessionRepository>(relaxed = true)

    /** Koin 测试扩展 */
    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(
            module {
                single { sessionRepo }
            }
        )
    }

    /**
     * 需要认证的 Mock Handler，用于验证 AuthInterceptor 注入 Session 后 Handler 可正确获取。
     * method = "test/authenticated"
     */
    class MockAuthenticatedHandler : Handler<Request, Response> {
        override val method: String = "test/authenticated"

        override suspend fun handle(req: Request): Response {
            val session = currentCoroutineContext().requireSession()
            return Response.newBuilder()
                .setCode(200)
                .setMsg("authenticated: ${session.userId}")
                .setMethod(method)
                .build()
        }
    }

    @Test
    fun `full pipeline processes ping request`() = runTest {
        // 准备 HandlerRegistry 并注册 PingHandler
        val registry = HandlerRegistry()
        val pingHandler = PingHandler()
        val reqCodec = ProtoCodec.buildCodec(Request::class)
        val respCodec = ProtoCodec.buildCodec(Response::class)
        registry.register(
            HandlerEntry(
                handler = pingHandler,
                reqClass = Request::class,
                respClass = Response::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        // 构建 Interceptor Pipeline — 手动构造（D-07 顺序）
        val sessionRegistry = mockk<SessionRegistry>()
        val interceptors = listOf(
            AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping")),
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        // 执行 PingHandler 请求
        val request = Request.newBuilder().setMethod("system/ping").build()
        val response = dispatcher.dispatch(request)

        // 验证：ping 请求应返回 200
        // Dispatcher 将 Handler 返回值序列化到 response.result 中，外层 Response code=200
        assertEquals(200, response.code, "ping response code should be 200")

        // 反序列化 result bytes 验证内层 Response 包含 "pong"
        val innerResponse = Response.parseFrom(response.result.toByteArray())
        assertEquals("pong", innerResponse.msg, "inner response msg should be pong")
    }

    @Test
    fun `authenticated handler receives session via AuthInterceptor`() = runTest {
        // 准备 HandlerRegistry 并注册 MockAuthenticatedHandler
        val registry = HandlerRegistry()
        val handler = MockAuthenticatedHandler()
        val reqCodec = ProtoCodec.buildCodec(Request::class)
        val respCodec = ProtoCodec.buildCodec(Response::class)
        registry.register(
            HandlerEntry(
                handler = handler,
                reqClass = Request::class,
                respClass = Response::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        // Mock SessionRegistry — validate 返回固定 Session
        val sessionRegistry = mockk<SessionRegistry>()
        val testSession = Session(
            userId = 1L,
            token = "test-token",
            deviceType = "test",
            deviceId = "dev1",
            connectionId = "conn1"
        )
        coEvery { sessionRegistry.validate("test-token") } returns testSession

        // 自定义 AuthInterceptor，覆盖 extractToken 返回固定 token
        val customAuthInterceptor = object : AuthInterceptor(
            sessionRegistry,
            skipMethods = setOf("system/ping")
        ) {
            override fun extractToken(request: Request): String? = "test-token"
        }

        // 构建 Interceptor Pipeline
        val interceptors = listOf(
            customAuthInterceptor,
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        // 执行需认证的请求
        val request = Request.newBuilder().setMethod("test/authenticated").build()
        val response = dispatcher.dispatch(request)

        // 验证：认证通过，外层 Response code=200
        assertEquals(200, response.code, "authenticated response code should be 200")

        // 反序列化 result bytes 验证内层 Response 包含 userId from Session
        val innerResponse = Response.parseFrom(response.result.toByteArray())
        assertEquals("authenticated: 1", innerResponse.msg, "inner response msg should contain userId from Session")

        // 验证 SessionRegistry.validate() 被调用（AuthInterceptor 实际执行了认证）
        coVerify(exactly = 1) { sessionRegistry.validate("test-token") }
    }

    // ========== Phase 5 用户 Handler 集成测试 ==========

    @Test
    fun `login dispatch test - correct password returns token`() = runTest {
        // 准备 HandlerRegistry
        val registry = HandlerRegistry()
        val userService = mockk<UserService>()
        val sessionRegistry = mockk<SessionRegistry>()

        // 创建 LoginHandler
        val loginHandler = LoginHandler(userService, sessionRegistry)
        val reqCodec = ProtoCodec.buildCodec(LoginReq::class)
        val respCodec = ProtoCodec.buildCodec(LoginResp::class)
        registry.register(
            HandlerEntry(
                handler = loginHandler,
                reqClass = LoginReq::class,
                respClass = LoginResp::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        // Mock UserService — 返回存在的用户
        coEvery { userService.loginByPassword(any()) } returns 1001L

        // 构建 Interceptor Pipeline — user/login 在 skipMethods 中
        val interceptors = listOf(
            AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping", "user/login", "user/register")),
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        // 构建登录请求
        val loginReq = LoginReq.newBuilder()
            .setUsername("testuser")
            .setPassword("correct-password")
            .build()
        val envelopeRequest = Request.newBuilder()
            .setMethod("user/login")
            .setParams(loginReq.toByteString())
            .build()
        val response = dispatcher.dispatch(envelopeRequest)

        // 验证：外层 Response code=200
        assertEquals(200, response.code, "login response code should be 200")

        // 反序列化 LoginResp
        val loginResp = LoginResp.parseFrom(response.result.toByteArray())
        assertTrue(loginResp.token.isNotBlank(), "login response should contain token")
        assertEquals(1001L, loginResp.uid, "login response uid should match")
    }

    @Test
    fun `login dispatch test - wrong password returns non 200`() = runTest {
        // 准备 HandlerRegistry
        val registry = HandlerRegistry()
        val userService = mockk<UserService>()
        val sessionRegistry = mockk<SessionRegistry>()

        val loginHandler = LoginHandler(userService, sessionRegistry)
        val reqCodec = ProtoCodec.buildCodec(LoginReq::class)
        val respCodec = ProtoCodec.buildCodec(LoginResp::class)
        registry.register(
            HandlerEntry(
                handler = loginHandler,
                reqClass = LoginReq::class,
                respClass = LoginResp::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        coEvery { userService.loginByPassword(any()) } throws UserException(BizCode.AUTH_FAILED)

        val interceptors = listOf(
            AuthInterceptor(sessionRegistry, skipMethods = setOf("system/ping", "user/login", "user/register")),
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        val loginReq = LoginReq.newBuilder()
            .setUsername("testuser")
            .setPassword("wrong-password")
            .build()
        val envelopeRequest = Request.newBuilder()
            .setMethod("user/login")
            .setParams(loginReq.toByteString())
            .build()
        val response = dispatcher.dispatch(envelopeRequest)

        // 密码错误应通过 ExceptionInterceptor 返回业务错误码
        assertEquals(true, response.code != 200, "wrong password should return non-200 code")
    }

    @Test
    fun `register dispatch test - success returns uid`() = runTest {
        val registry = HandlerRegistry()
        val userService = mockk<UserService>()

        val registerHandler = RegisterHandler(userService)
        val reqCodec = ProtoCodec.buildCodec(RegisterReq::class)
        val respCodec = ProtoCodec.buildCodec(RegisterResp::class)
        registry.register(
            HandlerEntry(
                handler = registerHandler,
                reqClass = RegisterReq::class,
                respClass = RegisterResp::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        // Mock：注册成功返回 uid
        coEvery { userService.register(any()) } returns 2001L

        val interceptors = listOf(
            AuthInterceptor(mockk(), skipMethods = setOf("system/ping", "user/login", "user/register")),
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        val registerReq = RegisterReq.newBuilder()
            .setUsername("newuser")
            .setPassword("password123")
            .setNickname("新用户")
            .build()
        val envelopeRequest = Request.newBuilder()
            .setMethod("user/register")
            .setParams(registerReq.toByteString())
            .build()
        val response = dispatcher.dispatch(envelopeRequest)

        assertEquals(200, response.code, "register response code should be 200")

        val registerResp = RegisterResp.parseFrom(response.result.toByteArray())
        assertEquals(2001L, registerResp.uid, "register response uid should match generated id")
    }

    @Test
    fun `search dispatch test - keyword returns user list`() = runTest {
        val registry = HandlerRegistry()
        val userService = mockk<UserService>()

        val searchHandler = SearchUserHandler(userService)
        val reqCodec = ProtoCodec.buildCodec(SearchUserReq::class)
        val respCodec = ProtoCodec.buildCodec(SearchUserResp::class)
        registry.register(
            HandlerEntry(
                handler = searchHandler,
                reqClass = SearchUserReq::class,
                respClass = SearchUserResp::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        // Mock 搜索结果
        val matchedUser = UserBrief.newBuilder()
            .setUid(1001L)
            .setUsername("testuser")
            .setDisplayName("测试用户")
            .setAvatarUrl("https://example.com/avatar.jpg")
            .build()
        val searchResp = SearchUserResp.newBuilder()
            .addUsers(matchedUser)
            .build()
        coEvery { userService.searchUsers(any(), any(), any()) } returns searchResp

        // Mock SessionRegistry — 提供认证 session
        val sessionRegistry = mockk<SessionRegistry>()
        val testSession = Session(
            userId = 1001L,
            token = "test-token",
            deviceType = "test",
            deviceId = "dev1",
            connectionId = "conn1"
        )
        coEvery { sessionRegistry.validate("test-token") } returns testSession

        // 自定义 AuthInterceptor 提取固定 token
        val customAuthInterceptor = object : AuthInterceptor(
            sessionRegistry,
            skipMethods = setOf("system/ping", "user/login", "user/register")
        ) {
            override fun extractToken(request: Request): String? = "test-token"
        }

        val interceptors = listOf(
            customAuthInterceptor,
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        val searchReq = SearchUserReq.newBuilder()
            .setKeyword("test")
            .setCursor(0)
            .setLimit(20)
            .build()
        val envelopeRequest = Request.newBuilder()
            .setMethod("user/search")
            .setParams(searchReq.toByteString())
            .build()
        val response = dispatcher.dispatch(envelopeRequest)

        assertEquals(200, response.code, "search response code should be 200")

        val searchRespResult = SearchUserResp.parseFrom(response.result.toByteArray())
        assertEquals(1, searchRespResult.usersCount, "should return 1 user")
        val userBrief = searchRespResult.usersList[0]
        assertEquals("testuser", userBrief.username)
        assertEquals("测试用户", userBrief.displayName)
    }

    @Test
    fun `getProfile dispatch test - existing user returns profile`() = runTest {
        val registry = HandlerRegistry()
        val userService = mockk<UserService>()

        val profileHandler = GetProfileHandler(userService)
        val reqCodec = ProtoCodec.buildCodec(GetProfileReq::class)
        val respCodec = ProtoCodec.buildCodec(GetProfileResp::class)
        registry.register(
            HandlerEntry(
                handler = profileHandler,
                reqClass = GetProfileReq::class,
                respClass = GetProfileResp::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        // Mock 用户资料
        val profileResp = GetProfileResp.newBuilder()
            .setUid(1001L)
            .setUsername("testuser")
            .setDisplayName("测试用户")
            .setAvatarUrl("https://example.com/avatar.jpg")
            .build()
        coEvery { userService.getProfile(1001L) } returns profileResp

        // Mock SessionRegistry — 提供认证 session
        val sessionRegistry = mockk<SessionRegistry>()
        val testSession = Session(
            userId = 1001L,
            token = "test-token",
            deviceType = "test",
            deviceId = "dev1",
            connectionId = "conn1"
        )
        coEvery { sessionRegistry.validate("test-token") } returns testSession

        val customAuthInterceptor = object : AuthInterceptor(
            sessionRegistry,
            skipMethods = setOf("system/ping", "user/login", "user/register")
        ) {
            override fun extractToken(request: Request): String? = "test-token"
        }

        val interceptors = listOf(
            customAuthInterceptor,
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        val profileReq = GetProfileReq.newBuilder()
            .setUid(1001L)
            .build()
        val envelopeRequest = Request.newBuilder()
            .setMethod("user/getProfile")
            .setParams(profileReq.toByteString())
            .build()
        val response = dispatcher.dispatch(envelopeRequest)

        assertEquals(200, response.code, "getProfile response code should be 200")

        val profileRespResult = GetProfileResp.parseFrom(response.result.toByteArray())
        assertEquals("testuser", profileRespResult.username)
        assertEquals("测试用户", profileRespResult.displayName)
        assertEquals("https://example.com/avatar.jpg", profileRespResult.avatarUrl)
    }

    @Test
    fun `getProfile dispatch test - non existent user returns error`() = runTest {
        val registry = HandlerRegistry()
        val userService = mockk<UserService>()

        val profileHandler = GetProfileHandler(userService)
        val reqCodec = ProtoCodec.buildCodec(GetProfileReq::class)
        val respCodec = ProtoCodec.buildCodec(GetProfileResp::class)
        registry.register(
            HandlerEntry(
                handler = profileHandler,
                reqClass = GetProfileReq::class,
                respClass = GetProfileResp::class,
                parseFrom = reqCodec.parseFrom,
                toByteArray = respCodec.toByteArray
            )
        )

        coEvery { userService.getProfile(9999L) } throws UserException(BizCode.USER_NOT_FOUND)

        // Mock SessionRegistry — 提供认证 session
        val sessionRegistry = mockk<SessionRegistry>()
        val testSession = Session(
            userId = 1001L,
            token = "test-token",
            deviceType = "test",
            deviceId = "dev1",
            connectionId = "conn1"
        )
        coEvery { sessionRegistry.validate("test-token") } returns testSession

        val customAuthInterceptor = object : AuthInterceptor(
            sessionRegistry,
            skipMethods = setOf("system/ping", "user/login", "user/register")
        ) {
            override fun extractToken(request: Request): String? = "test-token"
        }

        val interceptors = listOf(
            customAuthInterceptor,
            LogInterceptor(),
            RateLimitInterceptor(),
            ExceptionInterceptor()
        )
        val dispatcher = Dispatcher(registry, interceptors)

        val profileReq = GetProfileReq.newBuilder().setUid(9999L).build()
        val envelopeRequest = Request.newBuilder()
            .setMethod("user/getProfile")
            .setParams(profileReq.toByteString())
            .build()
        val response = dispatcher.dispatch(envelopeRequest)

        // 用户不存在应返回非 200 错误码
        assertTrue(response.code != 200, "non-existent user profile should return non-200 code")
    }
}
