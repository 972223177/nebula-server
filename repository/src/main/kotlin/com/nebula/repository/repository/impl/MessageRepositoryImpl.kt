package com.nebula.repository.repository.impl

import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.repository.repository.MessageWriteRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 消息写入路径实现（DB-03, D-11）。
 *
 * 架构：Redis Stream（即时 ACK）→ 定时批量刷入 MySQL
 * - 消息先通过 [MessageQueueRepository.enqueue] 写入 Redis Stream，客户端即刻收到 ACK
 * - 后台协程每 500ms 检查积压消息，有消息即触发批量 INSERT
 * - 刷写失败的消息保留在 Redis Stream 中，下次定时任务重试
 *
 * @param messageQueue Redis Stream 消息队列操作封装
 * @param jpaMessageRepo JPA 消息仓库（用于游标分页查询）
 * @param emf JPA EntityManagerFactory
 */
class MessageRepositoryImpl(
    private val messageQueue: MessageQueueRepository,
    private val jpaMessageRepo: MessageRepository,
    private val emf: EntityManagerFactory
) : MessageWriteRepository {

    private val logger = KotlinLogging.logger {}
    @Volatile
    private var stopped = false

    override suspend fun enqueueMessage(entity: MessageEntity): String {
        // 将 entity 序列化为 Map<String, String>
        val map = buildMap {
            entity.id?.let { put("id", it.toString()) }
            put("conversationId", entity.conversationId)
            put("senderUid", entity.senderUid.toString())
            put("messageType", entity.messageType.toString())
            put("content", entity.content)
            entity.clientMessageId?.let { put("clientMessageId", it) }
            put("clientTs", entity.clientTs.toString())
            put("serverTs", entity.serverTs.toString())
        }
        val result = messageQueue.enqueue(map)
        return result ?: throw RuntimeException("Failed to enqueue message to Redis Stream")
    }

    override suspend fun flushBatch(): Int {
        val entries = messageQueue.consume(batchSize = 30, blockMs = 0)
        if (entries.isEmpty()) return 0

        // 解析 StreamMessage 为 MessageEntity
        val messages = entries.mapNotNull { entry -> parseToEntity(entry) }

        val em = emf.createEntityManager()
        try {
            em.transaction.begin()
            var count = 0
            for (msg in messages) {
                em.persist(msg)
                count++
                if (count % 30 == 0) {
                    em.flush()
                    em.clear()
                }
            }
            em.transaction.commit()

            // XACK 所有成功写入的消息
            entries.forEach { messageQueue.acknowledge(it.id) }
            return messages.size
        } catch (e: Exception) {
            logger.error(e) { "批量刷写消息失败" }
            // 失败的消息保留在 Redis Stream 中，下次重试
            return 0
        } finally {
            em.close()
        }
    }

    override suspend fun acknowledgeMessage(messageId: String) {
        messageQueue.acknowledge(messageId)
    }

    /**
     * 启动定时刷写任务（D-11: 500ms 间隔）。
     *
     * 在后台协程中每 500ms 消费 Redis Stream 中的积压消息，触发批量刷入 MySQL。
     */
    fun startFlushTimer() {
        CoroutineScope(Dispatchers.IO).launch {
            while (!stopped) {
                delay(500)
                flushBatch()
            }
        }
    }

    /**
     * 停止定时刷写任务。
     */
    fun stop() {
        stopped = true
    }

    /** 将 StreamMessage 解析为 MessageEntity */
    private fun parseToEntity(entry: io.lettuce.core.StreamMessage<String, String>): MessageEntity? {
        val body = entry.body ?: return null
        return MessageEntity(
            conversationId = body["conversationId"] ?: return null,
            senderUid = body["senderUid"]?.toLongOrNull() ?: return null,
            messageType = body["messageType"]?.toIntOrNull() ?: return null,
            content = body["content"] ?: return null,
            clientMessageId = body["clientMessageId"],
            clientTs = body["clientTs"]?.toLongOrNull() ?: return null,
            serverTs = body["serverTs"]?.toLongOrNull() ?: return null
        ).apply {
            id = body["id"]?.toLongOrNull()
        }
    }
}
