package com.nebula.repository.redis

import io.lettuce.core.*
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import io.lettuce.core.models.stream.PendingMessage
import io.lettuce.core.models.stream.PendingMessages
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

/**
 * Redis Stream 消息队列封装（D-04）。
 *
 * 提供生产端 XADD、消费端 XREADGROUP 和确认 XACK 操作。
 * 所有方法为挂起函数，与 Netty 异步模型对齐。
 *
 * @param connection 共享 Redis 连接实例
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class MessageQueueRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())

    companion object {
        /** Redis Stream 键名 */
        private const val STREAM_KEY = "queue:messages"
        /** 消费者组名称 */
        private const val CONSUMER_GROUP = "flush-workers"
        /** 消费者名称 */
        private const val CONSUMER_NAME = "worker-1"
        /** 去重 SETNX key 前缀 */
        private const val DEDUP_KEY_PREFIX = "dedup:msg:"
        /** 去重 TTL：7 天 */
        private const val DEDUP_TTL_SECONDS = 7 * 24 * 3600L
    }

    /**
     * 确保消费者组存在（启动时调用，重复调用安全）。
     * 捕获 BUSYGROUP 异常以容忍消费者组已存在的场景。
     */
    suspend fun ensureConsumerGroup() {
        try {
            redis.xgroupCreate(
                XReadArgs.StreamOffset.from(STREAM_KEY, "0-0"),
                CONSUMER_GROUP,
                XGroupCreateArgs.Builder.mkstream(true)
            )
        } catch (e: RedisCommandExecutionException) {
            if (!(e.message?.contains("BUSYGROUP") ?: false)) throw e
            // 消费者组已存在，忽略
        }
    }

    /**
     * 将消息写入 Redis Stream。
     *
     * @param message 消息键值对
     * @return Redis Stream 消息 ID，失败返回 null
     */
    suspend fun enqueue(message: Map<String, String>): String? {
        return redis.xadd(
            STREAM_KEY,
            XAddArgs.Builder.maxlen(100000).approximateTrimming(),
            message
        )
    }

    /**
     * 消费一批消息。
     *
     * @param batchSize 批量消费条数上限
     * @param blockMs 阻塞等待时间（毫秒），0 表示非阻塞
     * @return 消费到的消息列表
     */
    suspend fun consume(batchSize: Long, blockMs: Long): List<StreamMessage<String, String>> {
        return redis.xreadgroup(
            Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
            XReadArgs.Builder.count(batchSize).block(blockMs),
            XReadArgs.StreamOffset.lastConsumed(STREAM_KEY)
        ).toList()
    }

    /**
     * 确认消息已被处理。
     *
     * @param messageId Redis Stream 消息 ID
     */
    suspend fun acknowledge(messageId: String) {
        redis.xack(STREAM_KEY, CONSUMER_GROUP, messageId)
    }

    /**
     * 去重检测：SETNX + 设置 TTL（D-72）。
     *
     * 使用 Redis SETNX 原子操作检测 clientMessageId 是否已存在。
     * 如果键不存在（首次发送），设置值为 senderUid 并返回 true。
     * 如果键已存在（重复消息），返回 false。
     * 连接异常时 fail-open 返回 true，由 MySQL 唯一索引作为最终去重保障。
     *
     * @param clientMsgId 客户端消息幂等标识
     * @param senderUid 发送者用户 ID
     * @return true 消息未重复（或无法判断），false 检测到重复
     */
    suspend fun checkAndSetDedup(clientMsgId: String, senderUid: Long): Boolean {
        val key = "$DEDUP_KEY_PREFIX$clientMsgId"
        return try {
            val result = redis.setnx(key, senderUid.toString())
            if (result == true) {
                redis.expire(key, DEDUP_TTL_SECONDS)
            }
            result ?: true // null 时 fail-open，MySQL 唯一索引作为兜底
        } catch (e: Exception) {
            true // 连接异常时 fail-open，MySQL 唯一索引作为兜底（D-72）
        }
    }

    /**
     * 查询消费者组的 Pending Entry List（PEL）统计信息（DB-05）。
     *
     * PEL 中存储了已投递但未确认（XACK）的消息。
     * 断线重连时，可通过此方法判断是否有离线消息待处理。
     */
    suspend fun getPendingCount(): PendingMessages? {
        return redis.xpending(STREAM_KEY, CONSUMER_GROUP)
    }

    /**
     * 获取 PEL 中的未确认消息列表（DB-05）。
     *
     * 用于断线重连时拉取离线消息。
     *
     * @param start 起始 ID（"-" 表示最早）
     * @param end 结束 ID（"+" 表示最新）
     * @param count 返回条数上限
     */
    fun getPendingMessages(start: String = "-", end: String = "+", count: Long = 100): Flow<PendingMessage> {
        return redis.xpending(
            STREAM_KEY, CONSUMER_GROUP,
            Range.create(start, end),
            Limit.from(count)
        )
    }

    /**
     * 按消息 ID 范围从 Stream 中读取消息（DB-05）。
     *
     * 配合 getPendingMessages 使用：获取 PEL ID 列表后，读取消息完整内容。
     *
     * @param startId 起始 ID
     * @param endId 结束 ID
     * @param count 返回条数上限
     */
    fun readMessagesById(startId: String, endId: String, count: Long = 100): Flow<StreamMessage<String, String>> {
        return redis.xrange(
            STREAM_KEY,
            Range.create(startId, endId),
            Limit.from(count)
        )
    }
}
