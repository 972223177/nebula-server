package com.nebula.gateway.service

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 登录绑定响应拦截器（D-05, CQ-13）。
 *
 * 把"user/login / user/register 成功响应 → 绑定 Session"的逻辑从 ChatService 抽离为可插拔拦截器。
 * 仅当响应 code == BizCode.OK.code 时执行绑定，否则透传 Response。
 *
 * @param sessionBinder 会话绑定编排器（Phase 1 抽离）
 */
internal class LoginBindingInterceptor(
    private val sessionBinder: SessionBinder,
) : ResponseInterceptor {
    private companion object {
        val logger = KotlinLogging.logger {}
    }

    override suspend fun afterResponse(
        response: Response,
        observer: ConnectionContext,
        request: Request
    ): Response {
        when (response.method) {
            "user/login" -> {
                if (response.code == BizCode.OK.code) {
                    logger.info { "[stream] #${observer.connId} 拦截登录成功响应，开始 Session 绑定" }
                    sessionBinder.bindOnLoginSuccess(response, observer)
                }
            }
            "user/register" -> {
                if (response.code == BizCode.OK.code) {
                    logger.info { "[stream] #${observer.connId} 拦截注册成功响应，开始 Session 绑定（注册即登录）" }
                    sessionBinder.bindOnRegisterSuccess(response, observer, request)
                }
            }
        }
        return response
    }
}
