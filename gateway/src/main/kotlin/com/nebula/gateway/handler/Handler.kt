package com.nebula.gateway.handler

/**
 * 泛型 Handler 接口，所有业务 Handler 必须实现此接口。
 *
 * Session 通过 CoroutineContext 隐式传递，Handler 内部通过 `coroutineContext.requireSession()` 获取：
 * ```kotlin
 * val session = coroutineContext.requireSession()
 * ```
 *
 * ⚠️ Pitfall: Handler 内部若使用 `launch { }` 或 `withContext(Dispatchers.Default)` 启动新协程，
 * Session 在新协程的 CoroutineContext 中不可见。必须使用 `coroutineScope { }` / `supervisorScope { }`
 * 结构化并发保持上下文传递，或显式传递 `coroutineContext + SessionKey(session)`。
 * 详见 RESEARCH.md Pitfall 1。
 *
 * @param Req 请求 Proto 消息类型
 * @param Resp 响应 Proto 消息类型
 */
interface Handler<Req : Any, Resp : Any> {

    /** 当前 Handler 对应的 method 路由字符串，如 "system/ping"、"user/login" */
    val method: String

    /**
     * 处理业务请求。
     *
     * Session 从 CoroutineContext 获取（由 AuthInterceptor 在认证通过后通过 withContext 注入）：
     * ```kotlin
     * val session = coroutineContext.requireSession()
     * ```
     *
     * @param req 反序列化后的请求参数
     * @return 业务处理结果，由 Dispatcher 序列化为 bytes
     */
    suspend fun handle(req: Req): Resp
}
