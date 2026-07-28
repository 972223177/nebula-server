package com.nebula.gateway.handler.external.agent

import com.nebula.chat.external.ListServicesRequest
import com.nebula.chat.external.ServiceDescriptor
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.service.external.ExternalServiceOrchestrator
import java.security.MessageDigest

/**
 * 单个可被 LLM 调用的外部服务定义（D-XX，Phase 10 发现+通用调用）。
 *
 * 将「工具描述」与「实际调用」封装为一个单元：
 * - [descriptor] 是下发给客户端的 [ServiceDescriptor]（id / 描述 / schema / 风险）；
 * - [invoke] 是按 [serviceId] 路由的执行体，把统一的 (userId, clientIp, paramsJson) 适配到
 *   具体上游编排方法，并把结构化结果转成通用 `result_json` 字符串。
 *
 * @param descriptor 服务描述（契约）
 * @param invoke 执行体：(userId, clientIp, paramsJson) -> resultJson；参数/上游异常抛 [BizException]
 */
class ServiceDefinition(
    val descriptor: ServiceDescriptor,
    val invoke: suspend (userId: Long, clientIp: String, paramsJson: String) -> String
)

/**
 * 外部服务注册表（D-XX，Phase 10）—— 服务发现与通用调用的数据源。
 *
 * 职责：
 * - 持有一组 [ServiceDefinitionProvider] 产出的内置 [ServiceDefinition]（当前为天气/搜索/IP 定位三个，
 *   复用现有 [ExternalServiceOrchestrator] 方法作为执行体，不重写业务逻辑）；每个定义独立成文件实现
 *   [ServiceDefinitionProvider]，新增服务只需扩展 provider 列表，本类不随服务数增长；
 * - [listDescriptors] 按 [ListServicesRequest] 过滤后产出客户端所需的工具清单；
 * - [versionHash] 基于全部描述完整内容计算稳定哈希（SHA-256），任一字段变化即变，供客户端判断缓存失效；
 * - [get] 按 id 取执行体，供 `CallService` 路由。
 *
 * 设计决策引用：
 * - 各执行体统一经 [ExternalServiceOrchestrator.invoke] 路由到 service 模块对应的
 *   [com.nebula.service.external.ExternalServiceInvoker]，现有配额/缓存/鉴权/净化机制自动生效
 *   （配额在 Invoker 内按 userId 扣减，缓存与内容净化由上游内置，故 `CallService` 无需额外实现即复用）；
 * - `result_json` 用 kotlinx.serialization 由 Invoker 返回的 Proto 响应字段构造，与
 *   [ServiceDescriptor.outputSchemaJson] 字段集一致；
 * - IP 定位的执行体经 [ExternalServiceOrchestrator.invoke] 路由到 service 模块 `GeoIpInvoker`，
 *   由 Invoker 内 `isPublicUnicastIpv4` 本地短路（非公网 IP 不发起上游调用、不浪费配额）。
 *
 * @param orchestrator 外部服务编排层（由 Koin 注入，复用既有实现）
 * @param providers 各内置服务的 [ServiceDefinitionProvider]，注册表聚合为定义表
 */
class ServiceRegistry(
    private val orchestrator: ExternalServiceOrchestrator,
    providers: List<ServiceDefinitionProvider>
) {
    /** 客户端缓存建议 TTL（秒）：客户端拉取 list_services 后可按此值缓存清单，过期或 version_hash 变化再重新拉取。 */
    val cacheTtlSeconds: Long = 300

    private val definitions: Map<String, ServiceDefinition> = providers
        .map { it.create(orchestrator) }
        .also { defs ->
            // 启动期 fail-fast：重复 id 会静默覆盖导致服务丢失，提前在构造时暴露
            val dup = defs.groupingBy { it.descriptor.id }.eachCount().filter { it.value > 1 }.keys
            require(dup.isEmpty()) { "ServiceRegistry 存在重复服务 id: ${dup.joinToString()}" }
        }
        .associateBy { it.descriptor.id }

    /**
     * 返回按过滤条件筛选后的服务描述清单。
     *
     * @param request 过滤条件（category / ids / searchKeyword / only_available）
     * @return 匹配的服务描述列表（按 id 升序，保证 version_hash 稳定）
     */
    fun listDescriptors(request: ListServicesRequest): List<ServiceDescriptor> {
        var list = definitions.values.map { it.descriptor }
        if (request.category.isNotBlank()) {
            list = list.filter { it.category == request.category }
        }
        if (request.idsList.isNotEmpty()) {
            val ids = request.idsList.toSet()
            list = list.filter { it.id in ids }
        }
        if (request.searchKeyword.isNotBlank()) {
            val kw = request.searchKeyword.lowercase()
            list = list.filter {
                it.name.lowercase().contains(kw) || it.description.lowercase().contains(kw)
            }
        }
        // only_available：当前未做实时可用性探测，所有服务均视为可用，原样返回
        return list.sortedBy { it.id }
    }

    /** 按 id 取服务定义（含执行体）；未知 id 返回 null，由调用方决定抛 NOT_FOUND。 */
    fun get(id: String): ServiceDefinition? = definitions[id]

    /**
     * 基于全部服务描述完整内容计算的稳定哈希（SHA-256 十六进制）。
     *
     * 任一描述字段变化即改变，客户端比对本地缓存的 version_hash 与服务端返回的不一致时，即判定清单已变更并重新拉取。
     */
    fun versionHash(): String {
        val sb = StringBuilder()
        definitions.values.sortedBy { it.descriptor.id }.forEach { def ->
            val d = def.descriptor
            sb.append(d.id).append('|').append(d.name).append('|').append(d.description).append('|')
                .append(d.category).append('|').append(d.inputSchemaJson).append('|').append(d.outputSchemaJson).append('|')
                .append(d.riskLevel).append('|').append(d.version).append('|').append(d.requiresUserConsent).append('\n')
        }
        return sha256Hex(sb.toString())
    }

    private fun sha256Hex(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
