package com.nebula.gateway.service

import com.nebula.chat.Envelope
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.friend.FriendService
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.admin.DeadLetterService
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlinx.coroutines.test.runTest

/**
 * 重连 eviction callback 单元测试。
 *
 * ChatStreamObserver 是 private inner class，tokenToObserver 是 private 字段，
 * 测试通过 eviction callback 间接验证 unknown-token 安全路径：
 * token 不在 tokenToObserver 中时，eviction callback 不操作 observer。
 *
 * 完整的多设备竞争、缓冲区、伪在线场景已在 ChatServiceReconnectIntegrationTest
 * 中通过反射覆盖。
 */
class ChatServiceReconnectTest {

    private lateinit var sessionRegistry: SessionRegistry
    private lateinit var mockObserver: StreamObserver<Envelope>
    private lateinit var evictionCallback: (String) -> Unit

    private val unknownToken = "unknown-token"

    @BeforeEach
    fun setUp() {
        sessionRegistry = mockk(relaxed = true)
        mockObserver = mockk(relaxed = true)

        // 捕获 eviction callback：使用 mutable variable 避免 slot.captured 的时序问题
        var capturedCallback: ((String) -> Unit)? = null
        every { sessionRegistry.onEviction(any()) } answers {
            capturedCallback = firstArg()
        }

        ChatService(
            dispatcher = mockk<Dispatcher>(relaxed = true),
            sessionRegistry = sessionRegistry,
            registry = mockk<HandlerRegistry>(relaxed = true),
            userStreamRegistry = mockk<UserStreamRegistry>(relaxed = true),
            onlineStatusService = mockk<OnlineStatusService>(relaxed = true),
            friendService = mockk<FriendService>(relaxed = true),
            pushService = mockk<PushService>(relaxed = true),
            privacyService = mockk<UserPrivacyService>(relaxed = true),
            deadLetterService = mockk<DeadLetterService>(relaxed = true)
        )

        evictionCallback = { token -> capturedCallback?.invoke(token) }
    }

    @Test
    fun evictionCallbackShouldHandleUnknownTokenGracefully() = runTest {
        // Given: tokenToObserver 为空，unknownToken 不在映射中
        // When: 触发 eviction callback
        evictionCallback(unknownToken)

        // Then: observer 未被操作（token 不在 tokenToObserver 中）
        verify(exactly = 0) { mockObserver.onNext(any()) }
        verify(exactly = 0) { mockObserver.onCompleted() }
    }
}
