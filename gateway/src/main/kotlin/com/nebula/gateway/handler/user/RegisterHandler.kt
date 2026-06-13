package com.nebula.gateway.handler.user

import com.nebula.chat.user.RegisterReq
import com.nebula.chat.user.RegisterResp
import com.nebula.gateway.handler.Handler
import com.nebula.service.user.UserService

/**
 * 用户注册 Handler — method = "user/register"（D-01, D-02, AUTH-01）。
 *
 * 职责：
 * - 委托 UserService 处理注册逻辑（参数校验、密码哈希、ID 生成、持久化）
 * - 返回 RegisterResp（含 uid）
 *
 * @param userService 用户业务服务
 */
class RegisterHandler(
    private val userService: UserService
) : Handler<RegisterReq, RegisterResp> {

    override val method: String = "user/register"

    override suspend fun handle(req: RegisterReq): RegisterResp {
        val uid = userService.register(req)
        return RegisterResp.newBuilder()
            .setUid(uid)
            .build()
    }
}
