package com.nebula.repository.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl

import com.nebula.common.session.SessionStore

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
) : SessionStore {
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
    override suspend fun save(token: String, userData: String, ttlSeconds: Long) {
        redis.setex("$KEY_PREFIX$token", ttlSeconds, userData)
    }

    /**
     * 按 token 查找 session 数据。
     *
     * @param token Session 令牌
     * @return 用户数据 JSON，不存在返回 null
     */
    override suspend fun findByToken(token: String): String? {
        return redis.get("$KEY_PREFIX$token")
    }

    /**
     * 滑动续期 TTL（D-13）。
     *
     * @param token Session 令牌
     * @param ttlSeconds 续期 TTL 秒数，默认 7 天
     */
    override suspend fun refreshTtl(token: String, ttlSeconds: Long) {
        redis.expire("$KEY_PREFIX$token", ttlSeconds)
    }

    /**
     * 删除 session token。
     *
     * @param token Session 令牌
     */
    override suspend fun delete(token: String) {
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
    override suspend fun saveRaw(key: String, value: String, ttlSeconds: Long) {
        redis.setex(key, ttlSeconds, value)
    }

    /**
     * 通用 Redis 字符串读取。
     *
     * @param key Redis key
     * @return 字符串值，不存在返回 null
     */
    override suspend fun findRaw(key: String): String? {
        return redis.get(key)
    }

    /**
     * 删除 Redis key。
     *
     * @param key 待删除的 key
     */
    override suspend fun deleteKey(key: String) {
        redis.del(key)
    }

    // ==================== 批量操作 ====================

    /**
     * 批量删除多个 key（D-65）。
     *
     * R-01 修复：移除 setAutoFlushCommands(false) + flushCommands() pipeline 模式。
     * 原实现修改 connection 级状态，与共享同一连接的其他协程产生竞态 —
     * 其他协程的命令也会被意外缓冲或提前 flush。
     * 改为逐条 del，Lettuce reactive API 内部已自动 pipeline 命令，性能接近。
     *
     * @param keys 待删除的 key 列表
     */
    suspend fun batchDelete(keys: List<String>) {
        if (keys.isEmpty()) return
        keys.forEach { redis.del(it) }
    }
}
