package com.nebula.repository.repository.impl

import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.repository.repository.MessageWriteRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.springframework.dao.DataIntegrityViolationException

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

    /**
     * M11: 死信创建回调 — 由 Gateway 层在启动后注入。
     *
     * 签名：(conversationId, senderUid, messageType, content, payload, clientMsgId, clientTs, failReason) → Unit
     * 使用基本类型避免跨模块依赖。
     */
    var onDeadLetter: (suspend (String, Long, Int, String, ByteArray?, String?, Long, String) -> Unit)? = null
    @Volatile
    private var stopped = false

    /** D-85/M19: 类级 CoroutineScope，stop() 时 cancel 所有子协程 */
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * 将消息入队到 Redis Stream。
     *
     * @see MessageWriteRepository.enqueueMessage
     */
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
            entity.payload?.let { put("payload", java.util.Base64.getEncoder().encodeToString(it)) }
        }
        val result = messageQueue.enqueue(map)
        return result ?: throw RuntimeException("Failed to enqueue message to Redis Stream")
    }

    /**
     * 从 Redis Stream 消费并批量刷入 MySQL。
     *
     * @see MessageWriteRepository.flushBatch
     */
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
        } catch (e: DataIntegrityViolationException) {
            logger.warn(e) { "唯一索引冲突，为冲突消息创建死信记录" }
            // M11: UK 冲突时为每条消息创建死信记录，而非静默跳过
            val handler = onDeadLetter
            if (handler != null) {
                entries.forEach { entry ->
                    val parsed = parseToEntity(entry)
                    if (parsed != null) {
                        try {
                            runBlocking {
                                handler(
                                    parsed.conversationId,
                                    parsed.senderUid,
                                    parsed.messageType,
                                    parsed.content,
                                    parsed.payload,
                                    parsed.clientMessageId,
                                    parsed.clientTs,
                                    "UK 冲突: client_msg_id=${parsed.clientMessageId}"
                                )
                            }
                        } catch (dlEx: Exception) {
                            logger.error(dlEx) { "创建死信记录失败: clientMsgId=${parsed.clientMessageId}" }
                        }
                    }
                }
            }
            // 仍然 XACK 避免 Redis 中重复消费
            entries.forEach { messageQueue.acknowledge(it.id) }
            return 0
        } catch (e: Exception) {
            logger.error(e) { "批量刷写消息失败" }
            // 失败的消息保留在 Redis Stream 中，下次重试
            return 0
        } finally {
            em.close()
        }
    }

    /**
     * 确认消息已被处理。
     *
     * @see MessageWriteRepository.acknowledgeMessage
     */
    override suspend fun acknowledgeMessage(messageId: String) {
        messageQueue.acknowledge(messageId)
    }

    /**
     * 启动定时刷写任务（D-11: 500ms 间隔）。
     *
     * 在后台协程中每 500ms 消费 Redis Stream 中的积压消息，触发批量刷入 MySQL。
     */
    fun startFlushTimer() {
        scope.launch {
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
        // D-85/M19: cancel 所有子协程，防止泄漏
        scope.cancel()
    }

    /**
     * 将 StreamMessage 解析为 MessageEntity。
     *
     * @param entry Redis Stream 消息条目
     * @return 解析后的 MessageEntity，关键字段缺失时返回 null
     */
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
            // M11: 解析 payload 字段（Base64 编码），用于死信记录恢复
            payload = body["payload"]?.let { java.util.Base64.getDecoder().decode(it) }
        }
    }
}
