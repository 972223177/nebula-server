package com.nebula.gateway.handler.user

import com.nebula.chat.user.LoginReq
import com.nebula.chat.user.LoginResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.common.log.AuditMarkers
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.session.SessionRegistry
import com.nebula.service.user.UserService
import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory
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
                // CQ-10: Token 重连审计日志
                auditLogger.info(AuditMarkers.LOGIN, "user_login | uid=${existingSession.userId} | method=token | success=true | device=${req.deviceType}")
                return buildLoginResp(existingSession.userId, existingSession.token, req)
            }
        }

        // 场景 2: 密码登录 — 委托 UserService（CQ-10: 审计日志记录成功/失败）
        return try {
            val userId = userService.loginByPassword(req)
            val token = UUID.randomUUID().toString()
            // CQ-10: 密码登录成功审计日志
            auditLogger.info(AuditMarkers.LOGIN, "user_login | uid=$userId | method=password | success=true | device=${req.deviceType}")
            buildLoginResp(userId, token, req)
        } catch (e: UserException) {
            // CQ-10: 密码登录失败审计日志
            auditLogger.warn(AuditMarkers.LOGIN, "user_login | uid=unknown | method=password | success=false | reason=${e.bizCode.name}")
            throw e
        }
    }

    /**
     * 构建登录成功响应。
     *
     * @param userId 经过密码验证或 Token 验证确认的用户 ID
     * @param token 新生成或当前有效的会话 Token
     * @param req 客户端登录请求，用于回传 deviceType 和 deviceId
     * @return 填充了 userId、token、server_now、deviceType、deviceId 的 LoginResp
     */
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

    companion object {
        /** 审计日志记录器 — 使用 SLF4J 原生 API 支持 Marker */
        private val auditLogger = LoggerFactory.getLogger(LoginHandler::class.java)
    }
}
