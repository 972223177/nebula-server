package com.nebula.gateway.handler.user

import com.nebula.chat.user.GetPrivacyReq
import com.nebula.chat.user.GetPrivacyResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.user.UserPrivacyService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 在线状态可见性读取 Handler — method = "user/getPrivacy"（BIZ-USER-06, D-11）。
 *
 * 委托 UserPrivacyService 查询隐私设置。
 *
 * @param userPrivacyService 用户隐私设置业务服务
 */
class GetPrivacyHandler(
    private val userPrivacyService: UserPrivacyService
) : Handler<GetPrivacyReq, GetPrivacyResp> {

    override val method: String = "user/getPrivacy"

    override suspend fun handle(req: GetPrivacyReq): GetPrivacyResp {
        val session = currentCoroutineContext().requireSession()
        return userPrivacyService.getHideOnlineStatus(session.userId, req)
    }
}
