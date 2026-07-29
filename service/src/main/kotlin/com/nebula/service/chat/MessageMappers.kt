package com.nebula.service.chat

import com.google.protobuf.ByteString
import com.nebula.chat.message.ChatMessage
import com.nebula.repository.entity.MessageEntity

/**
 * 消息 Entity → ChatMessage Protobuf 共享 mapper（2026-07-29 从 [com.nebula.service.chat.MessagePersistHelper] 迁入，仿 `conversation/ConversationMappers.kt`）。
 *
 * 之前 `toChatMessage` 内联在 `MessagePersistHelper` 中，存在「双源真相」风险——任何字段变更必须同步修改两处。
 * 抽到 service 包内的扩展函数，任何 `ChatMessage` 字段变更只需改本文件一处。
 *
 * 设计决策：
 * - 用扩展函数而非工具类：Kotlin 调用方更自然（`entity.toChatMessage()`），零样板；
 * - 不放 repository 模块：避免 service 跨包访问 repository 内部实现细节；
 * - 不放 common 模块：proto 类（`ChatMessage`）属于业务语义，不该在 common 暴露。
 *
 * @return 完整构造的 ChatMessage
 */
fun MessageEntity.toChatMessage(): ChatMessage {
    val builder = ChatMessage.newBuilder()
        .setMsgId(requireNotNull(id) { "MessageEntity.id 不应为null" })
        .setConversationId(conversationId)
        .setSenderUid(senderUid)
        .setMessageTypeValue(messageType)
        .setContent(content)
        .setClientTs(clientTs)
        .setServerTs(serverTs)
    val payloadBytes = payload
    if (payloadBytes != null && payloadBytes.isNotEmpty()) {
        builder.setPayload(ByteString.copyFrom(payloadBytes))
    }
    return builder.build()
}
