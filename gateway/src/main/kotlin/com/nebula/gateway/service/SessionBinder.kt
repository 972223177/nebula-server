package com.nebula.gateway.service

import com.nebula.chat.Envelope
import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.chat.user.LoginResp
import com.nebula.chat.user.RegisterReq
import com.nebula.chat.user.RegisterResp
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.service.user.OnlineStatusService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*

/**
 * 会话绑定编排器（D-05, D-67, CQ-13）。
 *
 * 从 ChatService.handleLoginSuccess / handleRegisterSuccess / bindSession 抽离。
 * 负责把登录/注册成功响应转换为 Session 并完成注册 + StreamObserver 绑定 + 在线状态通知 + 投递激活。
 *
 * 设计决策：
 * - ChatStreamObserver 仍是 ChatService 的 inner class（连接所有权在 ChatService），本组件只在方法参数"借用"
 *   observer 去写 userId/token/deviceType 与 activateDelivery()，不持有它，不违反分层。
 * - tokenToObserver 是 ChatService 级跨连接映射，以引用形式注入本组件，由本组件在绑定/互踢时更新。
 * - serverScope 由 ChatService 统管生命周期，本组件仅在绑定成功后启动在线状态通知协程。
 * - 响应 Envelope 发送已上移到 ChatService（Phase 2 经 ResponseInterceptor 链后统一发送），本组件只做绑定副作用。
 *
 * @param sessionRegistry Session 注册中心（含设备类型互踢逻辑）
 * @param userStreamRegistry 用户 StreamObserver 注册中心（D-01）
 * @param onlineStatusService 在线状态服务（D-57）
 * @param friendStatusNotifier 好友状态广播器（D-57 在线/离线通知）
 * @param serverScope 服务级后台作用域（跨连接存活）
 * @param tokenToObserver token → StreamObserver 跨连接映射（互踢时清理旧 token）
 */
internal class SessionBinder(
    private val sessionRegistry: SessionRegistry,
    private val userStreamRegistry: UserStreamRegistry,
    private val onlineStatusService: OnlineStatusService,
    private val friendStatusNotifier: FriendStatusNotifier,
    private val serverScope: CoroutineScope,
    private val tokenToObserver: MutableMap<String, StreamObserver<Envelope>>,
) {
    private companion object {
        val logger = KotlinLogging.logger {}
    }

    /**
     * 处理登录成功响应（D-05 绑定流程）。
     *
     * 从 LoginResp 中直接读取 deviceType/deviceId（Review 修复：无需重新解析 Request.params）。
     * 调用 [bind] 完成 Session 注册 + StreamObserver 绑定。
     * 响应 Envelope 由 ChatService 经 ResponseInterceptor 链后统一发送（Phase 2），本组件只做绑定。
     *
     * @param response 登录成功响应（code=BizCode.OK.code）
     * @param observer 当前连接的 StreamObserver
     */
    suspend fun bindOnLoginSuccess(response: Response, observer: ConnectionContext) {
        val loginResp = LoginResp.parseFrom(response.result.toByteArray())
        logger.info { "登录成功，绑定 Session: userId=${loginResp.userId}, deviceType=${loginResp.deviceType.name}" }

        val session = Session(
            userId = loginResp.userId,
            token = loginResp.token,
            deviceType = loginResp.deviceType.name,
            deviceId = loginResp.deviceId,
            connectionId = UUID.randomUUID().toString()
        )
        bind(session, loginResp.userId, observer)
    }

    /**
     * 处理注册成功响应 — 注册即登录（CQ-13）。
     *
     * 注册成功后自动完成 Session 绑定，客户端无需二次调用 user/login。
     * 与 [bindOnLoginSuccess] 共享相同的 Session 注册 + StreamObserver 绑定流程（通过 [bind]）。
     *
     * 注意：RegisterReq 中不含 deviceType 字段时（老客户端兼容），使用默认 MOBILE 类型。
     *
     * @param response 注册成功响应（code=BizCode.OK.code）
     * @param observer 当前连接的 StreamObserver
     * @param request 客户端原始注册请求（含 device_type、device_id 等设备信息）
     */
    suspend fun bindOnRegisterSuccess(response: Response, observer: ConnectionContext, request: Request) {
        // 反序列化 RegisterResp（获取 uid + token）
        val registerResp = RegisterResp.parseFrom(response.result.toByteArray())
        // 反序列化 RegisterReq（获取 device_type + device_id，用于 Session 创建）
        val registerReq = RegisterReq.parseFrom(request.params)

        // CQ-13: 构建 Session，device_type 来自注册请求（兼容老客户端默认 MOBILE）
        val deviceTypeName = if (registerReq.deviceTypeValue != 0) registerReq.deviceType.name else "MOBILE"
        logger.info { "注册成功，绑定 Session: uid=${registerResp.uid}, deviceType=$deviceTypeName" }

        val session = Session(
            userId = registerResp.uid,
            token = registerResp.token,
            deviceType = deviceTypeName,
            deviceId = registerReq.deviceId,
            connectionId = UUID.randomUUID().toString()
        )
        bind(session, registerResp.uid, observer)
    }

    /**
     * 绑定 Session 并完成 StreamObserver 注册 + 在线状态通知 + 投递激活（D-05, D-67, CQ-13）。
     *
     * 由 [bindOnLoginSuccess] 与 [bindOnRegisterSuccess] 共享，避免重复代码。
     * 响应 Envelope 发送已上移到 ChatService（Phase 2），本方法只负责绑定副作用。
     *
     * 流程：
     * 1. 注册 Session 到 SessionRegistry（同类型设备互踢，返回被驱逐的旧 token）
     * 2. 更新 tokenToObserver 映射（清理旧 token，设置新映射）
     * 3. 设置 userId/token/deviceType，取消旧延迟离线任务
     * 4. 注册到 UserStreamRegistry（D-01）
     * 5. 标记在线 + 推送状态变更给好友（D-57, D-85 serverScope 跨连接存活）
     * 6. 激活缓存再投递（D-67：驱逐场景下需等待旧连接清理；首次登录直接激活）
     *
     * @param session 待注册的 Session
     * @param uid 用户 ID（用于在线状态推送和 UserStreamRegistry）
     * @param observer 当前连接的 StreamObserver
     */
    private suspend fun bind(
        session: Session,
        uid: Long,
        observer: ConnectionContext
    ) {
        // 注册 Session（同类型设备互踢，返回被驱逐的旧 token）
        val evictedToken = sessionRegistry.registerWithDeviceType(session)
        if (evictedToken != null) {
            logger.info { "同类型设备互踢: userId=${session.userId}, deviceType=${session.deviceType}, evictedToken=${evictedToken.take(8)}..." }
        }

        // 更新 tokenToObserver：清理旧 token 映射，设置新映射
        if (evictedToken != null) {
            tokenToObserver.remove(evictedToken)
        }
        tokenToObserver[session.token] = observer

        // D-01: 注册 StreamObserver 到 UserStreamRegistry（REVIEW-MEDIUM-7: 使用 require 替代 as? 静默转换）
        observer.userId = uid

        // CQ-05: 记录 token 用于连接断开时清理 SessionRegistry
        observer.token = session.token
        // H2: 记录设备类型，用于断连清理
        observer.deviceType = session.deviceType

        // D-57: 重连时取消旧的延迟离线任务
        observer.delayedOfflineJob?.cancel()

        userStreamRegistry.register(uid, observer)

        // D-57: 标记在线 + 推送状态变更给所有好友。D-85: serverScope 保证跨连接存活
        serverScope.launch {
            withContext(Dispatchers.IO) {
                onlineStatusService.setOnline(uid)
            }
            friendStatusNotifier.notifyFriends(uid, 1)
        }

        // D-67: 激活缓存再投递
        // 不论 evictedToken 是否为 null，均需 activateDelivery() 来 flush pendingBuffer：
        // - evictedToken != null: 同类型设备互踢，旧连接清理期间 push 进缓存，需 flush
        // - evictedToken == null: register 后、deliveryActive=true 设置前的窗口期内，
        //   若有 push（如 FRIEND_REQUEST）通过 PushService 命中本 observer，会因 deliveryActive=false
        //   而被缓存到 pendingBuffer。若仅设标志不 flush，该消息将永久丢失。
        //   修复点：之前 else 分支仅 `observer.deliveryActive = true`，未 flush 缓存 → BUG
        observer.activateDelivery()
    }
}
