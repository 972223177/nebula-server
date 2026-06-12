package com.nebula.repository.config

import com.nebula.repository.redis.MessageQueueRepository
import io.lettuce.core.RedisClient
import io.lettuce.core.RedisURI
import io.lettuce.core.api.StatefulRedisConnection

/**
 * Lettuce Redis 客户端配置（D-03）。
 *
 * 维护两条独立的 [StatefulRedisConnection]：
 * - [connection]：供 SessionRepository / OnlineStatusRepository / PrivacyRepository 等延时敏感操作使用
 * - [messageQueueConnection]：专供 [MessageQueueRepository] 的消息队列批量操作使用
 *
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

    /** 共享 Redis 连接实例，供延时敏感的 Repository 使用（Session / OnlineStatus / Privacy） */
    val connection: StatefulRedisConnection<String, String> by lazy {
        client.connect()
    }

    /**
     * 消息队列专用 Redis 连接实例（D-29）。
     *
     * 与 [connection] 使用同一 [RedisClient] 但开辟独立连接通道，
     * 避免 [MessageQueueRepository] 的 XREADGROUP/XADD/XACK 批量操作
     * 阻塞 SessionRegistry 等延时敏感操作的 Redis 调用。
     */
    val messageQueueConnection: StatefulRedisConnection<String, String> by lazy {
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
        messageQueueConnection.close()
        connection.close()
        client.shutdown()
    }
}
