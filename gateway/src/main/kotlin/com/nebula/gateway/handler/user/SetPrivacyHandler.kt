package com.nebula.gateway.handler.user

import com.nebula.chat.Response
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import kotlinx.coroutines.currentCoroutineContext

/**
 * 在线状态可见性设置 Handler — method = "user/setPrivacy"（BIZ-USER-05, D-09, D-11, D-57）。
 *
 * 设置当前用户的在线状态是否对其他人可见。
 * 切换隐藏状态时同步更新 OnlineStatusRepository（D-57）。
 *
 * **写入策略（D-09/D-11）：**
 * [PrivacyRepository.setHideOnlineStatus()] 先写 Redis（立即生效），
 * 再异步 CoroutineScope(Dispatchers.IO).launch 刷 MySQL（best-effort 模式）。
 *
 * **安全约束（T-05-10）：**
 * 只允许修改当前登录用户的隐私设置。
 *
 * @param privacyRepository 用户隐私设置缓存操作
 * @param onlineStatusRepository 在线状态 Redis 操作（D-57）
 * @param pushService 推送服务
 */
class SetPrivacyHandler(
    private val privacyRepository: PrivacyRepository,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val pushService: PushService
) : Handler<SetPrivacyReq, Response> {

    /** method 路由：user/setPrivacy — 设置在线状态可见性 */
    override val method: String = "user/setPrivacy"

    /**
     * 设置当前用户的在线状态可见性，同步更新 Redis 状态（D-57）。
     *
     * @param req 包含 hideOnlineStatus 布尔值的请求
     * @return 通用响应，仅包含状态码
     */
    override suspend fun handle(req: SetPrivacyReq): Response {
        val session = currentCoroutineContext().requireSession()
        privacyRepository.setHideOnlineStatus(session.userId, req.hideOnlineStatus)

        // D-57: 切换隐藏状态时同步更新 OnlineStatusRepository
        if (req.hideOnlineStatus) {
            onlineStatusRepository.setHidden(session.userId)
        } else {
            onlineStatusRepository.setOnline(session.userId)
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
