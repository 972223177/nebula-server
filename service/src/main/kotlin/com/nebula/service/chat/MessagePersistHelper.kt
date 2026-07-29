package com.nebula.service.chat

import com.google.protobuf.ByteString
import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.message.ChatMessage
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.redis.MessageQueueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Base64

/**
 * 消息落库辅助（P2 抽取，D-04 / D-06 / D-09）。
 *
 * 承接 [com.nebula.service.chat.MessageService] 中的「非编排」逻辑，使其仅保留业务编排：
 * - 幂等去重（Redis SETNX，[checkAndSetDedup]）
 * - Redis Stream 落库（[enqueueMessage]：字段构造 + enqueue，D-04 落库入口）
 * - [MessageEntity] → [ChatMessage] 映射（[toChatMessage]）
 *
 * 抽出后落库细节可单测独立覆盖，不依赖完整发送链。本类不持有事务 / 推送 / 会话校验等编排职责。
 */
class MessagePersistHelper(
    private val messageQueueRepository: MessageQueueRepository
) {

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}
    }

    /**
     * 消息去重检查 — 使用 Redis SETNX 检测重复消息。
     *
     * @param clientMessageId 客户端消息 ID
     * @param senderUid 发送者用户 ID
     * @return true 表示新消息，false 表示重复
     */
    suspend fun checkAndSetDedup(clientMessageId: String, senderUid: Long): Boolean {
        val isNew = messageQueueRepository.checkAndSetDedup(clientMessageId, senderUid)
        if (!isNew) {
            // 重复消息是客户端重试的网络抖动场景，业务上正常；但频次异常高（如刷屏/重放）需关注
            logger.warn { "检测到重复消息（clientMsgId 已存在）senderUid=$senderUid clientMsgId=$clientMessageId" }
        }
        return isNew
    }

    /**
     * 构造 Redis Stream 字段并 enqueue（D-04 落库入口）。
     *
     * 字段必须与 [com.nebula.repository.repository.impl.MessageRepositoryImpl.parseToEntity] 对齐：
     * - `body["id"]` 取 `msgId`（不能用 "msgId" 字面量，否则消息 ID 为 null、DB INSERT 失败 PK not null）；
     * - `messageType` 必须写枚举数字值（`messageTypeValue`），不能写 `req.messageType.toString()`：
     *   `req.messageType` 是 proto 枚举 `ChatContentType`，其 `toString()` 返回枚举名（如 "TEXT"），
     *   而 `parseToEntity` 用 `body["messageType"]?.toIntOrNull()` 解析，`"TEXT".toIntOrNull() == null`
     *   会使整条消息被判为「毒消息」→ 死信 + XACK，永不落库，表现为「会话有概要、message/pull 却为空」。
     *
     * @param req 发送消息请求
     * @param msgId 雪花 ID
     * @param senderUid 发送者 UID
     * @param serverTs 服务端时间戳（毫秒），写入 `serverTs` 字段与 ChatMessage
     */
    suspend fun enqueueMessage(
        req: SendMessageReq,
        msgId: Long,
        senderUid: Long,
        serverTs: Long
    ) {
        val streamFields = mapOf(
            "id" to msgId.toString(),
            "conversationId" to req.conversationId,
            "senderUid" to senderUid.toString(),
            "messageType" to req.messageTypeValue.toString(),
            "content" to req.content,
            "clientMessageId" to req.clientMessageId,
            "clientTs" to req.clientTs.toString(),
            "serverTs" to serverTs.toString(),
            "payload" to (if (req.payload.size() > 0) Base64.getEncoder().encodeToString(req.payload.toByteArray()) else "")
        )
        messageQueueRepository.enqueue(streamFields)
    }

    /**
     * 将 [MessageEntity] 转换为 [ChatMessage] Protobuf。
     *
     * @param entity 消息实体
     * @return 转换后的 ChatMessage
     */
    fun toChatMessage(entity: MessageEntity): ChatMessage {
        val builder = ChatMessage.newBuilder()
            .setMsgId(requireNotNull(entity.id) { "MessageEntity.id 不应为null" })
            .setConversationId(entity.conversationId)
            .setSenderUid(entity.senderUid)
            .setMessageTypeValue(entity.messageType)
            .setContent(entity.content)
            .setClientTs(entity.clientTs)
            .setServerTs(entity.serverTs)
        val payloadBytes = entity.payload
        if (payloadBytes != null && payloadBytes.isNotEmpty()) {
            builder.setPayload(ByteString.copyFrom(payloadBytes))
        }
        return builder.build()
    }
}
