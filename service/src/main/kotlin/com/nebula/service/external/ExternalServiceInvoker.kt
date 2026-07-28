package com.nebula.service.external

import com.google.protobuf.Message
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 单个外部服务的执行体（D-XX，Phase 10 服务发现+通用调用）。
 *
 * 把「参数解析 + 配额/缓存管线调用 + 上游调用 + 净化 + 构造 Proto 响应」封装在独立文件，
 * 避免所有服务堆积在 [ExternalServiceOrchestrator] 同一文件（每加一个服务 = 新增一个实现类，
 * 编排类与注册表文件均不随服务数增长）。各实现持有 [ExternalServicePipeline] 与上游 Service，
 * 通过 [invoke] 返回 Proto 响应，由 gateway 侧 [com.nebula.gateway.handler.external.agent.ServiceDefinitionProvider]
 * 转成通用 result_json。
 *
 * @param R 返回的 Proto 响应类型（WeatherResponse / SearchResponse / IpLocationResponse）
 */
interface ExternalServiceInvoker<R : Message> {
    /** 服务稳定 id（契约标识符，list_services 下发的 id），也是注册表聚合键 */
    val serviceId: String

    /**
     * 执行服务调用。
     *
     * @param userId 调用方用户 ID（每用户防御子限）
     * @param clientIp 客户端真实 IP（由 Handler 从连接元数据提取，IP 定位类服务用于本地短路）
     * @param paramsJson 统一参数（JSON 对象字符串），由调用方按服务 schema 构造
     * @return 结构化 Proto 响应（已净化）
     * @throws BizException INVALID_PARAM（参数缺失/非法）/ QUOTA_EXCEEDED / SERVICE_UNAVAILABLE
     */
    suspend fun invoke(userId: Long, clientIp: String, paramsJson: String): R
}

/**
 * 解析 params_json 为 [JsonObject]（包级 internal 函数，供各 [ExternalServiceInvoker] 复用）。
 *
 * 非法 JSON 或非对象统一映射为 [BizCode.INVALID_PARAM]，否则 kotlinx.serialization 抛
 * [SerializationException] 会落入 ExceptionInterceptor 兜底分支变成 INTERNAL_ERROR(9000)，
 * 客户端拿不到有意义的参数错误。
 */
internal fun parseParams(paramsJson: String): JsonObject {
    if (paramsJson.isBlank()) return JsonObject(emptyMap())
    val el = try {
        Json.decodeFromString(JsonElement.serializer(), paramsJson)
    } catch (e: SerializationException) {
        throw BizException(BizCode.INVALID_PARAM, "params_json 不是合法 JSON: ${e.message}")
    }
    if (el !is JsonObject) {
        throw BizException(BizCode.INVALID_PARAM, "params_json 必须是 JSON 对象")
    }
    return el
}
