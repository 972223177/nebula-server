package com.nebula.gateway.service

import com.nebula.chat.Envelope
import com.nebula.chat.Message
import com.nebula.chat.PushEventType
import com.nebula.chat.message.ChatMessage
import com.nebula.service.admin.DeadLetterService
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 死信创建扩展（Phase 1.5 瘦身）。
 *
 * 从 ChatStreamObserver.createDeadLetter 下沉：把"解析 Envelope/Message → 组装死信字段 → 调用 create"的
 * 机械转换逻辑收口到此处，ChatStreamObserver 只需一行调用。
 *
 * 设计决策：
 * - proto 解析逻辑（ChatMessage.parseFrom 等）留在 gateway 模块，不跨层依赖 service 模块（D-分层约束）。
 * - 转换失败仅记录日志，不向上抛（与原实现行为一致，避免死信写入失败影响主投递流程）。
 *
 * @param envelope 投递失败的 Envelope
 * @param failReason 失败原因描述
 */
private val logger = KotlinLogging.logger {}

suspend fun DeadLetterService.recordFromEnvelope(envelope: Envelope, failReason: String) {
    try {
        val msg: Message = envelope.message
        if (msg.eventType == PushEventType.CHAT_MESSAGE) {
            val chatMsg = ChatMessage.parseFrom(msg.payload)
            create(
                conversationId = chatMsg.conversationId,
                senderUid = chatMsg.senderUid,
                messageType = chatMsg.messageTypeValue,
                content = chatMsg.content,
                payload = chatMsg.payload.toByteArray(),
                clientMsgId = null,
                clientTs = chatMsg.clientTs,
                failReason = failReason
            )
        } else {
            // 非 ChatMessage 类型的死信，使用 envelope 可用数据
            create(
                conversationId = "",
                senderUid = 0L,
                messageType = msg.eventTypeValue,
                content = msg.content,
                payload = msg.payload.toByteArray(),
                clientMsgId = null,
                clientTs = System.currentTimeMillis(),
                failReason = failReason
            )
        }
    } catch (e: Exception) {
        logger.error(e) { "创建死信记录失败" }
    }
}
