package com.nebula.gateway.handler.chat.send

import com.nebula.chat.message.ChatMessage
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.redis.MessageQueueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl

/**
 * 消息写入 Step — Snowflake ID 生成 → ChatMessage 构建 → Redis Stream 写入 → 会话元更新（D-04, D-10, D-13）。
 *
 * 职责：
 * - 使用 Snowflake 生成全局唯一消息 ID
 * - 构建完整的 ChatMessage proto 对象
 * - 将消息写入 Redis Stream（D-04 ACK 时机）
 * - 更新会话元信息：last_message_id、last_message_preview、last_updated_at（D-10）
 * - 更新去重键值为实际 msg_id（REVIEW-MEDIUM-6）
 *
 * 设计说明：
 * - 未读计数 INCR 不在 WriteStep 中处理，已移至 SendMessageHandler 的异步后处理阶段（REVIEW-MEDIUM-5）
 * - 推送由 SendMessageHandler 异步执行（D-04 per REVIEW）
 *
 * @param idGenerator Snowflake ID 生成器
 * @param messageQueueRepository Redis Stream 消息队列
 * @param connection Redis 连接实例
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class WriteStep(
    private val idGenerator: SnowflakeIdGenerator,
    private val messageQueueRepository: MessageQueueRepository,
    private val connection: StatefulRedisConnection<String, String>
) : SendMessageStep {

    private val redis: RedisCoroutinesCommands<String, String> =
        RedisCoroutinesCommandsImpl(connection.reactive())

    /**
     * 执行消息写入逻辑。
     *
     * @param context Step 链共享上下文
     * @return true 写入成功，继续下一步
     */
    override suspend fun execute(context: SendContext): Boolean {
        val req = context.req

        // D-10: Snowflake 生成全局唯一消息 ID
        val msgId = idGenerator.nextId()
        context.msgId = msgId

        // 构建完整的 ChatMessage proto 对象
        val chatMessage = ChatMessage.newBuilder()
            .setMsgId(msgId)
            .setConversationId(req.conversationId)
            .setSenderUid(context.senderUid)
            .setMessageType(req.messageType)
            .setContent(req.content)
            .setPayload(req.payload)
            .setClientTs(req.clientTs)
            .setServerTs(System.currentTimeMillis())
            .build()
        context.chatMessage = chatMessage

        // D-04: 写入 Redis Stream（ACK 时机点 — 写入后即可返回响应）
        messageQueueRepository.enqueue(mapOf(
            "msg_id" to msgId.toString(),
            "conversation_id" to req.conversationId
        ))

        val convId = req.conversationId

        // D-10: 更新会话元信息（Redis 会话缓存）
        redis.set("conversation:${convId}:last_message_id", msgId.toString())
        redis.set("conversation:${convId}:last_message_preview", req.content.take(50))
        redis.set("conversation:${convId}:last_updated_at", System.currentTimeMillis().toString())

        // REVIEW-MEDIUM-6: 更新去重键值为实际 msg_id（覆盖 DedupStep 写入的 "pending"）
        val dedupKey = "chat:dedup:${req.clientMessageId}"
        redis.setex(dedupKey, 7 * 24 * 3600L, msgId.toString())

        return true
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
