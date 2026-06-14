package com.nebula.repository.repository

import com.nebula.repository.entity.MessageEntity

/**
 * 消息写入路径接口（D-08）。
 *
 * 实现异步写路径：Redis Stream（即时 ACK）→ 定时批量刷入 MySQL。
 */
interface MessageWriteRepository {
    /**
     * 将消息入队到 Redis Stream。
     *
     * @param entity 消息实体
     * @return Redis Stream 消息 ID
     */
    suspend fun enqueueMessage(entity: MessageEntity): String
    /**
     * 从 Redis Stream 消费并批量刷入 MySQL。
     *
     * @return 成功写入的消息条数
     */
    suspend fun flushBatch(): Int
    /**
     * 确认消息已被处理。
     *
     * @param messageId Redis Stream 消息 ID
     */
    suspend fun acknowledgeMessage(messageId: String)
}
