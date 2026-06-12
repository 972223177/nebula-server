package com.nebula.gateway.handler

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.common.BizCode

/**
 * 应用层心跳 Handler — method = "system/ping"（D-28）。
 *
 * 双重心跳策略（D-27）的业务层组件：
 * - gRPC keepalive（传输层）：快速检测 TCP 断开，解决"连接是否存活"问题
 * - 应用层 PING/PONG（业务层）：检测 NAT/代理导致的半开连接，解决"服务是否健康"问题
 *   应用层 PING 与业务消息走在同一数据通道上，端到端真实状态检测
 *
 * D-30: AuthInterceptor 和 LogInterceptor 通过 skipMethods 跳过此 Handler，心跳请求不经过认证/日志拦截。
 * D-29: 客户端 30~60s（含 Jitter）发送 PING，服务端 90s 无 PING 断开连接。
 * D-32: 时间参数加入随机 Jitter 防惊群效应。
 *
 * ⚠️ Pitfall 警告（RESEARCH.md Pitfall 1）：Handler 内部若使用 `launch { }` 或 `withContext(Dispatchers.Default)` 启动新协程，
 * Session（通过 CoroutineContext 传递）在新协程中不可见。如需异步操作，使用 `coroutineScope { }` / `supervisorScope { }` 结构化并发。
 */
class PingHandler : Handler<Request, Response> {

    /** method 路由：system/ping — 应用层心跳探测 */
    override val method: String = "system/ping"

    /**
     * 处理心跳请求，返回 pong 响应。
     *
     * 返回固定 code=200 msg="pong" 的 Response，不依赖任何业务状态。
     * 客户端收到此响应即说明整个请求链路（Dispatcher + Pipeline + Handler）正常工作。
     *
     * @param req 客户端的心跳请求（不携带业务 payload）
     * @return code=200 msg="pong" 的响应
     */
    override suspend fun handle(req: Request): Response {
        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("pong")
            .setMethod(method)
            .build()
    }
}
