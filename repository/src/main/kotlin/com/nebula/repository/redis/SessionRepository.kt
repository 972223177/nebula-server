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

    /**
     * 保存 session token。
     *
     * @param token Session 令牌
     * @param userData 用户数据（JSON 格式）
     * @param ttlSeconds TTL 秒数，默认 7 天
     */
    suspend fun save(token: String, userData: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
        redis.setex("$KEY_PREFIX$token", ttlSeconds, userData)
    }

    /**
     * 按 token 查找 session 数据。
     *
     * @param token Session 令牌
     * @return 用户数据 JSON，不存在返回 null
     */
    suspend fun findByToken(token: String): String? {
        return redis.get("$KEY_PREFIX$token")
    }

    /**
     * 滑动续期 TTL（D-13）。
     *
     * @param token Session 令牌
     * @param ttlSeconds 续期 TTL 秒数，默认 7 天
     */
    suspend fun refreshTtl(token: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
        redis.expire("$KEY_PREFIX$token", ttlSeconds)
    }

    /**
     * 删除 session token。
     *
     * @param token Session 令牌
     */
    suspend fun delete(token: String) {
        redis.del("$KEY_PREFIX$token")
    }

    // ==================== 通用 Redis key/value 操作 ====================
    // 以下方法不校验 key 前缀，由调用方负责 key 命名逻辑

    /**
     * 通用 Redis 字符串写入（用于设备类型映射等场景）。
     *
     * @param key Redis key
     * @param value 字符串值
     * @param ttlSeconds TTL 秒数，默认 7 天
     */
    suspend fun saveRaw(key: String, value: String, ttlSeconds: Long = DEFAULT_TTL_SECONDS) {
        redis.setex(key, ttlSeconds, value)
    }

    /**
     * 通用 Redis 字符串读取。
     *
     * @param key Redis key
     * @return 字符串值，不存在返回 null
     */
    suspend fun findRaw(key: String): String? {
        return redis.get(key)
    }

    /**
     * 删除 Redis key。
     *
     * @param key 待删除的 key
     */
    suspend fun deleteKey(key: String) {
        redis.del(key)
    }

    // ==================== Pipeline 批量操作 ====================

    /**
     * 使用 Redis pipeline 批量删除多个 key（D-65）。
     *
     * 使用 Lettuce setAutoFlushCommands(false) + flushCommands() 实现 pipeline 模式，
     * 将多个 DEL 命令合并为一次网络往返，适用于连接清理等需要批量删除的场景。
     *
     * @param keys 待删除的 key 列表
     */
    suspend fun batchDelete(keys: List<String>) {
        if (keys.isEmpty()) return
        connection.setAutoFlushCommands(false)
        try {
            val async = connection.async()
            keys.forEach { async.del(it) }
            connection.flushCommands()
        } finally {
            // 异常时也要恢复 autoFlush，防止后续操作被缓冲
            connection.setAutoFlushCommands(true)
        }
    }
}
