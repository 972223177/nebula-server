package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 日志拦截器 — 记录请求处理方法、耗时和响应状态（D-08）。
 *
 * 职责：
 * - 在请求处理前记录开始时间
 * - 请求处理完成后计算耗时
 * - 成功（code=BizCode.OK.code）使用 info 级别记录
 * - 失败（code!=BizCode.OK.code）使用 warn 级别记录，附带错误码和消息
 *
 * 此拦截器位于 AuthInterceptor 之后，RateLimitInterceptor 之前（D-07）。
 * 心跳请求（system/ping）通过 AuthInterceptor.skipMethods 跳过，同时也不会到达 LogInterceptor。
 */
class LogInterceptor : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        val start = System.currentTimeMillis()
        val resp = chain.proceed(request)
        val elapsed = System.currentTimeMillis() - start

        if (resp.code != BizCode.OK.code) {
            log.warn { "[${request.method}] failed: code=${resp.code} msg=${resp.msg} (${elapsed}ms)" }
        } else {
            log.info { "[${request.method}] success (${elapsed}ms)" }
        }

        return resp
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
