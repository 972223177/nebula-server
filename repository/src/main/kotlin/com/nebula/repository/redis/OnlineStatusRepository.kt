package com.nebula.repository.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl

/**
 * 用户在线状态缓存操作封装（DB-02, D-14）。
 *
 * Redis key 格式: online:user:<userId>
 * 断连即标记离线 + 短 TTL（60s）（D-14）。
 *
 * @param connection 共享 Redis 连接实例
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class OnlineStatusRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())

    companion object {
        private const val KEY_PREFIX = "online:user:"
        private const val TTL_SECONDS = 60L  // D-14: 短 TTL
    }

    /** 标记用户在线 */
    suspend fun setOnline(userId: Long, statusData: String) {
        redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, statusData)
    }

    /** 标记用户离线 */
    suspend fun setOffline(userId: Long) {
        redis.del("$KEY_PREFIX$userId")
    }

    /** 检查用户是否在线 */
    suspend fun isOnline(userId: Long): Boolean {
        return redis.get("$KEY_PREFIX$userId") != null
    }
}
