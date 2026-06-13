package com.nebula.gateway.service

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
import com.nebula.chat.Message
import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.friend.StatusChangedPayload
import com.nebula.chat.user.LoginResp
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.push.PushService
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
 */
class ChatService(
    private val dispatcher: Dispatcher,
    private val sessionRegistry: SessionRegistry,
    private val registry: HandlerRegistry,
    private val userStreamRegistry: UserStreamRegistry,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val friendshipRepository: FriendshipRepository,
    private val pushService: PushService,
    private val privacyRepository: PrivacyRepository
) : BindableService {

    /** token → StreamObserver 映射，用于 eviction callback 查找对应连接推送 LOGOUT */
    private val tokenToObserver = ConcurrentHashMap<String, StreamObserver<Envelope>>()

    /** 标记 eviction callback 是否已注册，确保只注册一次 */
    private var evictionCallbackRegistered = false

    /**
     * ChatService 协程作用域 — 用于桥接 gRPC 回调线程与协程。
     *
     * gRPC 的 StreamObserver.onNext() 在 gRPC worker 线程调用，而 Dispatcher.dispatch() 是 suspend 函数。
     * 通过此作用域启动协程执行异步操作。
     */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
     * @param responseObserver gRPC 响应观察者，用于发送响应消息给客户端
     */
    private inner class ChatStreamObserver(
        private val responseObserver: StreamObserver<Envelope>
    ) : StreamObserver<Envelope> {

        /** 用户 ID（REVIEW-MEDIUM-7: 显式声明可空字段，清理时检查非空）。由 handleLoginSuccess 设置。 */
        var userId: Long? = null

        /** 60s 延迟离线任务（D-57），重连时取消旧任务防止泄漏 */
        var delayedOfflineJob: Job? = null

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
            cleanupConnection()
            responseObserver.onCompleted()
        }

        override fun onError(t: Throwable) {
            logger.error(t) { "ChatService stream error" }
            cleanupConnection()
            responseObserver.onError(t)
        }

        /**
         * 清理此连接持有的所有资源（Review 修复：防止内存泄漏）。
         *
         * 清理操作：
         * 1. 从 tokenToObserver 移除当前 responseObserver 的所有 token 映射
         * 2. 从 UserStreamRegistry 移除当前设备的 StreamObserver（D-01）
         * 3. 启动 60s 延迟离线任务，到期后检查无剩余设备则标记离线 + 推送（D-57）
         */
        private fun cleanupConnection() {
            tokenToObserver.entries.removeIf { it.value == responseObserver }
            // D-01: 移除当前设备 StreamObserver（不调 removeUser 以免移除其他设备的流）
            userId?.let { uid ->
                userStreamRegistry.removeStream(uid, responseObserver)
            }

            // D-57: 60s 延迟离线任务（伪在线）
            userId?.let { uid ->
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
     * 通过 tokenToObserver 查找当前连接，推送 LOGOUT 通知并关闭连接。
     */
    private fun ensureEvictionCallbackRegistered() {
        if (!evictionCallbackRegistered) {
            evictionCallbackRegistered = true
            sessionRegistry.onEviction { token ->
                val observer = tokenToObserver.remove(token)
                if (observer != null) {
                    // 构建 PUSH LOGOUT Envelope
                    val logoutEnvelope = Envelope.newBuilder()
                        .setDirection(Direction.PUSH)
                        .setRequestId("")  // 系统推送无 request_id
                        .setMessage(Message.newBuilder()
                            .setEventType(PushEventType.LOGOUT)
                            .setContent("相同设备类型在其他地方登录")
                            .build())
                        .build()
                    observer.onNext(logoutEnvelope)
                    observer.onCompleted()
                }
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
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
