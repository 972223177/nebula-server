package com.nebula.gateway.dispatcher

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.interceptor.Interceptor
import com.google.protobuf.ByteString
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 请求分发器 — Pipeline 编排入口（D-14, D-15）。
 *
 * 职责：
 * - 接收 Envelope Request，根据 method 查找 Handler
 * - 通过 ProtoCodec 反序列化请求参数
 * - 通过 Interceptor Pipeline 链执行请求处理
 * - 序列化结果为 Response 返回
 *
 * 设计决策引用：
 * - D-14: 返回完整 Response proto，不直接操作 StreamObserver
 * - D-15: dispatch() 签名：suspend fun dispatch(envelopeRequest: Request): Response
 *
 * @param handlerRegistry Handler 注册中心
 * @param interceptors 拦截器列表（按 D-07 顺序注入）
 * @param protoCodec Proto 编解码器
 */
class Dispatcher(
    private val handlerRegistry: HandlerRegistry,
    private val interceptors: List<Interceptor>,
    private val protoCodec: ProtoCodec = ProtoCodec,
    /** G-04: 单次请求处理超时（毫秒），含拦截器链 + Handler 执行 */
    private val dispatchTimeoutMs: Long = DEFAULT_DISPATCH_TIMEOUT_MS
) {

    /**
     * 分发请求并返回响应。
     *
     * 执行流程：
     * 1. 根据 method 查找 Handler（查 [HandlerRegistry]）
     * 2. 通过 [ProtoCodec] 反序列化 params bytes
     * 3. 构建 Interceptor Pipeline（foldRight 构建责任链）
     * 4. 执行 Pipeline，序列化结果
     *
     * @param envelopeRequest 客户端请求
     * @return 处理后的 Response
     */
    suspend fun dispatch(envelopeRequest: Request): Response {
        val method = envelopeRequest.method

        // Step 1: 查找 Handler
        val entry = handlerRegistry.get(method)
            ?: return Response.newBuilder()
                .setCode(BizCode.NOT_FOUND.code)
                .setMsg("method not found: $method")
                .build()

        // Step 2: 反序列化请求参数（CQ-05: 捕获反序列化异常返回错误响应）
        @Suppress("UNCHECKED_CAST")
        val req = try {
            protoCodec.deserialize(entry, envelopeRequest.params)
        } catch (e: Exception) {
            logger.error(e) { "请求参数反序列化失败: method=$method" }
            return Response.newBuilder()
                .setCode(BizCode.INVALID_PARAM.code)
                .setMsg("请求参数反序列化失败: ${e.message}")
                .build()
        }

        // Step 3: 构建 Pipeline 链尾 — 最终调用 Handler
        // 使用 var 记录拦截器链中最新传入的 request；
        // 当拦截器修改了 request 并调用 chain.proceed(modifiedRequest) 时，
        // 后续代码应使用传入的参数而非闭包捕获的 envelopeRequest（CQ-17）
        var currentRequest = envelopeRequest
        val handlerChain: Interceptor.Chain = object : Interceptor.Chain {
            override val request: Request get() = currentRequest

            override suspend fun proceed(request: Request): Response {
                currentRequest = request
                @Suppress("UNCHECKED_CAST")
                val result = (entry.handler as Handler<Any, Any>).handle(req)
                val resultBytes = protoCodec.serialize(entry, result)
                return Response.newBuilder()
                    .setCode(BizCode.OK.code)
                    .setMethod(request.method) // 使用链传入的 request.method，允许拦截器修改路由目标
                    .setResult(ByteString.copyFrom(resultBytes))
                    .build()
            }
        }

        // Step 4: 折叠拦截器链；若 interceptors 为空则直接调用 handlerChain
        val pipeline: Interceptor.Chain = if (interceptors.isEmpty()) {
            handlerChain
        } else {
            interceptors.foldRight<Interceptor, Interceptor.Chain>(handlerChain) { interceptor, chain ->
                InterceptorChain(interceptor, chain)
            }
        }

        // G-04: 超时保护，防止慢 Handler 无限占用协程和 DB 连接
        return try {
            withTimeout(dispatchTimeoutMs) {
                pipeline.proceed(envelopeRequest)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn { "请求处理超时 method=$method timeout=${dispatchTimeoutMs}ms" }
            Response.newBuilder()
                .setCode(BizCode.INTERNAL_ERROR.code)
                .setMsg("request timeout")
                .setMethod(method)
                .build()
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}

        /** G-04: 默认请求处理超时 10s（含拦截器链 + Handler） */
        private const val DEFAULT_DISPATCH_TIMEOUT_MS = 10_000L
    }
}
