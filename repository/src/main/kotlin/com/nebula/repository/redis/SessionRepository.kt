package com.nebula.repository.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl

/**
 * Session Token 缓存操作封装（DB-02, D-13）。
 *
 * Redis key 格式: session:token:<token>
 * 滑动 TTL 刷新策略：活跃用户的每次请求自动续期 TTL（默认 7 天）。
 *
 * @param connection 共享 Redis 连接实例
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class SessionRepository(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())

    companion object {
        private const val KEY_PREFIX = "session:token:"
        private const val DEFAULT_TTL_SECONDS = 7 * 24 * 3600L  // 7 天
    }

    /** 保存 session token */
    suspend fun save(token: String, userData: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
        redis.setex("$KEY_PREFIX$token", ttlSeconds, userData)
    }

    /** 按 token 查找 session 数据 */
    suspend fun findByToken(token: String): String? {
        return redis.get("$KEY_PREFIX$token")
    }

    /** 滑动续期 TTL（D-13） */
    suspend fun refreshTtl(token: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
        redis.expire("$KEY_PREFIX$token", ttlSeconds)
    }

    /** 删除 session token */
    suspend fun delete(token: String) {
        redis.del("$KEY_PREFIX$token")
    }
}
