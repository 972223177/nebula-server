package com.nebula.gateway.service

import com.nebula.chat.Request
import com.nebula.chat.Response

/**
 * 响应后置拦截器（Phase 2 瘦身）。
 *
 * 从 ChatService.handleRequest 的硬编码 when(response.method) 抽离，使登录绑定等"响应后逻辑"
 * 变为可插拔组件。未来新增"消息已读后通知""敏感词审计"等响应后处理，只需追加一个拦截器实现，
 * ChatService 不再膨胀。
 *
 * 拦截器按注册顺序组成责任链，每个拦截器可在响应写回客户端前执行副作用（如绑定 Session），
 * 并返回（可能修改后的）Response 传递给下一个拦截器。
 *
 * 设计决策：
 * - 接口声明为 internal，因方法参数暴露 internal 的 ChatStreamObserver 类型（与 SessionBinder/PingProcessor 一致）。
 * - request 参数透传原始请求，部分绑定逻辑需要解析请求参数（如注册的设备信息）。
 *
 * @param response Dispatcher 返回的业务响应
 * @param observer 当前连接的 StreamObserver（inner class）
 * @param request 客户端原始请求（部分绑定逻辑需要解析请求参数）
 * @return 传递给下一个拦截器的 Response
 */
internal interface ResponseInterceptor {
    suspend fun afterResponse(response: Response, observer: ChatService.ChatStreamObserver, request: Request): Response
}
