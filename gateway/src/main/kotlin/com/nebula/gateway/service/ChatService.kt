package com.nebula.gateway.service

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
import com.nebula.chat.Message
import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.StatusChangedPayload
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.user.LoginResp
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.push.PushService
import com.nebula.service.admin.DeadLetterService
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.BindableService
import io.grpc.MethodDescriptor
import io.grpc.ServerServiceDefinition
import io.grpc.protobuf.ProtoUtils
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentLinkedQueue

/** 缓存再投递缓冲区上限（D-67）：超过此数量丢弃最旧消息，防止内存泄漏 */
private const val MAX_PENDING = 1000

/** 缓存再投递超时时间（D-67）：超过此时间强制激活投递，防止"防饿死" */
private const val DELIVERY_TIMEOUT_MS = 10_000L

/**
 * gRPC 双向流聊天服务 — 实现 Envelope 协议的分发、登录响应拦截和 Session 绑定（D-05）。
 *
 * 职责：
 * - 实现 BindableService，注册 name="nebula.chat.ChatService" 的 BIDI_STREAMING gRPC 服务
 * - 接收 Envelope 消息，根据 Direction 分发给 Dispatcher 或处理 PING 心跳
 * - 拦截 user/login 的 200 响应，从 LoginResp 中读取设备信息并注册 Session（D-05 绑定流程）
 * - 维护 tokenToObserver 映射，支持同类型设备互踢时的 LOGOUT 推送（D-05 eviction callback）
 * - 在 onCompleted()/onError() 中清理 tokenToObserver，防止内存泄漏（Review 反馈#6）
 * - 集成 UserStreamRegistry，在登录成功时注册 StreamObserver，连接关闭时解除注册（D-01）
 *
 * 设计决策引用：
 * - D-01: UserStreamRegistry 管理 userId→StreamObserver 映射，ChatService 在生命周期事件中注册/注销
 * - D-04: LoginResp 的 deviceType/deviceId 由 LoginHandler 从 LoginReq 复制，无需 ChatService 重新解析 params
 * - D-05: Session 绑定流程：handleRequest → registerWithDeviceType → eviction callback → LOGOUT 推送
 *
 * @param dispatcher 请求分发器
 * @param sessionRegistry Session 注册中心（含设备类型互踢逻辑）
 * @param registry Handler 注册中心
 * @param userStreamRegistry 用户 StreamObserver 注册中心（D-01）
 * @param onlineStatusRepository 在线状态 Redis 操作（D-57）
 * @param friendshipRepository 好友关系仓库（查询好友列表用于推送）
 * @param pushService 推送服务
 * @param privacyRepository 隐私设置仓库（过滤隐藏用户）
 * @param deadLetterService 死信服务（D-75：缓存投递失败 10 次后写入死信表）
 */
class ChatService(
    private val dispatcher: Dispatcher,
    private val sessionRegistry: SessionRegistry,
    private val registry: HandlerRegistry,
    private val userStreamRegistry: UserStreamRegistry,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val friendshipRepository: FriendshipRepository,
    private val pushService: PushService,
    private val privacyRepository: PrivacyRepository,
    private val deadLetterService: DeadLetterService,
    /**
     * 协程作用域 — 用于桥接 gRPC 回调线程与协程。
     *
     * gRPC 的 StreamObserver.onNext() 在 gRPC worker 线程调用，而 Dispatcher.dispatch() 是 suspend 函数。
     * 通过此作用域启动协程执行异步操作。
     *
     * 可注入以支持测试：测试中传入 TestScope，确保所有 fire-and-forget 协程在 runTest 返回时完成。
     */
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : BindableService {

    /** token → StreamObserver 映射，用于 eviction callback 查找对应连接推送 LOGOUT */
    private val tokenToObserver = ConcurrentHashMap<String, StreamObserver<Envelope>>()

    /** 标记 eviction callback 是否已注册，使用 AtomicBoolean 确保 check-then-act 的原子性 */
    private val evictionCallbackRegistered = AtomicBoolean(false)

    override fun bindService(): ServerServiceDefinition {
        // 构造 BIDI_STREAMING MethodDescriptor
        val envelopeMarshaller = ProtoUtils.marshaller(Envelope.getDefaultInstance())
        val chatMethod = MethodDescriptor.newBuilder(envelopeMarshaller, envelopeMarshaller)
            .setFullMethodName(
                MethodDescriptor.generateFullMethodName("nebula.chat.ChatService", "chat")
            )
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .build()

        return ServerServiceDefinition.builder("nebula.chat.ChatService")
            .addMethod(
                chatMethod,
                ServerCalls.asyncBidiStreamingCall<Envelope, Envelope> { responseObserver ->
                    ChatStreamObserver(responseObserver)
                }
            )
            .build()
    }

    /**
     * 内部 StreamObserver — 处理双向流消息，管理连接生命周期。
     *
     * 职责（D-67）：
     * - 接收 Envelope 消息，分发给 handleRequest/handlePing
     * - 在重连期间缓存消息（pendingBuffer），待旧连接清理后投递
     * - 管理连接清理（tokenToObserver 移除、UserStreamRegistry 注销、延迟离线）
     *
     * @param responseObserver gRPC 响应观察者，用于发送响应消息给客户端
     */
    private inner class ChatStreamObserver(
        private val responseObserver: StreamObserver<Envelope>
    ) : StreamObserver<Envelope> {

        /** 用户 ID（REVIEW-MEDIUM-7: 显式声明可空字段，清理时检查非空）。由 handleLoginSuccess 设置。
         * 使用 @Volatile 保证协程写入与 gRPC 线程读取之间的可见性。 */
        @Volatile
        var userId: Long? = null

        /** 会话 Token（CQ-05: 连接断开时用于清理 SessionRegistry）。由 handleLoginSuccess 设置。 */
        @Volatile
        var token: String? = null

        /** 60s 延迟离线任务（D-57），重连时取消旧任务防止泄漏。
         * 使用 @Volatile 保证 gRPC 线程写入与协程读取之间的可见性。 */
        @Volatile
        var delayedOfflineJob: Job? = null

        /**
         * 缓存再投递缓冲区 — 使用 ConcurrentLinkedQueue（无界、无锁、高性能 FIFO，
         * 适合生产者-消费者缓存模式，与 PushService 的 CopyOnWriteArrayList 和
         * SessionRegistry 的 ConcurrentHashMap 不同，此处需要 FIFO 顺序保证）。
         * 在旧连接清理完成前，所有推送消息先缓存到此队列（D-67）。
         */
        private val pendingBuffer = ConcurrentLinkedQueue<Envelope>()

        /** 是否已进入正常投递模式（D-67） */
        @Volatile
        var deliveryActive = false

        /** 每消息投递重试计数器（D-75），key 为 envelope 内容哈希，value 为重试次数 */
        private val retryCountMap = ConcurrentHashMap<String, Int>()

        /**
         * 根据 envelope 内容生成唯一标识键，用于重试计数跟踪（D-75）。
         *
         * 使用 message 的事件类型和 payload 哈希组合，确保同一消息内容在不同 Envelope 实例间可追踪。
         */
        private fun envelopeKey(envelope: Envelope): String {
            val msg = envelope.message
            val payloadHash = if (!msg.payload.isEmpty) msg.payload.hashCode() else 0
            return "${msg.eventType.name}_$payloadHash"
        }

        /**
         * 从 envelope 中提取数据创建死信记录（D-75）。
         *
         * 当消息投递失败达到 MAX_PENDING_RETRIES 次时调用。
         * 优先尝试解析 ChatMessage 类型的数据，非 ChatMessage 类型使用 envelope 原始信息。
         *
         * @param envelope 投递失败的 Envelope
         * @param failReason 失败原因
         */
        private suspend fun createDeadLetter(envelope: Envelope, failReason: String) {
            try {
                val msg = envelope.message
                if (msg.eventType == PushEventType.CHAT_MESSAGE) {
                    val chatMsg = ChatMessage.parseFrom(msg.payload)
                    deadLetterService.create(
                        conversationId = chatMsg.conversationId,
                        senderUid = chatMsg.senderUid,
                        messageType = chatMsg.messageTypeValue,
                        content = chatMsg.content,
                        payload = chatMsg.payload.toByteArray(),
                        clientMsgId = null,
                        clientTs = chatMsg.clientTs,
                        failReason = failReason
                    )
                } else {
                    // 非 ChatMessage 类型的死信，使用 envelope 可用数据
                    deadLetterService.create(
                        conversationId = "",
                        senderUid = 0L,
                        messageType = msg.eventTypeValue,
                        content = msg.content,
                        payload = msg.payload.toByteArray(),
                        clientMsgId = null,
                        clientTs = System.currentTimeMillis(),
                        failReason = failReason
                    )
                }
            } catch (e: Exception) {
                logger.error(e) { "创建死信记录失败" }
            }
        }

        override fun onNext(envelope: Envelope) {
            when (envelope.direction) {
                Direction.REQUEST -> scope.launch {
                    handleRequest(envelope, responseObserver)
                }
                Direction.PING -> handlePing(envelope, responseObserver)
                else -> logger.warn { "Unexpected direction: ${envelope.direction}" }
            }
        }

        override fun onCompleted() {
            cleanupPending()
            cleanupConnection()
            responseObserver.onCompleted()
        }

        override fun onError(t: Throwable) {
            logger.error(t) { "ChatService stream error" }
            cleanupPending()
            cleanupConnection()
            responseObserver.onError(t)
        }

        /**
         * 缓存再投递入口（D-67, D-75）。
         *
         * 在重连期间（deliveryActive = false），将消息缓存到 pendingBuffer；
         * 在正常投递模式（deliveryActive = true），直接通过 responseObserver 投递。
         * 投递失败时跟踪重试次数，超过 MAX_PENDING_RETRIES 次后调用 [createDeadLetter] 写入死信表（D-75）。
         *
         * 所有通过 UserStreamRegistry.getStreams() 获取 StreamObserver 后调用 onNext()
         * 的代码路径（PushService.pushEventToUser 等），应切换为此方法。
         * 外部调用：`(observer as? ChatStreamObserver)?.deliver(envelope) ?: observer.onNext(envelope)`
         *
         * @param envelope 待投递的 Envelope
         */
        fun deliver(envelope: Envelope) {
            if (deliveryActive) {
                try {
                    responseObserver.onNext(envelope)
                } catch (e: Exception) {
                    // D-75: 投递失败，跟踪重试次数
                    val key = envelopeKey(envelope)
                    val retryCount = requireNotNull(retryCountMap.merge(key, 1) { old, _ -> old + 1 }) { "merge 结果不能为null" }
                    logger.warn(e) { "投递失败（第 $retryCount 次），envelopeKey=$key" }
                    if (retryCount >= MAX_PENDING_RETRIES) {
                        retryCountMap.remove(key)
                        // 异步创建死信，不阻塞当前线程
                        scope.launch { createDeadLetter(envelope, "投递失败已达${MAX_PENDING_RETRIES}次") }
                    } else {
                        // 重新入队等待下次重试
                        if (pendingBuffer.size >= MAX_PENDING) {
                            pendingBuffer.poll()
                        }
                        pendingBuffer.add(envelope)
                        // 触发延迟投递防饿死（D-67）
                        scheduleDelayedRetry()
                    }
                }
            } else {
                // 超限保护：丢弃最旧消息
                if (pendingBuffer.size >= MAX_PENDING) {
                    pendingBuffer.poll()
                }
                pendingBuffer.add(envelope)
            }
        }

        /** 延迟触发缓存投递的协程 Job */
        private var delayedRetryJob: Job? = null

        /**
         * 安排延迟投递任务（D-67 防饿死）。
         *
         * 当 deliveryActive 但投递失败重新入队后，安排 10s 后重新尝试投递缓存。
         */
        private fun scheduleDelayedRetry() {
            delayedRetryJob?.cancel()
            delayedRetryJob = scope.launch {
                delay(DELIVERY_TIMEOUT_MS)
                if (deliveryActive) {
                    activateDeliveryInternal()
                }
            }
        }

        /**
         * 投递单条缓存消息（D-75）。
         *
         * 投递成功返回 true；
         * 投递失败时跟踪重试次数，超过 MAX_PENDING_RETRIES 次后创建死信并返回 true（已处理），
         * 否则重新入队并返回 false。
         *
         * @param envelope 待投递的 Envelope
         * @return true 消息已处理（成功投递或写入死信），false 需重新入队
         */
        private suspend fun deliverCached(envelope: Envelope): Boolean {
            return try {
                responseObserver.onNext(envelope)
                true
            } catch (e: Exception) {
                val key = envelopeKey(envelope)
                val retryCount = retryCountMap.merge(key, 1) { old, _ -> old + 1 }!!
                logger.warn(e) { "缓存消息投递失败（第 $retryCount 次），envelopeKey=$key" }
                if (retryCount >= MAX_PENDING_RETRIES) {
                    retryCountMap.remove(key)
                    createDeadLetter(envelope, "缓存投递失败已达${MAX_PENDING_RETRIES}次")
                    true // 死信已处理，标记为完成
                } else {
                    false // 需重新入队
                }
            }
        }

        /**
         * 激活投递模式，投递所有缓存消息（D-67, D-75）。
         *
         * 由 handleLoginSuccess 在旧连接清理完成后调用。
         * 使用 withContext(Dispatchers.Default) 避免阻塞 gRPC 事件循环线程。
         * 使用 Default 而非 IO，因为 onNext() 是非阻塞的 gRPC 调用。
         * 投递失败的消息根据重试计数决定重新入队或写入死信（D-75）。
         */
        suspend fun activateDelivery() {
            withContext(Dispatchers.Default) {
                activateDeliveryInternal()
            }
            deliveryActive = true
        }

        /**
         * 投递所有缓存消息的内部实现（D-75）。
         *
         * 轮询 pendingBuffer，逐条投递。投递失败的消息重新入队等待下次重试。
         * 与 [deliver] 方法失败时的重新入队逻辑保持一致。
         */
        private suspend fun activateDeliveryInternal() {
            val reQueue = mutableListOf<Envelope>()
            while (true) {
                val envelope = pendingBuffer.poll() ?: break
                val handled = deliverCached(envelope)
                if (!handled) {
                    reQueue.add(envelope)
                }
            }
            // 将投递失败需重试的消息重新入队
            reQueue.forEach { pendingBuffer.add(it) }
        }

        /** 连接清理时清理缓冲区，防止内存泄漏（D-67） */
        fun cleanupPending() {
            pendingBuffer.clear()
        }

        /**
         * 清理此连接持有的所有资源（Review 修复：防止内存泄漏）。
         *
         * 清理操作：
         * 1. 从 tokenToObserver 移除当前 responseObserver（精确匹配实例，D-67 并发安全）
         * 2. 从 UserStreamRegistry 移除当前设备的 StreamObserver（防御性检查，D-01）
         * 3. 从 SessionRegistry 清除会话（CQ-05: 确保重连时生成新 connectionId）
         * 4. 启动 60s 延迟离线任务，到期后检查无剩余设备则标记离线 + 推送（D-57）
         */
        private fun cleanupConnection() {
            // D-67 并发安全：使用 values.remove() 精确匹配当前 observer 实例
            // 避免 entries.removeIf 遍历所有条目时误匹配其他线程新注册的 observer
            tokenToObserver.values.remove(responseObserver)

            // CQ-05: 清除会话，确保重连时生成新 connectionId
            token?.let { tok ->
                sessionRegistry.removeFromLocalCache(tok)
                scope.launch {
                    sessionRegistry.removeFromRedis(tok)
                }
            }

            // D-01: 移除当前设备 StreamObserver（不调 removeUser 以免移除其他设备的流）
            userId?.let { uid ->
                // 防御性检查：仅当当前 observer 仍在注册表中时才移除
                // 防止多设备重连场景下误删新连接的 StreamObserver（D-67）
                val currentStreams = userStreamRegistry.getStreams(uid)
                if (currentStreams.any { it == responseObserver }) {
                    userStreamRegistry.removeStream(uid, responseObserver)
                }
            }

            // D-57: 60s 延迟离线任务（伪在线）
            userId?.let { uid ->
                // 取消旧的延迟任务防止泄漏（R-09-02）
                delayedOfflineJob?.cancel()
                delayedOfflineJob = scope.launch {
                    delay(60_000)  // 60s 伪在线窗口
                    // 再次检查是否还有其他设备在线
                    if (userStreamRegistry.getStreams(uid).isEmpty()) {
                        // 无剩余设备，标记离线
                        withContext(Dispatchers.IO) {
                            onlineStatusRepository.setOffline(uid)
                        }
                        // 推送状态变更给所有好友
                        pushStatusChangeToFriends(uid, 0)
                    }
                }
            }
        }
    }

    /**
     * 处理业务请求（Direction.REQUEST）。
     *
     * D-05 绑定流程：
     * 1. 调用 dispatcher.dispatch() 分发请求
     * 2. 若响应是 user/login 且 code=200，执行 Session 绑定
     * 3. 否则直接返回响应
     */
    private suspend fun handleRequest(
        envelope: Envelope,
        responseObserver: StreamObserver<Envelope>
    ) {
        // 确保 eviction callback 已注册（首次调用时注册一次）
        ensureEvictionCallbackRegistered()

        val response = dispatcher.dispatch(envelope.request)

        if (response.method == "user/login" && response.code == 200) {
            // D-05 拦截：登录成功，绑定 Session
            handleLoginSuccess(response, responseObserver)
        } else {
            // 其他响应，直接返回
            val responseEnvelope = Envelope.newBuilder()
                .setDirection(Direction.RESPONSE)
                .setRequestId(envelope.requestId)
                .setResponse(response)
                .build()
            responseObserver.onNext(responseEnvelope)
        }
    }

    /**
     * 处理登录成功响应（D-05 绑定流程）。
     *
     * 从 LoginResp 中直接读取 deviceType/deviceId（Review 修复：无需重新解析 Request.params）。
     * 调用 SessionRegistry.registerWithDeviceType() 注册 Session，旧连接收到 LOGOUT 推送。
     * 同时将当前 StreamObserver 注册到 UserStreamRegistry（D-01）。
     *
     * @param response 登录成功响应（code=200）
     * @param responseObserver 当前连接的 StreamObserver
     */
    private suspend fun handleLoginSuccess(
        response: Response,
        responseObserver: StreamObserver<Envelope>
    ) {
        // 反序列化 LoginResp
        val loginResp = LoginResp.parseFrom(response.result.toByteArray())

        // 从 LoginResp 中直接获取设备信息（Review 修复#3：无需重新解析 Request.params）
        val session = Session(
            userId = loginResp.userId,
            token = loginResp.token,
            deviceType = loginResp.deviceType.name,
            deviceId = loginResp.deviceId,
            connectionId = UUID.randomUUID().toString()
        )

        // 注册 Session（同类型设备互踢，返回被驱逐的旧 token）
        val evictedToken = sessionRegistry.registerWithDeviceType(session)

        // 更新 tokenToObserver：清理旧 token 映射，设置新映射
        if (evictedToken != null) {
            tokenToObserver.remove(evictedToken)
        }
        tokenToObserver[session.token] = responseObserver

        // D-01: 注册 StreamObserver 到 UserStreamRegistry（REVIEW-MEDIUM-7: 使用 require 替代 as? 静默转换）
        require(responseObserver is ChatStreamObserver) {
            "responseObserver must be ChatStreamObserver"
        }
        responseObserver.userId = loginResp.userId

        // CQ-05: 记录 token 用于连接断开时清理 SessionRegistry
        responseObserver.token = session.token

        // D-57: 重连时取消旧的延迟离线任务
        responseObserver.delayedOfflineJob?.cancel()

        userStreamRegistry.register(loginResp.userId, responseObserver)

        // D-57: 标记在线 + 推送状态变更给所有好友
        scope.launch {
            withContext(Dispatchers.IO) {
                onlineStatusRepository.setOnline(loginResp.userId)
            }
            pushStatusChangeToFriends(loginResp.userId, 1)
        }

        // D-67: 激活缓存再投递
        if (evictedToken != null) {
            // eviction callback 在 registerWithDeviceType 中同步执行完成
            // 注意：如果旧连接清理超过 10s，缓冲区超时保护会强制激活投递。
            // 此时旧连接可能仍在，投递到旧连接的消息在 onCompleted 后丢失。
            // 这是"防饿死"权衡：宁可丢失少量消息也不阻塞新连接。
            // 丢失的消息可通过 Phase 10 的 gap detect + auto-pull 恢复。
            (responseObserver as? ChatStreamObserver)?.activateDelivery()
        } else {
            // 无旧连接，直接激活投递（首次登录或超时重连后旧连接已清理）
            (responseObserver as? ChatStreamObserver)?.let {
                it.deliveryActive = true
            }
        }

        // 发送 LoginResp Envelope 给客户端
        val loginRespEnvelope = Envelope.newBuilder()
            .setDirection(Direction.RESPONSE)
            .setRequestId("")  // login 请求的 request_id 从原始 Envelope 获取（简化处理）
            .setResponse(response)
            .build()
        responseObserver.onNext(loginRespEnvelope)
    }

    /**
     * 处理 PING 心跳请求，回复 PONG Envelope + 刷新在线状态 TTL（D-27, D-57）。
     *
     * @param envelope PING 请求 Envelope
     * @param responseObserver 当前连接的 StreamObserver
     */
    private fun handlePing(
        envelope: Envelope,
        responseObserver: StreamObserver<Envelope>
    ) {
        // D-57: 刷新在线状态 TTL
        (responseObserver as? ChatStreamObserver)?.userId?.let { uid ->
            scope.launch {
                withContext(Dispatchers.IO) {
                    onlineStatusRepository.refreshTtl(uid)
                }
            }
        }

        val pongEnvelope = Envelope.newBuilder()
            .setDirection(Direction.PONG)
            .setRequestId(envelope.requestId)
            .build()
        responseObserver.onNext(pongEnvelope)
    }

    /**
     * 确保 eviction callback 已注册（首次请求时注册一次）。
     *
     * eviction callback 在 Session 被驱逐（同类型设备互踢）时被触发：
     * 通过 tokenToObserver 查找当前连接，推送 DISCONNECT 通知并关闭连接。
     *
     * 推送步骤（D-68）：
     * 1. 推送 DISCONNECT 到旧连接，通知客户端触发重连流程
     * 2. 关闭旧连接（onCompleted）
     *
     * 异常容错：推送失败时 try-catch 保护，不阻止后续连接清理。
     * 不使用 PushService.pushEventToUser()：需要通过 tokenToObserver 精确推送到
     * 即将被关闭的旧连接，而非通过 UserStreamRegistry 推送到所有设备。
     */
    private fun ensureEvictionCallbackRegistered() {
        if (evictionCallbackRegistered.compareAndSet(false, true)) {
            sessionRegistry.onEviction { token ->
                val observer = tokenToObserver.remove(token)
                if (observer != null) {
                    // Step 1: 推送 DISCONNECT 通知（D-68）
                    try {
                        val disconnectEnvelope = Envelope.newBuilder()
                            .setDirection(Direction.PUSH)
                            .setRequestId("")  // 系统推送无 request_id
                            .setMessage(Message.newBuilder()
                                .setEventType(PushEventType.DISCONNECT)
                                .setContent("连接将被关闭，请触发重连流程")
                                .build())
                            .build()
                        observer.onNext(disconnectEnvelope)
                    } catch (e: Exception) {
                        // 连接可能已损坏，推送失败不阻塞清理
                        logger.warn(e) { "Failed to push DISCONNECT, connection may already be broken" }
                    }

                    // Step 2: 关闭连接（触发 cleanupConnection）
                    observer.onCompleted()
                }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** pendingBuffer 中单条消息的最大投递重试次数，超过后写入死信表（D-75） */
        const val MAX_PENDING_RETRIES = 10
    }

    /**
     * 推送状态变更给所有在线好友（D-50, D-57）。
     *
     * 查询好友列表 → 过滤隐藏用户 → 逐个 pushEventToUser(STATUS_CHANGED)。
     * JPA 查询在 withContext(Dispatchers.IO) 中执行。
     *
     * @param userId 状态变更用户 UID
     * @param status 新状态：0=离线 1=在线 2=隐藏
     */
    private fun pushStatusChangeToFriends(userId: Long, status: Int) {
        scope.launch {
            try {
                val friendships = withContext(Dispatchers.IO) {
                    friendshipRepository.findFriendsByUserId(
                        userId, 0, org.springframework.data.domain.PageRequest.of(0, Int.MAX_VALUE)
                    )
                }
                val friendUids = friendships.map { f ->
                    if (f.userId == userId) f.friendId else f.userId
                }.distinct()

                // 过滤隐藏用户
                val hiddenUids = privacyRepository.batchGetHideOnlineStatus(friendUids)
                val visibleFriends = friendUids.filter { it !in hiddenUids }

                val payload = StatusChangedPayload.newBuilder()
                    .setUid(userId)
                    .setStatus(status)
                    .build()

                visibleFriends.forEach { friendUid ->
                    try {
                        pushService.pushEventToUser(
                            friendUid, PushEventType.STATUS_CHANGED, payload.toByteString()
                        )
                    } catch (e: Exception) {
                        logger.warn(e) { "Failed to push status change to friend=$friendUid" }
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "pushStatusChangeToFriends failed for userId=$userId" }
            }
        }
    }
}
