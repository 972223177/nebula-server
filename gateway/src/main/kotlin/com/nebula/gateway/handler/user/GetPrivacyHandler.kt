package com.nebula.gateway.handler.user

import com.nebula.chat.user.GetPrivacyReq
import com.nebula.chat.user.GetPrivacyResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.redis.PrivacyRepository
import kotlin.coroutines.coroutineContext

/**
 * 在线状态可见性读取 Handler — method = "user/getPrivacy"（BIZ-USER-06, D-11）。
 *
 * 读取当前用户的在线状态是否对其他人可见。
 *
 * **读取策略（D-11）：**
 * [PrivacyRepository.getHideOnlineStatus()] 优先读 Redis，未命中则从 MySQL 读取并写回 Redis。
 * 此策略也是 Pitfall 4（异步 MySQL 丢失）的恢复机制——重启后首次读取可恢复持久化状态。
 *
 * **注意：**
 * 使用空的 [GetPrivacyReq] 入参，userId 从 [Session] 获取（通过 [requireSession]）。
 *
 * @param privacyRepository 用户隐私设置缓存操作
 */
class GetPrivacyHandler(
    private val privacyRepository: PrivacyRepository
) : Handler<GetPrivacyReq, GetPrivacyResp> {

    /** method 路由：user/getPrivacy — 读取在线状态可见性设置 */
    override val method: String = "user/getPrivacy"

    /**
     * 读取当前用户的隐私设置。
     *
     * @param req 空请求体，userId 从 Session 获取
     * @return 当前用户的 hideOnlineStatus 设置
     */
    override suspend fun handle(req: GetPrivacyReq): GetPrivacyResp {
        val session = coroutineContext.requireSession()
        val hideOnlineStatus = privacyRepository.getHideOnlineStatus(session.userId)
        return GetPrivacyResp.newBuilder()
            .setHideOnlineStatus(hideOnlineStatus)
            .build()
    }
}
