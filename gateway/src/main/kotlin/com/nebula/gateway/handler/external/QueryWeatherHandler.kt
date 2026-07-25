package com.nebula.gateway.handler.external
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.external.WeatherRequest
import com.nebula.chat.external.WeatherResponse
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.external.ExternalServiceOrchestrator
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * 天气查询 Handler（D-XX）—— method = "external/query_weather"。
 *
 * 仅做协议适配：从 CoroutineContext 取登录态 userId（规范 9，禁止重新解析 token），
 * 委托 [ExternalServiceOrchestrator] 完成配额/缓存/上游编排。业务异常由
 * ExceptionInterceptor 统一捕获并写入 Response.code/msg（见 external-service-backend.md §2/§4.3）。
 *
 * @param orchestrator 外部服务编排层
 */
class QueryWeatherHandler(
    private val orchestrator: ExternalServiceOrchestrator
) : Handler<WeatherRequest, WeatherResponse> {

    /** 路由方法名 */
    override val method: String = MethodNames.External.QUERY_WEATHER

    /**
     * 处理天气查询请求。
     *
     * @param req 反序列化后的 WeatherRequest（city）
     * @return 格式化天气文本封装的 WeatherResponse
     */
    override suspend fun handle(req: WeatherRequest): WeatherResponse {
        val userId = currentCoroutineContext().requireSession().userId
        return orchestrator.queryWeather(userId, req.city)
    }
}
