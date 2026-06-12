package com.nebula.gateway.dispatcher

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.interceptor.Interceptor
import com.google.protobuf.ByteString
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 请求分发器 — Pipeline 编排入口（D-02, D-14, D-15）。
 *
 * 职责：
 * - 接收 Envelope Request，根据 method 查找 Handler
 * - 通过 ProtoCodec 反序列化请求参数
 * - 通过 Interceptor Pipeline 链执行请求处理
 * - 序列化结果为 Response 返回
 *
 * 设计决策引用：
 * - D-02: CoroutineScope(Dispatchers.IO + SupervisorJob) 全局单作用域
 * - D-04: CoroutineExceptionHandler 兜底防止 JVM 崩溃
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
    private val protoCodec: ProtoCodec = ProtoCodec
) {

    /**
     * 全局 Dispatcher 作用域 — ChatServer 级别生命周期。
     *
     * [SupervisorJob] 隔离单个 Handler 异常，[CoroutineExceptionHandler] 兜底防止 JVM 崩溃。
     * 使用 `warn` 级别避免与 ExceptionInterceptor 的 `error` 日志重复。
     */
    @Suppress("unused")
    private val scope = CoroutineScope(
        Dispatchers.IO +
        SupervisorJob() +
        CoroutineExceptionHandler { _, e ->
            logger.warn(e) { "Unhandled exception in dispatcher scope (already handled by ExceptionInterceptor)" }
        }
    )

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

        // Step 2: 反序列化请求参数
        @Suppress("UNCHECKED_CAST")
        val req = protoCodec.deserialize(entry, envelopeRequest.params)

        // Step 3: 构建 Pipeline 链尾 — 最终调用 Handler
        val handlerChain: Interceptor.Chain = object : Interceptor.Chain {
            override val request: Request get() = envelopeRequest

            override suspend fun proceed(request: Request): Response {
                @Suppress("UNCHECKED_CAST")
                val result = (entry.handler as Handler<Any, Any>).handle(req)
                val resultBytes = protoCodec.serialize(entry, result)
                return Response.newBuilder()
                    .setCode(BizCode.OK.code)
                    .setMethod(method)
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

        return pipeline.proceed(envelopeRequest)
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
