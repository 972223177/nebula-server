package com.nebula.gateway.dispatcher

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.gateway.interceptor.Interceptor

/**
 * 非尾结点的链节 — 委托给当前拦截器（D-06）。
 *
 * 由 [Dispatcher] 通过 `interceptors.foldRight(handlerChain)` 构建链表结构，
 * 每个 [InterceptorChain] 包裹一个 [Interceptor] 实例和下一个链节。
 *
 * [request] 属性始终委托给下一个链节，保证请求篡改拦截器无法隐藏修改。
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
