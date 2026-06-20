package com.nebula.gateway.handler.admin

import com.nebula.chat.admin.DeadLetterItem
import com.nebula.chat.admin.DeadLetterQueryReq
import com.nebula.chat.admin.DeadLetterQueryResp
import com.nebula.gateway.handler.Handler
import com.nebula.service.admin.DeadLetterDTO
import com.nebula.service.admin.DeadLetterService

/**
 * 死信查询 Admin Handler — method = "admin/dead-letters"（Phase 10）。
 *
 * 提供白名单分页查询死信记录的能力，支持按状态过滤。
 * 无需认证（通过 AuthInterceptor 的 admin/ 前缀白名单）。
 *
 * @param deadLetterService 死信服务
 */
class DeadLetterQueryHandler(
    private val deadLetterService: DeadLetterService
) : Handler<DeadLetterQueryReq, DeadLetterQueryResp> {

    override val method: String = "admin/dead-letters"

    override suspend fun handle(req: DeadLetterQueryReq): DeadLetterQueryResp {
        val page = if (req.page <= 0) 1 else req.page
        val pageSize = req.pageSize.coerceIn(1, 100)
        val status = req.status.ifBlank { null }

        val result = deadLetterService.query(page, pageSize, status)

        val builder = DeadLetterQueryResp.newBuilder()
        for (dto in result.items) {
            builder.addItems(toDeadLetterItem(dto))
        }
        builder.total = result.total.toInt()
        return builder.build()
    }

    /**
     * 将 [DeadLetterDTO] 转换为 [DeadLetterItem] Protobuf 消息。
     *
     * createdAt 已为毫秒时间戳，直接使用。
     *
     * @param dto 死信数据传输对象
     * @return 死信 Protobuf 消息项
     */
    private fun toDeadLetterItem(dto: DeadLetterDTO): DeadLetterItem {
        return DeadLetterItem.newBuilder()
            .setId(dto.id)
            .setMsgId(dto.msgId ?: 0)
            .setConversationId(dto.conversationId)
            .setSenderUid(dto.senderUid)
            .setFailReason(dto.failReason)
            .setFailCount(dto.failCount)
            .setStatus(dto.status)
            .setCreatedAt(dto.createdAt)
            .build()
    }
}
