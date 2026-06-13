package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.SessionRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.withContext

/**
 * 认证拦截器 — 验证请求的 Session Token 并注入 Session 到协程上下文（D-03, D-08, D-09, D-77）。
 *
 * 职责：
 * - 白名单（skipMethods）中的方法直接跳过认证（D-30），使用前缀匹配（D-77），
 *   当前包括 "system/ping" 和所有 "admin/" 前缀的方法
 * - 未携带 Token 的请求返回 UNAUTHORIZED(1001)
 * - Token 无效/过期返回 TOKEN_INVALID(1101)
 * - 认证通过后通过 `withContext(SessionKey(session))` 将 Session 注入协程上下文（D-03）
 * - Session 注入后，Handler 可通过 `coroutineContext.requireSession()` 获取 Session
 *
 * @param sessionRegistry Session 注册中心，用于验证 Token 的有效性
 * @param skipMethods 跳过认证的方法白名单（D-77：前缀匹配），默认包含 "system/ping" 和 "admin/"
 */
open class AuthInterceptor(
    private val sessionRegistry: SessionRegistry,
    private val skipMethods: Set<String> = setOf("system/ping", "admin/")
) : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        val method = request.method

        // D-30, D-77: 白名单方法跳过认证（前缀匹配，支持 admin/ 等路径前缀）
        if (skipMethods.any { method.startsWith(it) }) {
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
     * 从请求的 metadata map 中提取 Token（D-04 推荐方式）。
     *
     * Token 通过 Request.metadata 传递，key 为 "authorization"。
     * 客户端应在每个认证请求的 Request.metadata 中设置 "authorization" → token。
     * login/register 请求走 skipMethods，不经过此方法。
     *
     * @param request 客户端请求
     * @return Token 字符串，若未携带则返回 null
     */
    open fun extractToken(request: Request): String? {
        val metadataMap = request.metadataMap
        val token = metadataMap["authorization"]
        if (token.isNullOrBlank()) return null
        return token
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
