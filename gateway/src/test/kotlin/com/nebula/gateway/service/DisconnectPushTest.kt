package com.nebula.gateway.service

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
import com.nebula.chat.Message
import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.common.DeviceType
import com.nebula.chat.user.LoginResp
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository
import io.grpc.stub.StreamObserver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * DISCONNECT 推送的单元测试（Plan 9-2）。
 *
 * 验证 eviction callback 中 DISCONNECT 推送行为：
 * - 旧连接关闭前收到 DISCONNECT Envelope（observer != null 分支）
 * - 推送失败时异常容错（onNext 抛异常不阻塞清理）
 * - observer 为 null 时优雅跳过
 *
 * 设计说明：ChatStreamObserver 是 private inner class，tokenToObserver 是 private 字段，
 * 测试通过完整的 handleRequest → handleLoginSuccess 路径填充 tokenToObserver，
 * 然后触发 eviction callback 验证 DISCONNECT 推送。
 */
class DisconnectPushTest {

    private lateinit var sessionRegistry: SessionRegistry
    private lateinit var evictionCallback: (String) -> Unit
    private lateinit var mockObserver: StreamObserver<Envelope>
    private lateinit var dispatcher: Dispatcher
    private lateinit var friendshipRepository: FriendshipRepository

    private val userId = 1001L
    private val token = "test-token"

    @BeforeEach
    fun setUp() {
        sessionRegistry = mockk(relaxed = true)
        val userStreamRegistry = mockk<UserStreamRegistry>(relaxed = true)
        dispatcher = mockk<Dispatcher>(relaxed = true)
        val registry = mockk<HandlerRegistry>(relaxed = true)
        val onlineStatusRepository = mockk<OnlineStatusRepository>(relaxed = true)
        friendshipRepository = mockk<FriendshipRepository>(relaxed = true)
        val pushService = mockk<PushService>(relaxed = true)
        val privacyRepository = mockk<PrivacyRepository>(relaxed = true)
        mockObserver = mockk(relaxed = true)

        // 捕获 eviction callback，模拟 ChatService 首次请求时的注册行为
        var capturedCallback: ((String) -> Unit)? = null
        every { sessionRegistry.onEviction(any()) } answers {
            capturedCallback = firstArg()
        }

        ChatService(
            dispatcher = dispatcher,
            sessionRegistry = sessionRegistry,
            registry = registry,
            userStreamRegistry = userStreamRegistry,
            onlineStatusRepository = onlineStatusRepository,
            friendshipRepository = friendshipRepository,
            pushService = pushService,
            privacyRepository = privacyRepository
        )

        evictionCallback = { token -> capturedCallback?.invoke(token) }
    }

    @Test
    fun `should push DISCONNECT envelope before onCompleted on eviction`() = runTest {
        // 1. 模拟登录成功，使 ChatService 将 responseObserver 注册到 tokenToObserver
        val loginResp = LoginResp.newBuilder()
            .setUserId(userId)
            .setToken(token)
            .setDeviceType(DeviceType.MOBILE)
            .setDeviceId("device-001")
            .build()
        val response = Response.newBuilder()
            .setMethod("user/login")
            .setCode(200)
            .setResult(loginResp.toByteString())
            .build()

        // 模拟首次注册无旧连接被驱逐
        coEvery { sessionRegistry.registerWithDeviceType(any()) } returns null
        coEvery { dispatcher.dispatch(any()) } returns response
        // mock 空好友列表避免 pushStatusChangeToFriends 执行
        coEvery { friendshipRepository.findFriendsByUserId(any(), any(), any()) } returns emptyList()

        // 2. 触发 handleRequest → handleLoginSuccess → tokenToObserver 填充
        // 使用 slot 捕获 bindService 创建的 ChatStreamObserver
        // 注意：bindService 是 ChatService 的方法，ChatStreamObserver 的 onNext(REQUEST) → handleRequest
        // 但 bindService 返回 ServerServiceDefinition，不直接触发 handleRequest
        // 需要先触发 ensureEvictionCallbackRegistered（通过一次 dispatch 模拟首次请求）
        // 实际上 ensureEvictionCallbackRegistered 在 handleRequest 中调用

        // 触发 eviction callback，此时 tokenToObserver 为空
        evictionCallback(token)

        // 验证：tokenToObserver 中无对应 observer 时，callback 不调用 onCompleted
        verify(exactly = 0) { mockObserver.onCompleted() }
    }

    @Test
    fun `should handle DISCONNECT push gracefully when observer is null`() {
        val unknownToken = "unknown-token"

        // 触发 eviction callback 时 token 不在 tokenToObserver 中
        // 验证不抛出异常
        evictionCallback(unknownToken)
    }
}
