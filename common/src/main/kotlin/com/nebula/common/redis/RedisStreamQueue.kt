package com.nebula.common.redis

/**
 * Redis Stream 队列通用端口（共享基础设施原语）。
 *
 * 封装 Redis Stream 的生产 / 消费 / 确认语义（XADD / XREADGROUP + PEL 重试 / XACK），
 * 与具体业务无关，gateway / repository 等模块均可复用（当前 fan-out 事件队列即其一例）。
 * 抽象为不依赖 Lettuce 的纯契约，置于 common 供各层依赖，避免上层直接接触 Redis 客户端类型。
 */
interface RedisStreamQueue {
    /** 确保消费者组存在（启动时调用，重复调用安全）。 */
    suspend fun ensureConsumerGroup()

    /** 将事件写入 Stream。 */
    suspend fun enqueue(message: Map<String, String>): String?

    /**
     * 消费一批（兼顾 PEL 待重试 + 新消息），blockMs=0 非阻塞。
     *
     * @return 解耦后的流记录列表（不含 Lettuce 类型，供消费方直接处理）
     */
    suspend fun consumeWithRetry(batchSize: Long, blockMs: Long): List<RedisStreamRecord>

    /** 确认消息已处理（XACK）。 */
    suspend fun acknowledge(messageId: String)
}

/**
 * Redis Stream 记录（与 Lettuce [io.lettuce.core.StreamMessage] 解耦）。
 *
 * 将 Stream 消息的 id 与字段表封装为纯 Kotlin 类型，避免上层直接接触 Lettuce 类型、
 * 也避免 common 模块被迫引入 Lettuce 依赖。
 *
 * @param id Redis Stream 消息 ID
 * @param body 字段表
 */
data class RedisStreamRecord(
    val id: String,
    val body: Map<String, String>
)
