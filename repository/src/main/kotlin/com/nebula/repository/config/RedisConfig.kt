package com.nebula.repository.config

import com.nebula.repository.redis.MessageQueueRepository
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Lettuce Redis 客户端配置（D-03）。
 *
 * 使用单一 [StatefulRedisConnection] 供所有 Redis Repository 共享（D-05）。
 * 连接使用 [by lazy] 延迟初始化。
 *
 * @param host Redis 服务器地址，默认 127.0.0.1
 * @param port Redis 服务器端口，默认 6379
 */
class RedisConfig(
    private val host: String = "127.0.0.1",
    private val port: Int = 6379
) {
    /** Redis 客户端实例（延迟初始化） */
    val client: RedisClient by lazy {
        RedisClient.create(RedisURI.builder().withHost(host).withPort(port).build())
    }

    /** 共享 Redis 连接实例，供所有 Repository 通过构造参数注入（防连接泄漏） */
    val connection: StatefulRedisConnection<String, String> by lazy {
        client.connect()
    }

    /**
     * 初始化 Redis 基础设施。
     *
     * 启动时调用，确保消费者组等基础设施就绪。
     */
    suspend fun initializeRedisInfra(messageQueueRepo: MessageQueueRepository) {
        messageQueueRepo.ensureConsumerGroup()
    }

    /** 清理资源 */
    fun shutdown() {
        connection.close()
        client.shutdown()
    }
}
