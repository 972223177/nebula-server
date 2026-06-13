package com.nebula.gateway.handler.user

import com.nebula.chat.user.LoginReq
import com.nebula.chat.user.LoginResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.session.SessionRegistry
import com.nebula.service.user.UserService
import java.util.UUID

/**
 * 用户登录 Handler — method = "user/login"（D-04, D-05, AUTH-01）。
 *
 * 职责：
 * - 场景 1: Token 重连（AUTH-02）— 通过 SessionRegistry.validate() 验证 Token
 * - 场景 2: 用户名+密码登录 — 委托 UserService 验证密码
 * - 登录成功后返回 LoginResp（含 token、uid、server_now、device_type、device_id）
 *
 * @param userService 用户业务服务
 * @param sessionRegistry Session 注册中心（用于 Token 验证）
 */
class LoginHandler(
    private val userService: UserService,
    private val sessionRegistry: SessionRegistry
) : Handler<LoginReq, LoginResp> {

    override val method: String = "user/login"

    override suspend fun handle(req: LoginReq): LoginResp {
        // 场景 1: Token 重连（AUTH-02）
        if (req.hasToken()) {
            val token = req.token
            val existingSession = sessionRegistry.validate(token)
            if (existingSession != null) {
                return buildLoginResp(existingSession.userId, existingSession.token, req)
            }
        }

        // 场景 2: 密码登录 — 委托 UserService
        val userId = userService.loginByPassword(req)
        val token = UUID.randomUUID().toString()
        return buildLoginResp(userId, token, req)
    }

    private fun buildLoginResp(userId: Long, token: String, req: LoginReq): LoginResp {
        return LoginResp.newBuilder()
            .setUserId(userId)
            .setUid(userId)
            .setToken(token)
            .setServerNow(System.currentTimeMillis())
            .setDeviceType(req.deviceType)
            .setDeviceId(req.deviceId)
            .build()
    }
}
