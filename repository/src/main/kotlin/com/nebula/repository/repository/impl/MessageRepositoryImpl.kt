package com.nebula.repository.repository.impl

import com.nebula.common.init.DeadLetterCallback
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.MessageWriteRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.PersistenceException
import kotlinx.coroutines.*
import org.hibernate.exception.ConstraintViolationException
import kotlin.time.Duration.Companion.milliseconds

/**
 * 消息写入路径实现（DB-03, D-11）。
 *
 * 架构：Redis Stream（即时 ACK）→ 定时批量刷入 MySQL
 * - 消息先通过 [MessageQueueRepository.enqueue] 写入 Redis Stream，客户端即刻收到 ACK
 * - 后台协程每 500ms 检查积压消息，有消息即触发批量 INSERT
 * - 刷写失败的消息保留在 Redis Stream 中，下次定时任务重试
 *
 * @param messageQueue Redis Stream 消息队列操作封装
 * @param jpaTxRunner JPA 事务运行器（协程友好）
 * @param emf JPA EntityManagerFactory（保留引用以保持 EMF 生命周期由本类负责）
 */
class MessageRepositoryImpl(
    private val messageQueue: MessageQueueRepository,
    private val jpaTxRunner: JpaTxRunner,
    @Suppress("unused") private val emf: EntityManagerFactory
) : MessageWriteRepository {

    private val logger = KotlinLogging.logger {}

    /**
     * M11: 死信创建回调 — 由 Gateway 层在启动后注入。
     *
     * 签名：(conversationId, senderUid, messageType, content, payload, clientMsgId, clientTs, failReason) → Unit
     * 使用基本类型避免跨模块依赖。
     */
    var onDeadLetter: DeadLetterCallback? = null
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
     * D-03 修复策略：
     * 1. 快速路径：批量 INSERT 所有消息（常见场景，全部为新消息）
     * 2. UK 冲突降级：逐条 INSERT 隔离冲突消息，非冲突消息正常入库
     * 3. 逐条 UK 冲突 → 死信；逐条非 UK 异常 → 不 XACK 保留重试
     * 4. 无法解析的条目 → 直接 XACK（毒消息，无法处理）
     *
     * @see MessageWriteRepository.flushBatch
     */
    override suspend fun flushBatch(): Int {
        // D-xx 修复: 用 consumeWithRetry 替代 consume，兼顾 PEL 中因非 UK 异常失败而滞留、
        // 需重试的消息（XREADGROUP "0" 读取 PEL）与从未投递的新消息（XREADGROUP ">"）。
        val entries = messageQueue.consumeWithRetry(batchSize = 30, blockMs = 0)
        if (entries.isEmpty()) return 0

        // 解析并保留 entry ↔ entity 映射
        val parsed = entries.mapNotNull { entry ->
            parseToEntity(entry)?.let { entry to it }
        }

        // D-03 修复: 无法解析的条目不再静默 XACK 丢弃，而是先录死信（保留原始 body 便于排查），
        // 再 XACK 释放 pending，避免 Stream 无限堆积且无痕丢失。
        val unparseableEntries = entries.filter { entry -> parsed.none { it.first == entry } }
        for (entry in unparseableEntries) {
            try {
                onDeadLetter?.onUnparseableMessage(
                    entry.body ?: emptyMap(),
                    "无法解析的毒消息，关键字段缺失或非法: conv=${entry.body?.get("conversationId")}"
                )
            } catch (dlEx: Exception) {
                logger.error(dlEx) { "毒消息录死信失败，仍 XACK 避免堆积: id=${entry.id}" }
            }
            messageQueue.acknowledge(entry.id)
        }

        if (parsed.isEmpty()) return 0

        val messages = parsed.map { it.second }

        // 快速路径：批量插入
        try {
            jpaTxRunner.execute { em ->
                var count = 0
                for (msg in messages) {
                    em.persist(msg)
                    count++
                    if (count % 30 == 0) {
                        em.flush()
                        em.clear()
                    }
                }
            }
            // 全部成功，XACK 所有条目
            parsed.forEach { (entry, _) -> messageQueue.acknowledge(entry.id) }
            return messages.size
        } catch (e: PersistenceException) {
            val isConstraintViolation = e.cause is ConstraintViolationException ||
                (e.message?.contains("Duplicate", ignoreCase = true) == true) ||
                (e.message?.contains("ConstraintViolation", ignoreCase = true) == true)
            if (!isConstraintViolation) {
                // 非 UK 异常（如连接断开），保留在 Redis Stream 下次重试
                logger.error(e) { "批量刷写失败（非 UK 冲突），保留重试" }
                return 0
            }

            // D-03: UK 冲突降级为逐条插入，隔离冲突消息
            logger.warn { "批量插入 UK 冲突，降级为逐条插入以隔离冲突消息（共 ${parsed.size} 条）" }
            var successCount = 0
            for ((entry, msg) in parsed) {
                val handled = try {
                    jpaTxRunner.execute { em -> em.persist(msg) }
                    successCount++
                    true // 成功 → XACK
                } catch (e2: PersistenceException) {
                    val isUK = e2.cause is ConstraintViolationException ||
                        (e2.message?.contains("Duplicate", ignoreCase = true) == true) ||
                        (e2.message?.contains("ConstraintViolation", ignoreCase = true) == true)
                    if (isUK) {
                        // 个体 UK 冲突 → 死信 + XACK
                        onDeadLetter?.let { handler ->
                            try {
                                handler.onMessageFailed(
                                    msg.conversationId, msg.senderUid, msg.messageType,
                                    msg.content, msg.payload, msg.clientMessageId,
                                    msg.clientTs, "UK 冲突: client_msg_id=${msg.clientMessageId}"
                                )
                            } catch (dlEx: Exception) {
                                logger.error(dlEx) { "创建死信记录失败: clientMsgId=${msg.clientMessageId}" }
                            }
                        }
                        true // 已死信 → XACK
                    } else {
                        // 个体非 UK 异常 → 不 XACK，保留重试
                        logger.error(e2) { "逐条插入失败（非 UK）: clientMsgId=${msg.clientMessageId}" }
                        false
                    }
                } catch (e2: Exception) {
                    logger.error(e2) { "逐条插入异常: clientMsgId=${msg.clientMessageId}" }
                    false // 不 XACK，保留重试
                }
                if (handled) {
                    messageQueue.acknowledge(entry.id)
                }
            }
            return successCount
        } catch (e: Exception) {
            logger.error(e) { "批量刷写失败，保留在 Redis Stream 重试" }
            return 0
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
                delay(500.milliseconds)
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
            // 修复：兼容旧 key "msgId"（SendMessage 曾误用此 key，现已修正为 "id"）
            id = body["id"]?.toLongOrNull() ?: body["msgId"]?.toLongOrNull()
            createdAt = java.time.LocalDateTime.now()
            // M11: 解析 payload 字段（Base64 编码），用于死信记录恢复
            payload = body["payload"]?.let { java.util.Base64.getDecoder().decode(it) }
        }
    }
}
