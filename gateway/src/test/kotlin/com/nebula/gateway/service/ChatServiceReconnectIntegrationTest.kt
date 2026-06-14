package com.nebula.gateway.service

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
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
import com.nebula.service.admin.DeadLetterService
import io.grpc.stub.StreamObserver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.suspendCoroutine

/**
 * ChatStreamObserver 内部方法集成测试（Plan 9-4 补充）。
 *
 * ChatStreamObserver 是 ChatService 的 private inner class，其内部方法（deliver、activateDelivery、
 * cleanupPending、cleanupConnection）以及 tokenToObserver 字段无法从测试代码直接访问。
 * 本测试使用 JVM 反射构造 ChatStreamObserver 实例并调用其私有方法，以验证每个分支的完整行为。
 *
 * 设计决策：
 * - 使用反射而非修改生产代码，保持 ChatStreamObserver 的封装性（D-69 为未来重构建议）
 * - 每个测试方法覆盖一个明确的分支条件，遵循 Given-When-Then 模式
 * - 使用 MockK verify 做断言验证
 */
class ChatServiceReconnectIntegrationTest {

    // ChatService 依赖 — 全部使用 mock
    private lateinit var sessionRegistry: SessionRegistry
    private lateinit var userStreamRegistry: UserStreamRegistry
    private lateinit var dispatcher: Dispatcher
    private lateinit var onlineStatusRepository: OnlineStatusRepository
    private lateinit var friendshipRepository: FriendshipRepository
    private lateinit var pushService: PushService
    private lateinit var privacyRepository: PrivacyRepository
    private lateinit var deadLetterService: DeadLetterService
    private lateinit var mockResponseObserver: StreamObserver<Envelope>
    private lateinit var chatService: ChatService

    // 反射相关
    private lateinit var chatStreamObserverClass: Class<*>
    private lateinit var evictionCallback: (String) -> Unit

    /** 反射获取 private 字段值 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> getField(obj: Any, fieldName: String): T {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName).apply { isAccessible = true }
                return field.get(obj) as T
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("Field '$fieldName' not found in ${obj.javaClass.name}")
    }

    /** 反射设置 private 字段值 */
    private fun setField(obj: Any, fieldName: String, value: Any) {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val field = clazz.getDeclaredField(fieldName).apply { isAccessible = true }
                field.set(obj, value)
                return
            } catch (_: NoSuchFieldException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchFieldException("Field '$fieldName' not found in ${obj.javaClass.name}")
    }

    /** 反射调用 private 方法（无参数） */
    private fun invokeMethod(obj: Any, methodName: String) {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                val method = clazz.getDeclaredMethod(methodName).apply { isAccessible = true }
                method.invoke(obj)
                return
            } catch (_: NoSuchMethodException) {
                clazz = clazz.superclass
            }
        }
        throw NoSuchMethodException("Method '$methodName' not found in ${obj.javaClass.name}")
    }

    /** 反射调用 private 方法（单参数，自动匹配参数类型） */
    private fun invokeMethod(obj: Any, methodName: String, arg: Any) {
        var clazz: Class<*>? = obj.javaClass
        while (clazz != null) {
            try {
                // 尝试所有 declared methods 匹配方法名和参数类型
                val method = clazz.declaredMethods.firstOrNull { m ->
                    m.name == methodName && m.parameterCount == 1 &&
                        m.parameterTypes[0].isAssignableFrom(arg.javaClass)
                }
                if (method != null) {
                    method.isAccessible = true
                    method.invoke(obj, arg)
                    return
                }
            } catch (_: Exception) {
                // 继续搜索
            }
            clazz = clazz.superclass
        }
        throw NoSuchMethodException("Method '$methodName' with arg type ${arg.javaClass.name} not found in ${obj.javaClass.name}")
    }

    /**
     * 通过反射调用 ChatStreamObserver 的 suspend 方法 activateDelivery()。
     * 使用 CountDownLatch 同步等待协程完成，避免手动管理 Continuation。
     */
    private fun callActivateDelivery(observer: Any) {
        val latch = CountDownLatch(1)
        // 创建自定义 Continuation，当协程完成时释放 latch
        val continuation = object : Continuation<Unit> {
            override val context: CoroutineContext get() = EmptyCoroutineContext
            override fun resumeWith(result: Result<Unit>) {
                latch.countDown()
            }
        }
        try {
            val method = chatStreamObserverClass.getDeclaredMethod(
                "activateDelivery",
                Continuation::class.java
            ).apply { isAccessible = true }
            method.invoke(observer, continuation)
            // 等待协程完成（超时 5s）
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw RuntimeException("activateDelivery timed out after 5 seconds")
            }
        } catch (e: Exception) {
            if (e is RuntimeException && e.message?.contains("timed out") == true) throw e
            // 如果反射调用本身抛异常（非协程相关），则重新抛出
            throw e
        }
    }

    /** 反射创建 ChatStreamObserver 实例 */
    private fun createChatStreamObserver(responseObserver: StreamObserver<Envelope>): Any {
        val constructor = chatStreamObserverClass.getDeclaredConstructor(
            ChatService::class.java, StreamObserver::class.java
        ).apply { isAccessible = true }
        return constructor.newInstance(chatService, responseObserver)
    }

    /** 反射调用 handleLoginSuccess（suspend 方法），在 runBlocking 中执行 */
    private fun callHandleLoginSuccess(observer: Any, response: Response) {
        runBlocking {
            // 在协程上下文中，通过反射调用 handleLoginSuccess
            // suspend 函数的 JVM 签名：handleLoginSuccess(Response, StreamObserver, Continuation) -> Object
            val method = ChatService::class.java.getDeclaredMethod(
                "handleLoginSuccess",
                Response::class.java,
                StreamObserver::class.java,
                Continuation::class.java
            ).apply { isAccessible = true }
            // 使用 kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
            // 或直接通过 runBlocking 的协程作用域调用
            @Suppress("UNCHECKED_CAST")
            val result = suspendCoroutine<Any?> { cont ->
                method.invoke(chatService, response, observer, cont)
            }
        }
    }

    /** 反射调用 ensureEvictionCallbackRegistered */
    private fun ensureEvictionRegistered() {
        val method = ChatService::class.java.getDeclaredMethod("ensureEvictionCallbackRegistered")
            .apply { isAccessible = true }
        method.invoke(chatService)
    }

    @BeforeEach
    fun setUp() {
        sessionRegistry = mockk(relaxed = true)
        userStreamRegistry = mockk<UserStreamRegistry>(relaxed = true)
        dispatcher = mockk<Dispatcher>(relaxed = true)
        val registry = mockk<HandlerRegistry>(relaxed = true)
        onlineStatusRepository = mockk<OnlineStatusRepository>(relaxed = true)
        friendshipRepository = mockk<FriendshipRepository>(relaxed = true)
        pushService = mockk<PushService>(relaxed = true)
        privacyRepository = mockk<PrivacyRepository>(relaxed = true)
        deadLetterService = mockk<DeadLetterService>(relaxed = true)
        mockResponseObserver = mockk(relaxed = true)

        // 捕获 eviction callback
        var capturedCallback: ((String) -> Unit)? = null
        every { sessionRegistry.onEviction(any()) } answers {
            capturedCallback = firstArg()
        }

        chatService = ChatService(
            dispatcher = dispatcher,
            sessionRegistry = sessionRegistry,
            registry = registry,
            userStreamRegistry = userStreamRegistry,
            onlineStatusRepository = onlineStatusRepository,
            friendshipRepository = friendshipRepository,
            pushService = pushService,
            privacyRepository = privacyRepository,
            deadLetterService = deadLetterService
        )

        // 初始化反射
        chatStreamObserverClass = ChatService::class.java.declaredClasses
            .first { it.simpleName == "ChatStreamObserver" }

        evictionCallback = { token -> capturedCallback?.invoke(token) }
    }

    // ==================== 第 1 组：deliver() 测试 ====================

    @Test
    fun deliverShouldDirectlyOnNextWhenDeliveryIsActive() {
        // Given: 创建 ChatStreamObserver 实例，deliveryActive = true
        val observer = createChatStreamObserver(mockResponseObserver)
        setField(observer, "deliveryActive", true)
        val envelope = Envelope.getDefaultInstance()

        // When: 调用 deliver(envelope)
        invokeMethod(observer, "deliver", envelope)

        // Then: responseObserver.onNext 被调用
        verify(exactly = 1) { mockResponseObserver.onNext(envelope) }
    }

    @Test
    fun deliverShouldBufferToPendingBufferWhenDeliveryIsNotActive() {
        // Given: 创建 ChatStreamObserver 实例，deliveryActive = false（默认）
        val observer = createChatStreamObserver(mockResponseObserver)
        val envelope = Envelope.getDefaultInstance()

        // When: 调用 deliver(envelope)
        invokeMethod(observer, "deliver", envelope)

        // Then: pendingBuffer 包含该 envelope，responseObserver.onNext 未被调用
        val pendingBuffer: ConcurrentLinkedQueue<Envelope> = getField(observer, "pendingBuffer")
        assert(pendingBuffer.size == 1) { "Expected pendingBuffer size 1, got ${pendingBuffer.size}" }
        assert(pendingBuffer.peek() == envelope) { "Expected envelope in pendingBuffer" }
        verify(exactly = 0) { mockResponseObserver.onNext(any()) }
    }

    @Test
    fun deliverShouldDropOldestMessageWhenBufferExceeds1000Limit() {
        // Given: pendingBuffer 已有 1000 条消息
        val observer = createChatStreamObserver(mockResponseObserver)
        val pendingBuffer: ConcurrentLinkedQueue<Envelope> = getField(observer, "pendingBuffer")
        val firstEnvelope = Envelope.newBuilder().setRequestId("first").build()
        pendingBuffer.add(firstEnvelope)
        for (i in 1 until 1000) {
            pendingBuffer.add(Envelope.newBuilder().setRequestId("msg-$i").build())
        }
        assert(pendingBuffer.size == 1000) { "Expected 1000 messages in buffer" }

        val newEnvelope = Envelope.newBuilder().setRequestId("new").build()

        // When: 调用 deliver(newEnvelope)
        invokeMethod(observer, "deliver", newEnvelope)

        // Then: pendingBuffer 仍为 1000 条，最旧消息（first）被 poll
        assert(pendingBuffer.size == 1000) { "Expected buffer size 1000, got ${pendingBuffer.size}" }
        val head = requireNotNull(pendingBuffer.peek())
        assert(head.requestId != "first") { "Expected oldest message to be dropped" }
        assert(pendingBuffer.any { it.requestId == "new" }) { "Expected new message in buffer" }
        verify(exactly = 0) { mockResponseObserver.onNext(any()) }
    }

    // ==================== 第 2 组：activateDelivery() 测试 ====================

    @Test
    fun activateDeliveryShouldDeliverAllBufferedMessagesThenSetActive() = runTest {
        // Given: pendingBuffer 有 3 条缓存消息
        val observer = createChatStreamObserver(mockResponseObserver)
        val pendingBuffer: ConcurrentLinkedQueue<Envelope> = getField(observer, "pendingBuffer")
        val env1 = Envelope.newBuilder().setRequestId("1").build()
        val env2 = Envelope.newBuilder().setRequestId("2").build()
        val env3 = Envelope.newBuilder().setRequestId("3").build()
        pendingBuffer.add(env1)
        pendingBuffer.add(env2)
        pendingBuffer.add(env3)

        // When: 调用 activateDelivery()
        callActivateDelivery(observer)

        // Then: 3 条消息按序投递
        verify(exactly = 1) { mockResponseObserver.onNext(env1) }
        verify(exactly = 1) { mockResponseObserver.onNext(env2) }
        verify(exactly = 1) { mockResponseObserver.onNext(env3) }
        // pendingBuffer 为空
        assert(pendingBuffer.isEmpty()) { "Expected pendingBuffer to be empty after activation" }
        // deliveryActive = true
        val deliveryActive: Boolean = getField(observer, "deliveryActive")
        assert(deliveryActive) { "Expected deliveryActive to be true after activation" }
    }

    @Test
    fun activateDeliveryShouldContinueDeliveryOnIndividualMessageFailure() = runTest {
        // Given: pendingBuffer 有 3 条消息，第 2 条 onNext 抛异常
        val observer = createChatStreamObserver(mockResponseObserver)
        val pendingBuffer: ConcurrentLinkedQueue<Envelope> = getField(observer, "pendingBuffer")
        val env1 = Envelope.newBuilder().setRequestId("1").build()
        val env2 = Envelope.newBuilder().setRequestId("2").build()
        val env3 = Envelope.newBuilder().setRequestId("3").build()
        pendingBuffer.add(env1)
        pendingBuffer.add(env2)
        pendingBuffer.add(env3)

        // 第 2 条消息投递时抛异常
        every { mockResponseObserver.onNext(env2) } throws RuntimeException("Delivery failed")
        // 第 1 条和第 3 条正常
        every { mockResponseObserver.onNext(env1) } just runs
        every { mockResponseObserver.onNext(env3) } just runs

        // When: 调用 activateDelivery()
        callActivateDelivery(observer)

        // Then: 第 1 条投递成功，第 2 条异常被捕获，第 3 条投递成功
        verify(exactly = 1) { mockResponseObserver.onNext(env1) }
        verify(exactly = 1) { mockResponseObserver.onNext(env2) }
        verify(exactly = 1) { mockResponseObserver.onNext(env3) }
        // deliveryActive = true（异常不阻止标记激活）
        val deliveryActive: Boolean = getField(observer, "deliveryActive")
        assert(deliveryActive) { "Expected deliveryActive to be true despite delivery failure" }
    }

    // ==================== 第 3 组：cleanupConnection() 测试 ====================

    @Test
    fun cleanupConnectionShouldRemoveObserverFromTokenToObserver() {
        // Given: tokenToObserver 中包含 responseObserver
        val observer = createChatStreamObserver(mockResponseObserver)
        val tokenToObserver: ConcurrentHashMap<String, StreamObserver<Envelope>> =
            getField(chatService, "tokenToObserver")
        tokenToObserver["test-token"] = mockResponseObserver
        assert(tokenToObserver.values.contains(mockResponseObserver)) { "Expected observer in tokenToObserver" }

        // When: 调用 cleanupConnection()
        invokeMethod(observer, "cleanupConnection")

        // Then: tokenToObserver.values 不再包含 responseObserver
        assert(!tokenToObserver.values.contains(mockResponseObserver)) {
            "Expected observer removed from tokenToObserver"
        }
    }

    @Test
    fun cleanupConnectionShouldRemoveFromUserStreamRegistryOnlyWhenObserverPresent() {
        // Given: userStreamRegistry.getStreams 包含 responseObserver
        val observer = createChatStreamObserver(mockResponseObserver)
        setField(observer, "userId", 1001L)
        every { userStreamRegistry.getStreams(1001L) } returns listOf(mockResponseObserver)

        // When: 调用 cleanupConnection()
        invokeMethod(observer, "cleanupConnection")

        // Then: userStreamRegistry.removeStream 被调用
        verify(exactly = 1) { userStreamRegistry.removeStream(1001L, mockResponseObserver) }
    }

    @Test
    fun cleanupConnectionShouldNOTRemoveFromUserStreamRegistryWhenObserverNotPresent() {
        // Given: userStreamRegistry.getStreams 不包含 responseObserver（被新连接替换）
        val observer = createChatStreamObserver(mockResponseObserver)
        setField(observer, "userId", 1001L)
        every { userStreamRegistry.getStreams(1001L) } returns emptyList()

        // When: 调用 cleanupConnection()
        invokeMethod(observer, "cleanupConnection")

        // Then: userStreamRegistry.removeStream 未被调用（防御性检查生效）
        verify(exactly = 0) { userStreamRegistry.removeStream(any(), any()) }
    }

    @Test
    fun cleanupConnectionShouldStartDelayedOfflineTaskWhenNoOtherDevices() {
        // Given: userStreamRegistry.getStreams(uid) 返回空列表
        val observer = createChatStreamObserver(mockResponseObserver)
        setField(observer, "userId", 1001L)
        every { userStreamRegistry.getStreams(1001L) } returns emptyList()

        // When: 调用 cleanupConnection()
        invokeMethod(observer, "cleanupConnection")

        // Then: delayedOfflineJob != null
        val delayedOfflineJob: Job? = getField<Job?>(observer, "delayedOfflineJob")
        assert(delayedOfflineJob != null) { "Expected delayedOfflineJob to be set" }
        val job = requireNotNull(delayedOfflineJob)
        assert(!job.isCompleted) { "Expected delayedOfflineJob to be active" }
    }

    @Test
    fun cleanupConnectionShouldSkipDelayedOfflineTaskWhenUserIdIsNull() {
        // Given: userId = null（连接未登录即关闭）
        val observer = createChatStreamObserver(mockResponseObserver)

        // When: 调用 cleanupConnection()
        invokeMethod(observer, "cleanupConnection")

        // Then: delayedOfflineJob 为 null
        val delayedOfflineJob: Job? = getField<Job?>(observer, "delayedOfflineJob")
        assert(delayedOfflineJob == null) { "Expected no delayedOfflineJob when userId is null" }
    }

    // ==================== 第 4 组：onCompleted/onError 生命周期测试 ====================

    @Test
    fun onCompletedShouldCleanupPendingBufferAndConnection() {
        // Given: ChatStreamObserver 实例，pendingBuffer 有缓存消息
        val observer = createChatStreamObserver(mockResponseObserver)
        val pendingBuffer: ConcurrentLinkedQueue<Envelope> = getField(observer, "pendingBuffer")
        pendingBuffer.add(Envelope.getDefaultInstance())
        assert(pendingBuffer.isNotEmpty()) { "Expected pendingBuffer to have messages" }

        // When: 调用 onCompleted()
        invokeMethod(observer, "onCompleted")

        // Then: pendingBuffer 为空 + responseObserver.onCompleted() 被调用
        assert(pendingBuffer.isEmpty()) { "Expected pendingBuffer to be empty after onCompleted" }
        verify(exactly = 1) { mockResponseObserver.onCompleted() }
    }

    @Test
    fun onErrorShouldCleanupPendingBufferAndConnection() {
        // Given: ChatStreamObserver 实例，pendingBuffer 有缓存消息
        val observer = createChatStreamObserver(mockResponseObserver)
        val pendingBuffer: ConcurrentLinkedQueue<Envelope> = getField(observer, "pendingBuffer")
        pendingBuffer.add(Envelope.getDefaultInstance())
        assert(pendingBuffer.isNotEmpty()) { "Expected pendingBuffer to have messages" }

        val error = RuntimeException("Connection lost")

        // When: 调用 onError(t) — Throwable 类型
        invokeMethod(observer, "onError", error as Throwable)

        // Then: pendingBuffer 为空 + responseObserver.onError() 被调用
        assert(pendingBuffer.isEmpty()) { "Expected pendingBuffer to be empty after onError" }
        verify(exactly = 1) { mockResponseObserver.onError(error) }
    }

    // ==================== 第 5 组：handleLoginSuccess 分支测试 ====================

    @Test
    fun handleLoginSuccessShouldSetDeliveryActiveWhenNoEvictedToken() = runBlocking {
        // Given: 创建 ChatStreamObserver，首次注册无旧连接被驱逐
        val observer = createChatStreamObserver(mockResponseObserver)
        coEvery { sessionRegistry.registerWithDeviceType(any()) } returns null
        coEvery { friendshipRepository.findFriendsByUserId(any(), any(), any()) } returns emptyList()
        coEvery { onlineStatusRepository.setOnline(any()) } returns Unit

        val response = buildLoginResponse()

        // When: 通过反射调用 handleLoginSuccess（传入 ChatStreamObserver 实例作为 responseObserver）
        callHandleLoginSuccess(observer, response)

        // Then: deliveryActive = true（首次登录直接激活）
        val deliveryActive: Boolean = getField(observer, "deliveryActive")
        assert(deliveryActive) { "Expected deliveryActive to be true after first login" }
    }

    @Test
    fun handleLoginSuccessShouldActivateDeliveryWhenEvictedTokenExists() = runBlocking {
        // Given: 创建 ChatStreamObserver，有旧连接被驱逐
        val oldToken = "old-token"
        val observer = createChatStreamObserver(mockResponseObserver)
        coEvery { sessionRegistry.registerWithDeviceType(any()) } returns oldToken
        coEvery { friendshipRepository.findFriendsByUserId(any(), any(), any()) } returns emptyList()
        coEvery { onlineStatusRepository.setOnline(any()) } returns Unit

        val response = buildLoginResponse()

        // When: 通过反射调用 handleLoginSuccess（传入 ChatStreamObserver 实例作为 responseObserver）
        callHandleLoginSuccess(observer, response)

        // Then: deliveryActive = true（evictedToken 不为 null 时调用 activateDelivery）
        val deliveryActive: Boolean = getField(observer, "deliveryActive")
        assert(deliveryActive) { "Expected deliveryActive to be true after reconnection" }
    }

    // ==================== 第 6 组：tokenToObserver eviction 测试 ====================

    @Test
    fun evictionCallbackShouldRemoveObserverAndPushDISCONNECTWhenTokenMatches() {
        // Given: 确保 eviction callback 已注册 + tokenToObserver 包含 token→observer 映射
        ensureEvictionRegistered()
        val evictedObserver = mockk<StreamObserver<Envelope>>(relaxed = true)
        val tokenToObserver: ConcurrentHashMap<String, StreamObserver<Envelope>> =
            getField(chatService, "tokenToObserver")
        tokenToObserver["target-token"] = evictedObserver

        // When: 触发 eviction callback（匹配的 token）
        evictionCallback("target-token")

        // Then:
        // 1. tokenToObserver 中已移除该 token
        assert(!tokenToObserver.containsKey("target-token")) {
            "Expected token removed from tokenToObserver"
        }
        // 2. DISCONNECT Envelope 被发送到旧 observer
        val disconnectSlot = slot<Envelope>()
        verify(exactly = 1) { evictedObserver.onNext(capture(disconnectSlot)) }
        assert(disconnectSlot.captured.direction == Direction.PUSH) {
            "Expected PUSH direction for DISCONNECT"
        }
        assert(disconnectSlot.captured.message.eventType == PushEventType.DISCONNECT) {
            "Expected DISCONNECT event type"
        }
        // 3. onCompleted 被调用（连接关闭）
        verify(exactly = 1) { evictedObserver.onCompleted() }
    }

    @Test
    fun evictionCallbackShouldSkipWhenTokenNotInTokenToObserver() {
        // Given: 确保 eviction callback 已注册 + tokenToObserver 为空
        ensureEvictionRegistered()
        val evictedObserver = mockk<StreamObserver<Envelope>>(relaxed = true)

        // When: 触发 eviction callback（不存在的 token）
        evictionCallback("unknown-token")

        // Then: 没有任何 observer 被操作
        verify(exactly = 0) { evictedObserver.onNext(any()) }
        verify(exactly = 0) { evictedObserver.onCompleted() }
    }

    // ==================== 工具方法 ====================

    /** 构建登录成功响应 */
    private fun buildLoginResponse(): Response {
        val loginResp = LoginResp.newBuilder()
            .setUserId(1001L)
            .setToken("test-token")
            .setDeviceType(DeviceType.MOBILE)
            .setDeviceId("device-001")
            .build()
        return Response.newBuilder()
            .setMethod("user/login")
            .setCode(200)
            .setResult(loginResp.toByteString())
            .build()
    }
}
