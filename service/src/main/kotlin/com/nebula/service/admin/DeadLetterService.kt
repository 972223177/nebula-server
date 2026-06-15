package com.nebula.service.admin

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.entity.DeadLetterEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.DeadLetterRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.OptimisticLockException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable

/**
 * 死信服务 — 提供死信记录的创建、补偿重试、查询和管理（Phase 10, D-76）。
 *
 * 职责：
 * - 创建死信记录：当消息投递失败达到最大重试次数时调用
 * - 补偿重试：定时任务扫描 pending 状态的死信，重新入队
 * - 手动重试：Admin API 支持单条死信手动重试
 * - 分页查询：支持按状态过滤的死信记录查询
 * - 永久失败标记：将失败次数超过阈值的死信标记为 permanent_failed
 *
 * @param deadLetterRepository 死信 JPA 仓库
 * @param messageQueueRepository 消息队列仓库，用于 compensate 时重新入队
 * @param idGenerator Snowflake ID 生成器
 */
class DeadLetterService(
    private val deadLetterRepository: DeadLetterRepository,
    private val messageQueueRepository: MessageQueueRepository,
    private val idGenerator: SnowflakeIdGenerator
) {

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}

        /** 最大补偿重试次数：超过此次数标记为永久失败 */
        private const val MAX_COMPENSATE_RETRIES = 5

        /** 最大投递重试次数（ChatService 投递失败后的死信阈值） */
        private const val MAX_PENDING_RETRIES = 10

        /** 补偿查询每批大小 */
        private const val BATCH_SIZE = 100

        /** 死信状态：待处理 */
        const val STATUS_PENDING = "pending"

        /** 死信状态：重试中 */
        const val STATUS_RETRYING = "retrying"

        /** 死信状态：永久失败 */
        const val STATUS_PERMANENT_FAILED = "permanent_failed"

        /** 死信状态：重试成功 */
        const val STATUS_RETRY_SUCCESS = "retry_success"
    }

    /**
     * 创建死信记录（D-75）。
     *
     * 当消息投递失败达到最大重试次数时，由 ChatService 调用此方法写入死信表。
     *
     * @param conversationId 会话 ID
     * @param senderUid 发送者用户 ID
     * @param messageType 消息类型
     * @param content 消息内容
     * @param payload 消息序列化字节（可选）
     * @param clientMsgId 客户端消息 ID（可选）
     * @param clientTs 客户端时间戳
     * @param failReason 失败原因描述
     * @return 创建的 DeadLetterEntity
     */
    suspend fun create(
        conversationId: String,
        senderUid: Long,
        messageType: Int,
        content: String,
        payload: ByteArray? = null,
        clientMsgId: String? = null,
        clientTs: Long,
        failReason: String
    ): DeadLetterEntity {
        val entity = DeadLetterEntity(
            conversationId = conversationId,
            senderUid = senderUid,
            messageType = messageType,
            content = content,
            payload = payload,
            clientMsgId = clientMsgId,
            clientTs = clientTs,
            failReason = failReason,
            failCount = 0,
            status = STATUS_PENDING
        )
        return withContext(Dispatchers.IO) {
            deadLetterRepository.save(entity)
        }.also {
            logger.warn { "死信记录已创建: id=${it.id}, conv=$conversationId, reason=$failReason" }
        }
    }

    /**
     * 补偿任务：扫描 pending 状态的死信，重新入队。
     *
     * 查询 failCount < MAX_COMPENSATE_RETRIES 的 pending 死信，
     * 逐个更新状态为 retrying 后重新写入 Redis Stream。
     * 更新时捕获 OptimisticLockException 跳过并发冲突记录。
     *
     * @return 本次补偿处理的死信数量
     */
    suspend fun compensate(): Int {
        val pendingItems = withContext(Dispatchers.IO) {
            deadLetterRepository.findByStatusAndFailCountLessThan(
                STATUS_PENDING, MAX_COMPENSATE_RETRIES, Pageable.ofSize(BATCH_SIZE)
            )
        }
        if (pendingItems.isEmpty()) return 0

        var processedCount = 0
        for (item in pendingItems) {
            try {
                // 乐观锁更新状态为 retrying
                item.status = STATUS_RETRYING
                item.failCount = item.failCount + 1
                withContext(Dispatchers.IO) {
                    deadLetterRepository.save(item)
                }

                // 重新写入 Redis Stream（M09: payload 从 DeadLetterEntity 恢复为 Base64）
                val streamFields = mapOf(
                    "msg_id" to (item.msgId?.toString() ?: ""),
                    "conversation_id" to item.conversationId,
                    "sender_uid" to item.senderUid.toString(),
                    "message_type" to item.messageType.toString(),
                    "content" to item.content,
                    "client_message_id" to (item.clientMsgId ?: ""),
                    "client_ts" to item.clientTs.toString(),
                    "server_ts" to System.currentTimeMillis().toString(),
                    "payload" to (item.payload?.let { java.util.Base64.getEncoder().encodeToString(it) } ?: "")
                )
                withContext(Dispatchers.IO) {
                    messageQueueRepository.enqueue(streamFields)
                }

                // 更新状态为重试成功
                item.status = STATUS_RETRY_SUCCESS
                withContext(Dispatchers.IO) {
                    deadLetterRepository.save(item)
                }
                processedCount++
            } catch (e: OptimisticLockException) {
                // 并发冲突，跳过此条由其他补偿任务处理
                logger.warn(e) { "死信补偿并发冲突，跳过 id=${item.id}" }
            } catch (e: Exception) {
                logger.error(e) { "死信补偿失败 id=${item.id}" }
            }
        }

        // 补偿完成后标记永久失败
        markPermanentFailed()

        logger.info { "死信补偿完成: 处理 $processedCount 条" }
        return processedCount
    }

    /**
     * 手动重试单条死信记录。
     *
     * 由 Admin API 调用（RetryDeadLetterHandler）。
     * 尝试重新写入 Redis Stream，写入成功后更新状态为重试成功。
     *
     * @param id 死信记录 ID
     * @return true 重试成功，false 失败（死信不存在或状态不允许）
     */
    suspend fun retry(id: Long): Boolean {
        val entity = withContext(Dispatchers.IO) {
            deadLetterRepository.findById(id).orElse(null)
        } ?: return false

        if (entity.status == STATUS_RETRY_SUCCESS) {
            logger.warn { "死信已重试成功，跳过 id=$id" }
            return false
        }

        return try {
            entity.status = STATUS_RETRYING
            entity.failCount = entity.failCount + 1
            withContext(Dispatchers.IO) {
                deadLetterRepository.save(entity)
            }

            // 重新写入 Redis Stream（M10: payload 从 DeadLetterEntity 恢复为 Base64）
            val streamFields = mapOf(
                "msg_id" to (entity.msgId?.toString() ?: ""),
                "conversation_id" to entity.conversationId,
                "sender_uid" to entity.senderUid.toString(),
                "message_type" to entity.messageType.toString(),
                "content" to entity.content,
                "client_message_id" to (entity.clientMsgId ?: ""),
                "client_ts" to entity.clientTs.toString(),
                "server_ts" to System.currentTimeMillis().toString(),
                "payload" to (entity.payload?.let { java.util.Base64.getEncoder().encodeToString(it) } ?: "")
            )
            withContext(Dispatchers.IO) {
                messageQueueRepository.enqueue(streamFields)
            }

            entity.status = STATUS_RETRY_SUCCESS
            withContext(Dispatchers.IO) {
                deadLetterRepository.save(entity)
            }
            true
        } catch (e: OptimisticLockException) {
            logger.warn(e) { "手动重试并发冲突，跳过 id=$id" }
            false
        } catch (e: Exception) {
            logger.error(e) { "手动重试失败 id=$id" }
            false
        }
    }

    /**
     * 分页查询死信记录。
     *
     * @param page 页码（从 1 开始）
     * @param pageSize 每页条数
     * @param status 过滤状态（为空时查询全部）
     * @return 分页结果
     */
    suspend fun query(page: Int, pageSize: Int, status: String?): Page<DeadLetterEntity> {
        val pageable = PageRequest.of(page - 1, pageSize)
        return withContext(Dispatchers.IO) {
            if (status.isNullOrBlank()) {
                deadLetterRepository.findAll(pageable)
            } else {
                // M15: 使用 countByStatus 获取精确的过滤后总数，而非未过滤的 findAll().totalElements
                deadLetterRepository.findByStatusOrderByCreatedAtAsc(status, pageable)
                    .let { items ->
                        val total = withContext(Dispatchers.IO) {
                            deadLetterRepository.countByStatus(status)
                        }
                        org.springframework.data.domain.PageImpl(items, pageable, total)
                    }
            }
        }
    }

    /**
     * 标记失败次数超过阈值的死信为永久失败。
     *
     * 由 compensate() 在每次补偿完成后自动调用。
     * M16: 使用 findByStatusAndFailCountGreaterThanEqual 替代原有的 findByStatusAndFailCountLessThan(status, 0) 死查询。
     * 捕获 OptimisticLockException 跳过并发冲突记录。
     */
    suspend fun markPermanentFailed() {
        // M16: failCount >= MAX_COMPENSATE_RETRIES 才标记永久失败，修复 failCount < 0 死查询
        val expiredItems = withContext(Dispatchers.IO) {
            deadLetterRepository.findByStatusAndFailCountGreaterThanEqual(
                STATUS_RETRYING, MAX_COMPENSATE_RETRIES, Pageable.ofSize(BATCH_SIZE)
            )
        }

        for (item in expiredItems) {
            try {
                item.status = STATUS_PERMANENT_FAILED
                withContext(Dispatchers.IO) {
                    deadLetterRepository.save(item)
                }
                logger.warn { "死信已达最大重试次数，标记为永久失败: id=${item.id}" }
            } catch (e: OptimisticLockException) {
                logger.warn(e) { "标记永久失败并发冲突，跳过 id=${item.id}" }
            }
        }
    }
}
