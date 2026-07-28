package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.ListServicesRequest
import com.nebula.chat.external.ListServicesResponse
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.MethodNames
import com.nebula.gateway.handler.requireSession
import kotlinx.coroutines.currentCoroutineContext

/**
 * 服务发现 Handler（D-XX，Phase 10）—— method = "external/list_services"。
 *
 * 仅做协议适配：校验登录态（与天气/搜索/定位一致，须登录后可用），委托 [ServiceRegistry]
 * 按过滤条件产出 [ServiceDescriptor] 清单，并附带 `cache_ttl_seconds` 与基于完整内容计算的
 * `version_hash`。业务异常由 ExceptionInterceptor 统一捕获写入 Response.code/msg。
 *
 * @param registry 外部服务注册表
 */
class ListServicesHandler(
    private val registry: ServiceRegistry
) : Handler<ListServicesRequest, ListServicesResponse> {

    /** 路由方法名 */
    override val method: String = MethodNames.External.LIST_SERVICES

    /**
     * 处理服务发现请求。
     *
     * @param req 过滤条件（category / only_available / ids / search_keyword）
     * @return 工具清单（含 version_hash 与缓存 TTL）
     */
    override suspend fun handle(req: ListServicesRequest): ListServicesResponse {
        // 需要登录态（与现有外部服务 handler 一致），无 Session 则抛 UNAUTHORIZED
        currentCoroutineContext().requireSession()
        val descriptors = registry.listDescriptors(req)
        return ListServicesResponse.newBuilder()
            .addAllServices(descriptors)
            .setCacheTtlSeconds(registry.cacheTtlSeconds)
            .setVersionHash(registry.versionHash())
            .build()
    }
}
