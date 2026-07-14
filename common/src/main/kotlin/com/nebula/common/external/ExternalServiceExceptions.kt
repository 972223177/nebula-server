package com.nebula.common.external

import com.nebula.common.BizCode
import com.nebula.common.exception.BizException

/**
 * 外部服务业务异常便捷构造（D-XX）。
 *
 * 统一抛出 [BizException]，由 ExceptionInterceptor 捕获并写入 `Response.code/msg`
 * （错误码定义见 common/BizCode.kt 16xx 段，见 external-service-backend.md §2/§4.3）。
 * 配额剩余重置秒数折叠进 msg 文案，避免扩展 Response 协议。
 */
object ExternalServiceExceptions {

    /**
     * 构建配额耗尽异常，msg 折叠剩余重置秒数。
     *
     * @param remainingSeconds 距离配额周期重置的剩余秒数
     * @return 携带可读文案的 BizException(QUOTA_EXCEEDED)
     */
    fun quotaExceeded(remainingSeconds: Long): BizException =
        BizException(BizCode.QUOTA_EXCEEDED, "外部服务配额已耗尽，约 ${remainingSeconds} 秒后重置")

    /**
     * 构建服务不可用异常（Key 缺失 / 上游超时 / 熔断降级失败）。
     *
     * @param msg 具体原因（禁止包含 API Key 等敏感信息）
     * @return BizException(SERVICE_UNAVAILABLE)
     */
    fun serviceUnavailable(msg: String): BizException =
        BizException(BizCode.SERVICE_UNAVAILABLE, msg)

    /**
     * 构建城市不存在异常（GeoAPI 未匹配到 LocationID）。
     *
     * @param city 用户传入的城市名
     * @return BizException(INVALID_CITY)
     */
    fun invalidCity(city: String): BizException =
        BizException(BizCode.INVALID_CITY, "城市不存在: $city")
}
