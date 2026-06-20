package com.nebula.service.admin

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.common.init.DeadLetterCallback
import com.nebula.repository.dao.DeadLetterDao
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.entity.DeadLetterEntity
import com.nebula.repository.redis.MessageQueueRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.persistence.OptimisticLockException  // 保留供其他方法使用（如 retry）
import java.time.ZoneOffset

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
 * 事务通过 [JpaTxRunner] 管理（替代原 Spring TransactionTemplate）。
 */
class DeadLetterService(
    private val deadLetterDao: DeadLetterDao,
    private val txRunner: JpaTxRunner,
    private val messageQueueRepository: MessageQueueRepository,
    private val idGenerator: SnowflakeIdGenerator
) : DeadLetterCallback {

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
     * 死信回调实现（D-28 跨层桥接）。
     *
     * 由 repository 模块在消息异步刷盘失败时调用，
     * 创建死信记录。
     */
    override suspend fun onMessageFailed(
        convId: String,
        senderUid: Long,
        msgType: Int,
        content: String,
        payload: ByteArray?,
        clientMsgId: String?,
        clientTs: Long,
        reason: String
    ) {
        create(
            conversationId = convId,
            senderUid = senderUid,
            messageType = msgType,
            content = content,
            payload = payload,
            clientMsgId = clientMsgId,
            clientTs = clientTs,
            failReason = reason
        )
    }

    /**
     * 创建死信记录（D-75）。
     *
     * 当消息投递失败达到最大重试次数时，由 ChatService 调用此方法写入死信表。
     * 返回 [DeadLetterDTO] 替代在 gateway 层直接暴露 JPA 实体。
     *
     * @param conversationId 会话 ID
     * @param senderUid 发送者用户 ID
     * @param messageType 消息类型
     * @param content 消息内容
     * @param payload 消息序列化字节（可选）
     * @param clientMsgId 客户端消息 ID（可选）
     * @param clientTs 客户端时间戳
     * @param failReason 失败原因描述
     * @return 创建的死信 DTO
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
    ): DeadLetterDTO {
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
        val saved = txRunner.execute { em -> deadLetterDao.insert(em, entity) }
        logger.warn { "死信记录已创建: id=${saved.id}, conv=$conversationId, reason=$failReason" }
        return saved.toDTO()
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
        val pendingItems = txRunner.execute { em ->
            deadLetterDao.findByStatusAndFailCountLessThan(
                em, STATUS_PENDING, MAX_COMPENSATE_RETRIES, offset = 0, limit = BATCH_SIZE
            )
        }
        if (pendingItems.isEmpty()) return 0

        var processedCount = 0
        for (item in pendingItems) {
            try {
                // 乐观锁更新状态为 retrying
                item.status = STATUS_RETRYING
                item.failCount = item.failCount + 1
                txRunner.execute { em -> deadLetterDao.update(em, item) }

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
                messageQueueRepository.enqueue(streamFields)

                // 更新状态为重试成功
                item.status = STATUS_RETRY_SUCCESS
                txRunner.execute { em -> deadLetterDao.update(em, item) }
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
        val entity = txRunner.execute { em -> deadLetterDao.findById(em, id) } ?: return false

        if (entity.status == STATUS_RETRY_SUCCESS) {
            logger.warn { "死信已重试成功，跳过 id=$id" }
            return false
        }

        return try {
            entity.status = STATUS_RETRYING
            entity.failCount = entity.failCount + 1
            txRunner.execute { em -> deadLetterDao.update(em, entity) }

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
            messageQueueRepository.enqueue(streamFields)

            entity.status = STATUS_RETRY_SUCCESS
            txRunner.execute { em -> deadLetterDao.update(em, entity) }
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
     * @return DTO 分页结果
     */
    suspend fun query(page: Int, pageSize: Int, status: String?): ListPage<DeadLetterDTO> {
        val actualPage = maxOf(1, page)
        val actualPageSize = pageSize.coerceIn(1, 100)
        val offset = (actualPage - 1) * actualPageSize

        return txRunner.execute { em ->
            if (status.isNullOrBlank()) {
                // 全量查询：使用 DAO 统一入口（避免 Service 内嵌 JPQL）
                val allItems = deadLetterDao.findAllOrderByCreatedAtAsc(em, offset, actualPageSize)
                val total = deadLetterDao.countAll(em)
                ListPage(allItems.map { it.toDTO() }, total)
            } else {
                // M15: 使用 countByStatus 获取精确的过滤后总数，而非未过滤的 findAll().totalElements
                val items = deadLetterDao.findByStatusOrderByCreatedAtAsc(
                    em, status, offset = offset, limit = actualPageSize
                )
                val total = deadLetterDao.countByStatus(em, status)
                ListPage(items.map { it.toDTO() }, total)
            }
        }
    }

    /**
     * 将 [DeadLetterEntity] 转换为 [DeadLetterDTO]。
     *
     * 处理 `createdAt` 的 LocalDateTime → 毫秒时间戳转换，以及 `id` 为 null 时的默认值。
     *
     * @return 转换后的 DTO
     */
    private fun DeadLetterEntity.toDTO() = DeadLetterDTO(
        id = id ?: 0,
        msgId = msgId,
        conversationId = conversationId,
        senderUid = senderUid,
        failReason = failReason,
        failCount = failCount,
        status = status,
        createdAt = createdAt?.toInstant(ZoneOffset.UTC)?.toEpochMilli() ?: 0L
    )

    /**
     * 标记失败次数超过阈值的死信为永久失败。
     *
     * 由 compensate() 在每次补偿完成后自动调用。
     * M16: 使用 findByStatusAndFailCountGreaterThanEqual 替代原有的 findByStatusAndFailCountLessThan(status, 0) 死查询。
     * 捕获 OptimisticLockException 跳过并发冲突记录。
     */
    suspend fun markPermanentFailed() {
        // M16: failCount >= MAX_COMPENSATE_RETRIES 才标记永久失败，修复 failCount < 0 死查询
        // 性能优化（Phase 4.4）：用单条 JPQL UPDATE 一次性批量更新所有超限记录，
        // 避免逐条循环 + 每条一个独立事务（之前的实现 N 个超限记录 = N 个事务 = N 次连接获取）
        val affectedRows = txRunner.execute { em ->
            deadLetterDao.executeUpdate(
                em,
                """
                UPDATE DeadLetterEntity d
                SET d.status = :permanentFailed
                WHERE d.status = :retrying AND d.failCount >= :maxRetries
                """.trimIndent(),
                "permanentFailed" to STATUS_PERMANENT_FAILED,
                "retrying" to STATUS_RETRYING,
                "maxRetries" to MAX_COMPENSATE_RETRIES
            )
        }
        if (affectedRows > 0) {
            logger.warn { "死信已达最大重试次数，标记为永久失败: count=$affectedRows" }
        }
    }
}

/**
 * 简化版分页结果 — 替代 Spring Data 的 Page 接口，避免依赖。
 */
data class ListPage<T>(
    val items: List<T>,
    val total: Long
)
