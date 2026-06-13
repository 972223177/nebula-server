package com.nebula.gateway.handler.admin

import com.nebula.chat.admin.DeadLetterItem
import com.nebula.chat.admin.DeadLetterQueryReq
import com.nebula.chat.admin.DeadLetterQueryResp
import com.nebula.gateway.handler.Handler
import com.nebula.repository.entity.DeadLetterEntity
import com.nebula.service.admin.DeadLetterService
import java.time.ZoneOffset

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
        for (entity in result.content) {
            builder.addItems(toDeadLetterItem(entity))
        }
        builder.total = result.totalElements.toInt()
        return builder.build()
    }

    /**
     * 将 [DeadLetterEntity] 转换为 [DeadLetterItem] Protobuf 消息。
     *
     * createdAt 转换为毫秒时间戳（Proto 定义的 int64 类型）。
     */
    private fun toDeadLetterItem(entity: DeadLetterEntity): DeadLetterItem {
        return DeadLetterItem.newBuilder()
            .setId(entity.id ?: 0)
            .setMsgId(entity.msgId ?: 0)
            .setConversationId(entity.conversationId)
            .setSenderUid(entity.senderUid)
            .setFailReason(entity.failReason)
            .setFailCount(entity.failCount)
            .setStatus(entity.status)
            .setCreatedAt(
                entity.createdAt?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: 0L
            )
            .build()
    }
}
