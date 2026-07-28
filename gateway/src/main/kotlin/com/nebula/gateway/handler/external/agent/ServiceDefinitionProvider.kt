package com.nebula.gateway.handler.external.agent

import com.nebula.service.external.ExternalServiceOrchestrator

/**
 * 单个外部服务定义的提供者（Phase 10 服务发现+通用调用）。
 *
 * 把「服务描述构造」与「执行体绑定」封装在各自文件内，避免所有定义堆积在 [ServiceRegistry] 同一文件。
 * 新增服务只需新增一个实现类并在 Koin 注册为 [ServiceDefinitionProvider]，注册表自动聚合，无需改动 [ServiceRegistry]。
 *
 * @param orchestrator 外部服务编排层（由 [ServiceRegistry] 注入，供执行体复用既有实现）
 * @return 该服务的 [ServiceDefinition]（描述契约 + 调用执行体）
 */
interface ServiceDefinitionProvider {
    fun create(orchestrator: ExternalServiceOrchestrator): ServiceDefinition
}
