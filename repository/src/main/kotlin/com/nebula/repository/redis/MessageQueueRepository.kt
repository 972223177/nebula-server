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

    /** 将消息写入 Redis Stream */
    suspend fun enqueue(message: Map<String, String>): String? {
        return redis.xadd(
            STREAM_KEY,
            XAddArgs.Builder.maxlen(100000).approximateTrimming(),
            message
        )
    }

    /** 消费一批消息 */
    suspend fun consume(batchSize: Long, blockMs: Long): List<StreamMessage<String, String>> {
        return redis.xreadgroup(
            Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
            XReadArgs.Builder.count(batchSize).block(blockMs),
            XReadArgs.StreamOffset.lastConsumed(STREAM_KEY)
        ).toList()
    }

    /** 确认消息已被处理 */
    suspend fun acknowledge(messageId: String) {
        redis.xack(STREAM_KEY, CONSUMER_GROUP, messageId)
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
