package com.nebula.gateway.interceptor

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.gateway.dispatcher.InterceptorChain

/**
 * 拦截器接口 — suspend 版本的责任链模式（GoF Chain of Responsibility）。
 *
 * 所有拦截器方法定义为 suspend 函数（D-05），对齐全局协程生态。
 * 通过 Koin 以 List\<Interceptor\> 方式注入，Dispatcher 在启动时组装为链。
 * 执行顺序（D-07）：
 *   AuthInterceptor → LogInterceptor → RateLimitInterceptor → ExceptionInterceptor
 */
interface Interceptor {

    /**
     * 拦截处理请求。
     *
     * 实现类可通过调用 `chain.proceed(request)` 将请求传递给下一个拦截器，
     * 也可直接返回 Response 终止链路（如认证失败时返回错误响应）。
     *
     * @param request 当前请求
     * @param chain 下一个拦截器的链节
     * @return 处理后的响应
     */
    suspend fun intercept(request: Request, chain: Chain): Response

    /**
     * 责任链节接口 — 代表单链表中的一个节点。
     *
     * 由 [com.nebula.gateway.dispatcher.Dispatcher] 通过
     * `interceptors.foldRight(handlerChain)` 构建为单链表：
     *
     *   pipeline ──→ Auth ──→ Log ──→ RateLimit ──→ Exception ──→ handlerChain
     *
     * - **非尾结点**：由 [com.nebula.gateway.dispatcher.InterceptorChain] 实现，
     *   持有 `(interceptor, next)`，`proceed` 委托给拦截器的 `intercept`。
     * - **尾结点**（handlerChain）：Dispatcher 中匿名 Chain 实现，`proceed` 最终调用 Handler。
     *
     * 调用 proceed 相当于"沿着 next 指针向链表内层前进"。
     * 拦截器在 proceed 前执行前置操作（鉴权、限流），返回后执行后置操作（日志）。
     */
    interface Chain {

        /** 当前链节的请求对象（拦截器可通过修改参数后传递给 proceed） */
        val request: Request

        /**
         * 继续执行下一个拦截器链节。
         *
         * 拦截器可在调用 proceed 前执行前置操作（如认证检查、限流），
         * 也可在 proceed 返回后执行后置操作（如日志记录、异常兜底）。
         *
         * @param request 传递给下一个拦截器的请求对象
         * @return 最终的响应结果
         */
        suspend fun proceed(request: Request): Response
    }
}
