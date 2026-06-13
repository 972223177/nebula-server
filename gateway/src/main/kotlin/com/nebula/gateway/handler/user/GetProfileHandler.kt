package com.nebula.gateway.handler.user

import com.nebula.chat.user.GetProfileReq
import com.nebula.chat.user.GetProfileResp
import com.nebula.gateway.handler.Handler
import com.nebula.service.user.UserService

/**
 * 用户详细资料查询 Handler — method = "user/getProfile"（BIZ-USER-02）。
 *
 * 委托 UserService 处理资料查询。
 *
 * @param userService 用户业务服务
 */
class GetProfileHandler(
    private val userService: UserService
) : Handler<GetProfileReq, GetProfileResp> {

    override val method: String = "user/getProfile"

    override suspend fun handle(req: GetProfileReq): GetProfileResp {
        return userService.getProfile(req.uid)
    }
}
