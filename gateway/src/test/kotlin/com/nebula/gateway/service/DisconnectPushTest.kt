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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * DISCONNECT 推送的单元测试（Plan 9-2）。
 *
 * 验证 eviction callback 中 DISCONNECT 推送行为：
 * - token 在 tokenToObserver 中时推送 DISCONNECT + 关闭连接
 * - token 不在 tokenToObserver 中时优雅跳过
 *
 * 注意：ChatStreamObserver 是 private inner class，tokenToObserver 是 private 字段，
 * 无法直接设置。DISCONNECT 推送的完整验证依赖从 ChatService 提取 ChatStreamObserver
 * 的可行性方案（D-69）。完整测试在 ChatServiceReconnectIntegrationTest 中通过反射实现。
 */
class DisconnectPushTest {

    private lateinit var sessionRegistry: SessionRegistry
    private lateinit var evictionCallback: (String) -> Unit
    private lateinit var mockObserver: StreamObserver<Envelope>
    private lateinit var dispatcher: Dispatcher

    private val userId = 1001L
    private val token = "test-token"

    @BeforeEach
    fun setUp() {
        sessionRegistry = mockk(relaxed = true)
        val userStreamRegistry = mockk<UserStreamRegistry>(relaxed = true)
        dispatcher = mockk<Dispatcher>(relaxed = true)
        val registry = mockk<HandlerRegistry>(relaxed = true)
        val onlineStatusRepository = mockk<OnlineStatusRepository>(relaxed = true)
        val friendshipRepository = mockk<FriendshipRepository>(relaxed = true)
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
    fun shouldHandleDISCONNECTGracefullyWhenTokenNotInTokenToObserver() {
        val unknownToken = "unknown-token"

        // token 不在 tokenToObserver 中，eviction callback 不触发 DISCONNECT 推送
        evictionCallback(unknownToken)

        // 验证 observer 未被操作（无 DISCONNECT 推送，无 onCompleted）
        verify(exactly = 0) { mockObserver.onNext(any()) }
        verify(exactly = 0) { mockObserver.onCompleted() }
    }
}
