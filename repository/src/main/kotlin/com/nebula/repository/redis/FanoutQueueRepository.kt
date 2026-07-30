package com.nebula.repository.redis

import com.nebula.common.redis.RedisKeys
import com.nebula.common.redis.RedisStreamQueue
import com.nebula.common.redis.RedisStreamRecord
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.*
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList

/**
 * fan-out 事件 Redis Stream 封装（§七 Durable Outbox）。
 *
 * 与 [MessageQueueRepository] 同构：生产端 XADD、消费端 XREADGROUP + PEL 重试、确认 XACK。
 * 独立 stream key（[RedisKeys.FANOUT_STREAM_KEY]）与消费者组（fanout_workers），
 * 不与消息持久化刷写路径争用同一消费者组。
 *
 * 实现 [RedisStreamQueue] 通用端口（契约定义在 common）：Stream 原语（入队/消费/确认）。
 * 本类不耦合 fan-out 专属语义，保持为通用 Redis Stream 基础设施实现。
 * gateway 仅依赖接口、不依赖本类，保持 `proto ← common ← repository ← service ← gateway` 单向依赖链。
 *
 * @param connection 共享 Redis 连接实例（复用 messageQueueConnection，非阻塞消费）
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class FanoutQueueRepositoryImpl(
    private val connection: StatefulRedisConnection<String, String>
) : RedisStreamQueue {
    private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())
    private val logger = KotlinLogging.logger {}

    companion object {
        private const val CONSUMER_GROUP = "fanout_workers"
        private const val CONSUMER_NAME = "fanout-worker-1"
    }

    /** 确保消费者组存在（启动时调用，重复调用安全）。 */
    override suspend fun ensureConsumerGroup() {
        try {
            redis.xgroupCreate(
                XReadArgs.StreamOffset.from(RedisKeys.FANOUT_STREAM_KEY, "0-0"),
                CONSUMER_GROUP,
                XGroupCreateArgs.Builder.mkstream(true)
            )
        } catch (e: RedisCommandExecutionException) {
            if (!(e.message?.contains("BUSYGROUP") ?: false)) throw e
        }
    }

    /** 将 fan-out 事件写入 Stream。 */
    override suspend fun enqueue(message: Map<String, String>): String? {
        return redis.xadd(
            RedisKeys.FANOUT_STREAM_KEY,
            XAddArgs.Builder.maxlen(100000).approximateTrimming(),
            message
        )
    }

    /**
     * 消费一批（兼顾 PEL 待重试 + 新消息），blockMs=0 非阻塞。
     * 参考 [MessageQueueRepository.consumeWithRetry] 的 PEL 重试设计。
     * 返回 [RedisStreamRecord]（与 Lettuce [StreamMessage] 解耦），供 gateway 层直接消费。
     */
    override suspend fun consumeWithRetry(batchSize: Long, blockMs: Long): List<RedisStreamRecord> {
        val result = mutableListOf<RedisStreamRecord>()
        try {
            val pendingArgs = XReadArgs.Builder.count(batchSize)
            if (blockMs > 0) pendingArgs.block(blockMs)
            result += redis.xreadgroup(
                Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
                pendingArgs,
                XReadArgs.StreamOffset.from(RedisKeys.FANOUT_STREAM_KEY, "0")
            ).toList().map { RedisStreamRecord(it.id, it.body) }
        } catch (e: Exception) {
            logger.warn(e) { "读取 fanout PEL 待重试消息失败，跳过" }
        }
        val newArgs = XReadArgs.Builder.count(batchSize)
        if (blockMs > 0) newArgs.block(blockMs)
        result += redis.xreadgroup(
            Consumer.from(CONSUMER_GROUP, CONSUMER_NAME),
            newArgs,
            XReadArgs.StreamOffset.lastConsumed(RedisKeys.FANOUT_STREAM_KEY)
        ).toList().map { RedisStreamRecord(it.id, it.body) }
        return result
    }

    /** 确认消息已处理。 */
    override suspend fun acknowledge(messageId: String) {
        redis.xack(RedisKeys.FANOUT_STREAM_KEY, CONSUMER_GROUP, messageId)
    }
}
