package com.nebula.gateway.dispatcher

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.gateway.interceptor.Interceptor

/**
 * 非尾结点的链节 — 单链表节点（D-06）。
 *
 * [Dispatcher] 通过 `interceptors.foldRight(handlerChain)` 构建一条单链表：
 *
 *   pipeline ──→ AuthChain ──→ LogChain ──→ RateLimitChain ──→ ExceptionChain ──→ handlerChain
 *
 * 每个 [InterceptorChain] 是一个链表节点，持有当前拦截器和指向下一节点的 `next`。
 * `handlerChain`（Dispatcher 中匿名内部类实现）是尾结点，执行完毕后直接调 Handler。
 *
 * 调用从链表头 `pipeline.proceed(request)` 开始：
 *   ```
 *   Auth.intercept(req, next=LogChain)
 *     → Log.intercept(req, next=RateLimitChain)
 *       → RateLimit.intercept(req, next=ExceptionChain)
 *         → Exception.intercept(req, next=handlerChain)
 *           → Handler.handle(req)                    ← 尾结点，实际执行业务
 *   ```
 * 每个 intercept 返回后，执行后置处理（日志、异常兜底），最终返回到调用方。
 *
 * [request] 属性始终委托给下一个链节，保证请求篡改拦截器无法隐藏修改。
 *
 * @param interceptor 当前链节包裹的拦截器
 * @param next 下一个拦截器链节（更靠近 Handler 的内层），尾结点为 handlerChain
 */
class InterceptorChain(
    private val interceptor: Interceptor,
    private val next: Interceptor.Chain
) : Interceptor.Chain {

    /** 委托给下一个链节的 request，保证 Chain 语义正确 */
    override val request: Request get() = next.request

    /**
     * 调用当前拦截器的 [Interceptor.intercept] 方法。
     *
     * @param request 传递给下一个拦截器的请求
     * @return 拦截器处理后的响应
     */
    override suspend fun proceed(request: Request): Response {
        return interceptor.intercept(request, next)
    }
}
