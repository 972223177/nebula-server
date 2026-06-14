package com.nebula.repository.redis

import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.flow.toList

/**
 * 用户在线状态数据（JSON 存储）。
 *
 * @param status 在线状态：0=离线 1=在线 2=隐藏（D-57）
 * @param lastActiveAt 最后活跃时间（毫秒时间戳）
 */
@Serializable
data class OnlineStatusData(
    val status: Int,
    val lastActiveAt: Long
)

/**
 * 用户在线状态缓存操作封装（DB-02, D-14, D-57）。
 *
 * Redis key 格式: online:user:<userId>
 * 存储格式: JSON `{"status":1,"lastActiveAt":1234567890}`
 * TTL: 60s（D-14 短 TTL），通过 refreshTtl 刷新
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

        private val json = Json { ignoreUnknownKeys = true }
    }

    /**
     * 标记用户在线（status=1），写入 JSON 并设置 TTL。
     *
     * @param userId 用户 ID
     */
    suspend fun setOnline(userId: Long) {
        val data = OnlineStatusData(status = 1, lastActiveAt = System.currentTimeMillis())
        val jsonStr = json.encodeToString(data)
        redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, jsonStr)
    }

    /**
     * 标记用户隐藏（status=2），写入 JSON 并设置 TTL。
     *
     * @param userId 用户 ID
     */
    suspend fun setHidden(userId: Long) {
        val data = OnlineStatusData(status = 2, lastActiveAt = System.currentTimeMillis())
        val jsonStr = json.encodeToString(data)
        redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, jsonStr)
    }

    /**
     * 标记用户离线，删除 key。
     *
     * @param userId 用户 ID
     */
    suspend fun setOffline(userId: Long) {
        redis.del("$KEY_PREFIX$userId")
    }

    /**
     * 获取用户在线状态数据，key 不存在返回 null。
     *
     * @param userId 用户 ID
     * @return 在线状态数据，key 不存在或解析失败返回 null
     */
    suspend fun getStatus(userId: Long): OnlineStatusData? {
        val jsonStr = redis.get("$KEY_PREFIX$userId") ?: return null
        return try {
            json.decodeFromString<OnlineStatusData>(jsonStr)
        } catch (e: Exception) {
            // 兼容旧格式（纯文本 "online" 等），降级为 null
            null
        }
    }

    /**
     * 检查用户是否在线（status >= 1 即认为在线，兼容 status=2 隐藏场景）。
     *
     * @param userId 用户 ID
     * @return true 表示在线（含隐藏），false 表示离线
     */
    suspend fun isOnline(userId: Long): Boolean {
        return getStatus(userId) != null
    }

    /**
     * 刷新 key 的 TTL 为 60s。
     *
     * @param userId 用户 ID
     */
    suspend fun refreshTtl(userId: Long) {
        redis.expire("$KEY_PREFIX$userId", TTL_SECONDS)
    }

    /**
     * 批量获取用户在线状态（D-50 客户端上线补偿拉取）。
     *
     * @param userIds 用户 UID 列表
     * @return userId → OnlineStatusData? 的映射，不存在的 key 映射为 null
     */
    suspend fun batchGetStatus(userIds: List<Long>): Map<Long, OnlineStatusData?> {
        if (userIds.isEmpty()) return emptyMap()
        val keys: kotlin.Array<String> = userIds.map { "$KEY_PREFIX$it" }.toTypedArray()
        // mget 返回 Flow<KeyValue>，collect 后转为 List
        val mgetResult = redis.mget(*keys).toList(mutableListOf())

        val result = mutableMapOf<Long, OnlineStatusData?>()
        for (i in userIds.indices) {
            if (i >= mgetResult.size) break
            val kv = mgetResult[i]
            val data = if (kv.hasValue()) {
                try {
                    json.decodeFromString<OnlineStatusData>(kv.value)
                } catch (e: Exception) {
                    null
                }
            } else null
            result[userIds[i]] = data
        }
        return result
    }
}
