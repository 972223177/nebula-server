package com.nebula.gateway.handler.admin

import com.nebula.chat.admin.RetryDeadLetterReq
import com.nebula.chat.admin.RetryDeadLetterResp
import com.nebula.gateway.handler.Handler
import com.nebula.service.admin.DeadLetterService

/**
 * 死信手动重试 Admin Handler — method = "admin/retry-dead-letter"（Phase 10）。
 *
 * 接收死信 ID，委托 [DeadLetterService.retry] 尝试重新入队。
 * 无需认证（通过 AuthInterceptor 的 admin/ 前缀白名单）。
 *
 * @param deadLetterService 死信服务
 */
class RetryDeadLetterHandler(
    private val deadLetterService: DeadLetterService
) : Handler<RetryDeadLetterReq, RetryDeadLetterResp> {

    override val method: String = "admin/retry-dead-letter"

    override suspend fun handle(req: RetryDeadLetterReq): RetryDeadLetterResp {
        val success = deadLetterService.retry(req.deadLetterId)
        return RetryDeadLetterResp.newBuilder()
            .setSuccess(success)
            .build()
    }
}
