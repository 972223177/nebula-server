package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException

/**
 * 异常拦截器 — 三态异常映射（D-08, D-10）。
 *
 * 作为 Pipeline 链尾包裹 Handler（D-07），捕获所有异常并映射为结构化 Response。
 * 三态映射策略（D-10）：
 * - [BizException] → 使用异常携带的业务状态码（如 USER_NOT_FOUND → 1200）
 * - [IllegalArgumentException] → BAD_REQUEST(1000)（参数校验异常）
 * - 其他未预期异常 → INTERNAL_ERROR(9000)，不向客户端暴露堆栈细节
 *
 * 未预期异常仅通过 logger.error 写入服务端日志用于排查，客户端仅收到 9000 + "internal error"。
 */
class ExceptionInterceptor : Interceptor {

    override suspend fun intercept(request: Request, chain: Interceptor.Chain): Response {
        return try {
            chain.proceed(request)
        } catch (e: BizException) {
            // D-10: BizException → 业务状态码
            Response.newBuilder()
                .setCode(e.bizCode.code)
                .setMsg(e.message ?: e.bizCode.msg)
                .setMethod(request.method)
                .build()
        } catch (e: IllegalArgumentException) {
            // D-10: 参数异常 → BAD_REQUEST(1000)
            logger.warn(e) { "Illegal argument for method ${request.method}" }
            Response.newBuilder()
                .setCode(BizCode.INVALID_PARAM.code)
                .setMsg(e.message ?: BizCode.INVALID_PARAM.msg)
                .setMethod(request.method)
                .build()
        } catch (e: CancellationException) {
            // 重新抛出以传播协程取消信号，避免协程被异常捕获阻断
            throw e
        } catch (e: Exception) {
            // D-10: 未预期异常 → INTERNAL_ERROR(9000)，不暴露堆栈
            logger.error(e) { "Unhandled exception for method ${request.method}" }
            Response.newBuilder()
                .setCode(BizCode.INTERNAL_ERROR.code)
                .setMsg(BizCode.INTERNAL_ERROR.msg)
                .setMethod(request.method)
                .build()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
