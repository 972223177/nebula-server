package com.nebula.gateway.handler.user

import com.nebula.chat.Response
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.redis.PrivacyRepository
import kotlin.coroutines.coroutineContext

/**
 * 在线状态可见性设置 Handler — method = "user/setPrivacy"（BIZ-USER-05, D-09, D-11）。
 *
 * 设置当前用户的在线状态是否对其他人可见。
 *
 * **写入策略（D-09/D-11）：**
 * [PrivacyRepository.setHideOnlineStatus()] 先写 Redis（立即生效），
 * 再异步 CoroutineScope(Dispatchers.IO).launch 刷 MySQL（best-effort 模式）。
 *
 * **异步 MySQL 刷写是 best-effort 模式（Pitfall 4）：**
 * - 服务器 crash 在 Redis 写完后、MySQL 刷完前，最后一次隐私设置丢失
 * - 重启后首次 [PrivacyRepository.getHideOnlineStatus()] 从 MySQL 回退读取，可恢复持久化状态
 * - 此权衡已被接受：隐私设置变更频率低，Redis 为主要存储，MySQL 为持久化备份
 *
 * **安全约束（T-05-10）：**
 * 只允许修改当前登录用户的隐私设置——userId 从 [Session] 获取（通过 [requireSession]），
 * 非请求参数传入，防止越权修改他人隐私设置。
 *
 * @param privacyRepository 用户隐私设置缓存操作
 */
class SetPrivacyHandler(
    private val privacyRepository: PrivacyRepository
) : Handler<SetPrivacyReq, Response> {

    /** method 路由：user/setPrivacy — 设置在线状态可见性 */
    override val method: String = "user/setPrivacy"

    /**
     * 设置当前用户的在线状态可见性。
     *
     * @param req 包含 hideOnlineStatus 布尔值的请求
     * @return 通用响应，仅包含状态码
     */
    override suspend fun handle(req: SetPrivacyReq): Response {
        val session = coroutineContext.requireSession()
        privacyRepository.setHideOnlineStatus(session.userId, req.hideOnlineStatus)
        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
