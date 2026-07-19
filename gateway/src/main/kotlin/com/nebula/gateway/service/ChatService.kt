package com.nebula.gateway.service

import com.nebula.chat.*
import com.nebula.common.BizCode
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.DeliverableStreamObserver
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.service.admin.DeadLetterService
import com.nebula.service.friend.FriendService
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.*
import io.grpc.stub.ServerCalls
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/** 缓存再投递缓冲区上限（D-67）：超过此数量丢弃最旧消息，防止内存泄漏 */
private const val MAX_PENDING = 1000

/** 缓存再投递超时时间（D-67）：超过此时间强制激活投递，防止"防饿死" */
private const val DELIVERY_TIMEOUT_MS = 10_000L

/**
 * gRPC 双向流聊天服务 — 实现 Envelope 协议的分发、登录响应拦截和 Session 绑定（D-05）。
 *
 * 职责：
 * - 实现 BindableService，注册 name=[SERVICE_NAME] 的 BIDI_STREAMING gRPC 服务
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
 * @param userStreamRegistry 用户 StreamObserver 注册中心（D-01）
 * @param onlineStatusService 在线状态服务（D-57）
 * @param friendService 好友服务（查询好友列表用于推送）
 * @param pushService 推送服务
 * @param privacyService 隐私设置服务（过滤隐藏用户）
 * @param deadLetterService 死信服务（D-75：缓存投递失败 10 次后写入死信表）
 */
class ChatService(
    private val dispatcher: Dispatcher,
    private val sessionRegistry: SessionRegistry,
    private val userStreamRegistry: UserStreamRegistry,
    private val onlineStatusService: OnlineStatusService,
    private val friendService: FriendService,
    private val pushService: PushService,
    private val privacyService: UserPrivacyService,
    private val deadLetterService: DeadLetterService,
    /**
     * 服务级后台任务协程作用域（D-85）。
     *
     * 用于跨连接存活的后台任务：延迟离线（60s 伪在线窗口）、死信补偿、设备类型清理、
     * 在线状态变更推送、好友状态广播等。使用 IO 调度器 + SupervisorJob。
     *
     * 与 per-connection 的 [ChatStreamObserver.connectionScope] 分离：serverScope 中的任务
     * 不受连接断开的 cancel 影响。
     */
    private val serverScope: CoroutineScope
) : BindableService {

    /** token → StreamObserver 映射，用于 eviction callback 查找对应连接推送 LOGOUT */
    private val tokenToObserver = ConcurrentHashMap<String, StreamObserver<Envelope>>()

    /** ChatStreamObserver 实例计数器，用于分配连接编号（诊断用） */
    private val observerCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** 标记 eviction callback 是否已注册，使用 AtomicBoolean 确保 check-then-act 的原子性 */
    private val evictionCallbackRegistered = AtomicBoolean(false)

    /** 好友在线状态变更广播器（D-50, D-57）：从 pushStatusChangeToFriends 抽离（Phase 1 瘦身） */
    private val friendStatusNotifier = FriendStatusNotifier(
        serverScope, friendService, privacyService, pushService
    )

    /** 会话绑定编排器（D-05）：从 handleLoginSuccess/handleRegisterSuccess/bindSession 抽离（Phase 1 瘦身） */
    private val sessionBinder = SessionBinder(
        sessionRegistry, userStreamRegistry, onlineStatusService, friendStatusNotifier, serverScope, tokenToObserver
    )

    /** PING 心跳处理器（D-27）：从 handlePing 抽离（Phase 1 瘦身） */
    private val pingProcessor = PingProcessor(onlineStatusService)

    /** 响应后置拦截器链（Phase 2 瘦身）：登录绑定等响应后逻辑可插拔，ChatService 不再硬编码业务 method */
    private val responseInterceptors: List<ResponseInterceptor> = listOf(LoginBindingInterceptor(sessionBinder))

    override fun bindService(): ServerServiceDefinition {
        // 构造 BIDI_STREAMING MethodDescriptor
        // 使用自定义 Marshaller 替代 ProtoUtils.marshaller(Envelope.getDefaultInstance())。
        // ProtoInputStream.drainTo() 返回 getSerializedSize() 作为已写入字节数，
        // 但 CodedOutputStream → MessageFramer 的 flush 边界不一致导致写入 > 声明，
        // 触发 knownLengthPendingAllocation。ByteArrayInputStream 的 available()
        // 与 read() 完全一致，且对标 protobuf 仅多一次 toByteArray()（原路径同样有序列化）。
        val envelopeMarshaller: MethodDescriptor.Marshaller<Envelope> =
            object : MethodDescriptor.Marshaller<Envelope> {
                override fun stream(value: Envelope): java.io.InputStream =
                    java.io.ByteArrayInputStream(value.toByteArray())
                override fun parse(stream: java.io.InputStream): Envelope =
                    Envelope.parseFrom(stream)
            }
        val chatMethod = MethodDescriptor.newBuilder(envelopeMarshaller, envelopeMarshaller)
            .setFullMethodName(
                MethodDescriptor.generateFullMethodName(SERVICE_NAME, "chat")
            )
            .setType(MethodDescriptor.MethodType.BIDI_STREAMING)
            .build()

        return ServerServiceDefinition.builder(SERVICE_NAME)
            .addMethod(
                chatMethod,
                ServerCalls.asyncBidiStreamingCall { responseObserver ->
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
    internal inner class ChatStreamObserver(
        private val responseObserver: StreamObserver<Envelope>
    ) : DeliverableStreamObserver, ConnectionContext {

        /** 连接编号（递增，用于关联同一连接的多帧消息）。仅 ChatService 内部使用（handlePing 等）。 */
        override val connId = observerCounter.incrementAndGet()

        /** 协程互斥锁：gRPC MessageFramer 非线程安全，多个协程并发 onNext 会踩坏缓冲区。
         * 通过 [sendEnvelope] 间接使用，ChatStreamObserver 内部访问。 */
        val sendMutex = Mutex()

        /**
         * 每连接独立协程作用域（D-85）。
         *
         * 替换 ChatService 上的全局 scope，实现连接断开时自动取消所有在途请求和关联异步任务。
         * 使用 IO 调度器 + SupervisorJob：单子协程崩溃不取消兄弟协程，连接 cancel 时一刀切。
         */
        override val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        /**
         * 线程安全的 Envelope 发送入口（D-67, D-85 suspend 重构）。
         *
         * gRPC MessageFramer 非线程安全，多个协程并发调用 responseObserver.onNext 会踩坏缓冲区。
         * 本方法通过 [sendMutex] 串行化所有写入操作。
         *
         * D-85: 改为 suspend 函数，消除 runBlocking 对 IO 线程的阻塞。
         * 调用方必须在协程上下文中（connectionScope.launch / serverScope.launch）。
         * gRPC 线程上的 onNext 经 connectionScope.launch 桥接；handlePing 内部经
         * connectionScope.launch 调用本方法；eviction DISCONNECT 经 serverScope.launch
         * 异步发送（见 ensureEvictionCallbackRegistered，已不再使用 runBlocking）。
         *
         * @param envelope 待发送的 Envelope
         */
        override suspend fun sendEnvelope(envelope: Envelope) {
            sendMutex.withLock { responseObserver.onNext(envelope) }
        }

        init {
            logger.info { "[stream] #$connId ChatStreamObserver 创建 responseObserver=${responseObserver.javaClass.simpleName}@${System.identityHashCode(responseObserver)}" }
        }

        /** 用户 ID（REVIEW-MEDIUM-7: 显式声明可空字段，清理时检查非空）。由 handleLoginSuccess 设置。
         * 使用 @Volatile 保证协程写入与 gRPC 线程读取之间的可见性。 */
        @Volatile
        override var userId: Long? = null

        /** 会话 Token（CQ-05: 连接断开时用于清理 SessionRegistry）。由 handleLoginSuccess 设置。 */
        @Volatile
        override var token: String? = null

        /** 60s 延迟离线任务（D-57），重连时取消旧任务防止泄漏。
         * 使用 @Volatile 保证 gRPC 线程写入与协程读取之间的可见性。 */
        @Volatile
        override var delayedOfflineJob: Job? = null

        /** H2 修复：设备类型（用于连接断开时清理 Redis 设备类型映射） */
        @Volatile
        override var deviceType: String? = null

        /**
         * 缓存再投递缓冲区 — 使用 ConcurrentLinkedQueue（无界、无锁、高性能 FIFO，
         * 适合生产者-消费者缓存模式，与 PushService 的 CopyOnWriteArrayList 和
         * SessionRegistry 的 ConcurrentHashMap 不同，此处需要 FIFO 顺序保证）。
         * 在旧连接清理完成前，所有推送消息先缓存到此队列（D-67）。
         */
        private val pendingBuffer = ConcurrentLinkedQueue<Envelope>()

        /** 是否已进入正常投递模式（D-67） */
        @Volatile
        override var deliveryActive = false

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

        override fun onNext(envelope: Envelope) {
            when (envelope.direction) {
                Direction.REQUEST -> {
                    logger.info { "[stream] 收到 REQUEST requestId=${envelope.requestId}，method=${envelope.request.method}，metadata=${envelope.request.metadataMap}" }
                    connectionScope.launch {
                        // fix: 传递 ChatStreamObserver（this）而非 gRPC responseObserver，
                        // 确保 handleLoginSuccess 中的 require(responseObserver is ChatStreamObserver) 不会失败
                        handleRequest(envelope, this@ChatStreamObserver)
                    }
                }
                Direction.PING -> {
                    // Phase 1 瘦身：PING 处理委托 PingProcessor
                    pingProcessor.handle(envelope, this@ChatStreamObserver)
                }
                // 服务端生成的 RESPONSE / PONG / PUSH 直接转发给 gRPC 客户端，避免 ChatStreamObserver.onNext 递归
                // PUSH: PushService 通过 UserStreamRegistry 推送消息（聊天消息/已读回执/投递确认），
                // 由于 UserStreamRegistry 存储的是 ChatStreamObserver 实例，需在此转发到 gRPC 客户端
                Direction.RESPONSE, Direction.PONG, Direction.PUSH -> {
                    connectionScope.launch { sendEnvelope(envelope) }
                }
                else -> logger.warn { "[stream] Unexpected direction: ${envelope.direction}" }
            }
        }

        override fun onCompleted() {
            logger.info { "[stream] #$connId onCompleted userId=$userId token=${token?.take(8)}..." }
            try {
                cleanupPending()
                cleanupConnection()
                connectionScope.cancel()
            } catch (e: Exception) {
                logger.error(e) { "[stream] #$connId cleanupConnection 失败，跳过以释放 gRPC 资源" }
            } finally {
                // CQ-12: responseObserver.onCompleted() 可能因 call 已关闭而抛异常，需要兜底容错
                try {
                    responseObserver.onCompleted()
                } catch (e: Exception) {
                    logger.warn(e) { "[stream] #$connId responseObserver.onCompleted 失败（连接可能已关闭）" }
                }
            }
        }

        override fun onError(t: Throwable) {
            if (isExpectedDisconnect(t)) {
                // 客户端取消/断开属连接生命周期的正常部分，仅打印异常名与 message，不打印 stacktrace
                logger.info { "[stream] #$connId onError 连接被客户端取消/断开（${t::class.simpleName}: ${t.message}）userId=$userId token=${token?.take(8)}..." }
            } else {
                logger.error(t) { "[stream] #$connId onError userId=$userId token=${token?.take(8)}..." }
            }
            try {
                cleanupPending()
                cleanupConnection()
                connectionScope.cancel()
            } catch (e: Exception) {
                logger.error(e) { "[stream] #$connId cleanupConnection 失败，跳过以释放 gRPC 资源" }
            } finally {
                // CQ-12: responseObserver.onError() 可能因 call 已关闭而抛异常，需要兜底容错
                try {
                    responseObserver.onError(t)
                } catch (e: Exception) {
                    if (isExpectedDisconnect(e)) {
                        logger.info { "[stream] #$connId responseObserver.onError 跳过（连接已关闭，${e::class.simpleName}: ${e.message}）" }
                    } else {
                        logger.warn(e) { "[stream] #$connId responseObserver.onError 失败（连接可能已关闭）" }
                    }
                }
            }
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
        override fun deliver(envelope: Envelope) {
            if (deliveryActive) {
                connectionScope.launch {
                    try {
                        sendEnvelope(envelope)
                    } catch (e: Exception) {
                        // D-75: 投递失败，跟踪重试次数（移入 launch 块以捕获 sendEnvelope suspend 异常）
                        val key = envelopeKey(envelope)
                        val retryCount = requireNotNull(retryCountMap.merge(key, 1) { old, _ -> old + 1 }) { "merge 结果不能为null" }
                        logger.warn(e) { "投递失败（第 $retryCount 次），envelopeKey=$key" }
                        if (retryCount >= MAX_PENDING_RETRIES) {
                            retryCountMap.remove(key)
                            // 死信通过 serverScope 保证不受连接 cancel 影响
                            serverScope.launch { deadLetterService.recordFromEnvelope(envelope, "投递失败已达${MAX_PENDING_RETRIES}次") }
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
            delayedRetryJob = connectionScope.launch {
                delay(DELIVERY_TIMEOUT_MS.milliseconds)
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
                sendMutex.withLock { responseObserver.onNext(envelope) }
                true
            } catch (e: Exception) {
                val key = envelopeKey(envelope)
                val retryCount = retryCountMap.merge(key, 1) { old, _ -> old + 1 }!!
                logger.warn(e) { "缓存消息投递失败（第 $retryCount 次），envelopeKey=$key" }
                if (retryCount >= MAX_PENDING_RETRIES) {
                    retryCountMap.remove(key)
                    deadLetterService.recordFromEnvelope(envelope, "缓存投递失败已达${MAX_PENDING_RETRIES}次")
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
        override suspend fun activateDelivery() {
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
         * 3. 从 SessionRegistry 仅清除 L1 本地缓存，保留 Redis 中的 token（修复：断连后
         *    客户端可拿同一 token 重新鉴权，无需重新登录。同设备重新登录时 registerWithDeviceType
         *    会触发 unregister 从 Redis 删除旧 token。）
         * 4. 启动 60s 延迟离线任务，到期后检查无剩余设备则标记离线 + 推送（D-57）
         */
        fun cleanupConnection() {
            // CQ-12: 使用 token 字段精确 key-based 删除，替代 O(n) 全表扫描 values.remove(this)。
            // tokenToObserver 存储的是 ChatStreamObserver 实例（handleLoginSuccess L590）。
            // 正常流程中 token 已设置（登录后），清理时通过 token key 精确删除。
            // 若 token 为 null（未登录即断连），该 observer 不在 tokenToObserver 中，无需清理。
            token?.let { tok ->
                tokenToObserver.remove(tok)
            }

            // 仅清除 L1 本地缓存，保留 Redis 中的 token。
            // 客户端断连后可用同一 token 重新鉴权（AuthInterceptor 从 Redis 恢复 Session）。
            // 同设备重新登录时 registerWithDeviceType → unregister 会从 Redis 删除旧 token。
            token?.let { tok ->
                sessionRegistry.removeFromLocalCache(tok)
            }

            // H2: 条件清理 Redis 设备类型映射（CQ-12 竞态修复）。
            // 传递 expectedToken 参数，仅当 Redis 中映射值仍为旧 token 时才删除，
            // 防止旧连接的异步清理误删新连接重连后写入的映射。
            // D-85: 使用 serverScope 保证断连后仍可执行 Redis 清理。
            userId?.let { uid ->
                deviceType?.let { dt ->
                    token?.let { tok ->
                        serverScope.launch {
                            sessionRegistry.cleanupDeviceTypeMapping(uid, dt, tok)
                        }
                    }
                }
            }

            // D-01: 移除当前设备 StreamObserver（不调 removeUser 以免移除其他设备的流）
            userId?.let { uid ->
                // 防御性检查：仅当当前 observer 仍在注册表中时才移除
                // 防止多设备重连场景下误删新连接的 StreamObserver（D-67）
                // fix: 使用 this 而非 responseObserver，确保与注册时存储的实例一致
                val currentStreams = userStreamRegistry.getStreams(uid)
                if (currentStreams.any { it === this }) {
                    userStreamRegistry.removeStream(uid, this)
                }
            }

            // D-57: 60s 延迟离线任务（伪在线）。D-85: 使用 serverScope 保证断连后仍可执行。
            userId?.let { uid ->
                // 取消旧的延迟任务防止泄漏（R-09-02）
                delayedOfflineJob?.cancel()
                delayedOfflineJob = serverScope.launch {
                    delay(60_000.milliseconds)  // 60s 伪在线窗口
                    // 再次检查是否还有其他设备在线
                    if (userStreamRegistry.getStreams(uid).isEmpty()) {
                        // 无剩余设备，标记离线
                        withContext(Dispatchers.IO) {
                            onlineStatusService.setOffline(uid)
                        }
                        // 推送状态变更给所有好友
                        friendStatusNotifier.notifyFriends(uid, 0)
                    }
                }
            }
        }
    }

    /**
     * 处理业务请求（Direction.REQUEST）。
     *
     * 绑定流程（D-05, CQ-13）：
     * 1. 调用 dispatcher.dispatch() 分发请求
     * 2. 若响应是 user/login 或 user/register 成功，执行 Session 绑定
     * 3. 否则直接返回响应
     */
    /**
     * 处理业务请求（Direction.REQUEST）。
     *
     * 分发流程（D-05, CQ-13）：
     * 1. 调用 dispatcher.dispatch() 分发请求
     * 2. 若响应是 user/login 或 user/register 成功，委托 SessionBinder 执行绑定
     * 3. 否则直接返回响应
     */
    /**
     * 处理业务请求（Direction.REQUEST）。
     *
     * 分发流程（D-05, CQ-13, Phase 2）：
     * 1. 调用 dispatcher.dispatch() 分发请求
     * 2. 响应经 ResponseInterceptor 责任链处理（登录/注册成功时由 LoginBindingInterceptor 执行绑定）
     * 3. 统一发送最终响应 Envelope 给客户端
     */
    private suspend fun handleRequest(
        envelope: Envelope,
        observer: ConnectionContext
    ) {
        // 确保 eviction callback 已注册（首次调用时注册一次）
        ensureEvictionCallbackRegistered()

        // 日志辅助排查：打印请求的 method，确认客户端实际发送的内容
        if (envelope.direction == Direction.REQUEST) {
            logger.info { "[handleRequest] method=${envelope.request.method} direction=${envelope.direction} requestId=${envelope.requestId} metadata=${envelope.request.metadataMap}" }
        }

        val response = dispatcher.dispatch(envelope.request)

        // 日志辅助排查：打印 dispatch 返回的 code 和 msg
        if (response.code != BizCode.OK.code) {
            logger.warn { "[handleRequest] 响应异常 method=${response.method} code=${response.code} msg=${response.msg}" }
        }

        // Phase 2 瘦身：响应后逻辑（登录绑定等）通过拦截器链处理，ChatService 不再硬编码业务 method
        val finalResp = responseInterceptors.fold(response) { r, interceptor ->
            interceptor.afterResponse(r, observer, envelope.request)
        }
        sendResponseEnvelope(finalResp, observer, envelope.requestId)
    }

    /**
     * 透传响应 Envelope 给客户端（无 Session 绑定逻辑）。
     *
     * 用于：
     * - 非 user/login / user/register 的响应
     * - user/login / user/register 失败（非 OK 状态码）的错误响应
     *
     * 注：gRPC 序列化一致性已由 bindService() 中的自定义 Marshaller 在全局层面保证。
     */
    private suspend fun sendResponseEnvelope(
        response: Response,
        responseObserver: ConnectionContext,
        requestId: String
    ) {
        val responseEnvelope = Envelope.newBuilder()
            .setDirection(Direction.RESPONSE)
            .setRequestId(requestId)
            .setResponse(response)
            .build()
        responseObserver.sendEnvelope(responseEnvelope)
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
                val observer = tokenToObserver.remove(token) ?: return@onEviction
                // 2026-07 review F1：改为 serverScope.launch 异步写穿，避免 runBlocking 阻塞登录请求协程（互踢场景）。
                // serverScope 不受连接取消链（connectionScope.cancel）影响，DISCONNECT 不会被取消，写穿比 runBlocking 更可靠。
                // sendEnvelope 与 onCompleted 必须在同一 launch 块内顺序执行：sendMutex 保证 DISCONNECT 的 onNext
                // 先于 onCompleted() 的 responseObserver.onCompleted() 写穿；onCompleted 内部不持 sendMutex，无重入风险。
                serverScope.launch {
                    // Step 1: 推送 DISCONNECT 通知（D-68, D-85）。
                    try {
                        val disconnectEnvelope = Envelope.newBuilder()
                            .setDirection(Direction.PUSH)
                            .setRequestId("")  // 系统推送无 request_id
                            .setMessage(Message.newBuilder()
                                .setEventType(PushEventType.DISCONNECT)
                                .setContent("连接将被关闭，请触发重连流程")
                                .build())
                            .build()
                        val chatObserver = observer as? ChatStreamObserver
                        if (chatObserver != null) {
                            chatObserver.sendEnvelope(disconnectEnvelope)
                        } else {
                            observer.onNext(disconnectEnvelope)
                        }
                    } catch (e: Exception) {
                        // 连接可能已损坏，推送失败不阻塞清理
                        logger.warn(e) { "Failed to push DISCONNECT, connection may already be broken" }
                    }

                    // Step 2: 关闭连接（触发 cleanupConnection）
                    // G-03 修复：旧连接可能已被 gRPC/客户端关闭，onCompleted() 重复调用会抛
                    // "call already closed"，导致驱逐回调异常传播 → registerWithDeviceType 崩溃
                    // → handleLoginSuccess 中途退出 → 新登录的 token 映射/userId/投递激活全部丢失。
                    try {
                        observer.onCompleted()
                    } catch (e: Exception) {
                        logger.warn(e) { "旧连接 onCompleted 失败（连接可能已关闭），跳过" }
                    }
                }
            }
        }
    }

    companion object {
        /** gRPC 完整服务名（proto package + service name） */
        const val SERVICE_NAME = "nebula.chat.ChatService"

        private val logger = KotlinLogging.logger {}

        /** pendingBuffer 中单条消息的最大投递重试次数，超过后写入死信表（D-75） */
        const val MAX_PENDING_RETRIES = 10

        /**
         * 判断是否为"可接受的连接中断异常"——由客户端主动取消或连接正常断开引发，
         * 属连接生命周期的正常部分，不应打印完整 stacktrace（仅打印异常名与 message 即可）。
         *
         * 覆盖两类：
         * - gRPC 取消：[StatusException] / [io.grpc.StatusRuntimeException]，且 [Status.getCode] 为 [Status.Code.CANCELLED]
         * - 协程取消：[CancellationException]（连接协程被取消时沿调用链传播）
         *
         * @param cause 待判定的异常
         * @return true 表示属可接受的连接中断，可降级日志
         */
        private fun isExpectedDisconnect(cause: Throwable): Boolean {
            val status = (cause as? StatusException)?.status
            if (status?.code == Status.Code.CANCELLED) return true
            return cause is CancellationException
        }
    }

}
