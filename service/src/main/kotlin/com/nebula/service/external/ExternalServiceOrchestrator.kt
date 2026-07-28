package com.nebula.service.external

import com.google.protobuf.Message
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 外部服务编排入口（D-XX，Phase 10）—— 退化为纯注册表 + 通用调用。
 *
 * 仅负责按 service_id 聚合各 [ExternalServiceInvoker] 并暴露统一调用入口，
 * 具体「参数解析 / 配额 / 缓存 / 上游调用 / 净化」逻辑全部下沉到各 Invoker 独立文件，
 * 本类不再随服务数增长。gateway 侧专用 Handler 与 [com.nebula.gateway.handler.external.agent.ServiceDefinitionProvider]
 * 均统一经 [invoke] 路由，零增长承接新服务（新增服务 = 1 个 Invoker 文件 + service 模块 1 行 Koin）。
 *
 * @param invokers 各外部服务执行体（service 模块以 ExternalServiceInvoker<*> 注册，Koin getAll 聚合）
 */
class ExternalServiceOrchestrator(
    private val invokers: Map<String, ExternalServiceInvoker<*>>
) {
    private val log = KotlinLogging.logger {}

    init {
        // 启动期 fail-fast：重复 id 会静默覆盖导致服务丢失，提前在构造时暴露
        val dup = invokers.keys.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
        require(dup.isEmpty()) { "ExternalServiceOrchestrator 存在重复服务 id: ${dup.joinToString()}" }
    }

    /**
     * 按 service_id 通用调用外部服务。
     *
     * @param id 服务 id（大小写不敏感，免疫 LLM 工具调用的大小写漂移）
     * @param userId 调用方用户 ID（每用户防御子限）
     * @param clientIp 客户端真实 IP（IP 定位类服务用于本地短路）
     * @param paramsJson 统一参数（JSON 对象字符串）
     * @return 结构化 Proto 响应（已净化）
     * @throws BizException NOT_FOUND（未知 service_id）/ INVALID_PARAM / QUOTA_EXCEEDED / SERVICE_UNAVAILABLE
     */
    suspend fun invoke(id: String, userId: Long, clientIp: String, paramsJson: String): Message {
        val invoker = invokers[id.lowercase()]
            ?: throw BizException(BizCode.NOT_FOUND, "未知 service_id: $id")
        log.debug { "外部服务调用 id=$id userId=$userId" }
        return invoker.invoke(userId, clientIp, paramsJson)
    }
}
