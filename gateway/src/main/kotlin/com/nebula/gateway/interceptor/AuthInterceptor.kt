package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.SessionRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

/**
 * 认证拦截器 — 验证请求的 Session Token 并注入 Session 到协程上下文（D-03, D-08, D-09）。
 *
 * 职责：
 * - 白名单（skipMethods）中的方法直接跳过认证（D-30），当前仅包含 "system/ping"
 * - 未携带 Token 的请求返回 UNAUTHORIZED(1001)
 * - Token 无效/过期返回 TOKEN_INVALID(1101)
 * - 认证通过后通过 `withContext(SessionKey(session))` 将 Session 注入协程上下文（D-03）
 * - Session 注入后，Handler 可通过 `coroutineContext.requireSession()` 获取 Session
 *
 * @param sessionRegistry Session 注册中心，用于验证 Token 的有效性
 * @param skipMethods 跳过认证的方法白名单，默认包含 "system/ping"
 */
open class AuthInterceptor(
    private val sessionRegistry: SessionRegistry,
    private val skipMethods: Set<String> = setOf("system/ping")
) : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        val method = request.method

        // D-30: 白名单方法跳过认证
        if (method in skipMethods) {
            return chain.proceed(request)
        }

        // 从 Request 中提取 Token
        val token = extractToken(request)
            ?: return Response.newBuilder()
                .setCode(BizCode.UNAUTHORIZED.code)
                .setMsg(BizCode.UNAUTHORIZED.msg)
                .build()

        // 验证 Session
        val session = sessionRegistry.validate(token)
            ?: return Response.newBuilder()
                .setCode(BizCode.TOKEN_INVALID.code)
                .setMsg(BizCode.TOKEN_INVALID.msg)
                .build()

        // D-03: 注入 Session 到 CoroutineContext
        return withContext(SessionKey(session)) {
            chain.proceed(request)
        }
    }

    /**
     * 从请求中提取 Token。
     *
     * TODO: Phase 5 确定 Token 传递方式（如 Envelope metadata 或 params 中）后实现具体提取逻辑。
     * 当前返回 null，确保未实现提取逻辑时安全地返回 UNAUTHORIZED。
     *
     * @param request 客户端请求
     * @return Token 字符串，若无法提取则返回 null
     */
    open fun extractToken(request: Request): String? {
        return null
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
