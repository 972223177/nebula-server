package com.nebula.repository.redis

import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/**
 * 用户隐私设置缓存操作（D-09, D-11）。
 *
 * Redis key 格式: "privacy:user:{userId}"
 * 存储内容: JSON 格式 PrivacyData，TTL 7 天
 *
 * MySQL 异步刷写策略（best-effort 模式）：
 * setHideOnlineStatus() 先写 Redis（立即生效），再异步刷 MySQL。
 * 服务器 crash 在 Redis 写完后、MySQL 刷完前，最后一次隐私设置丢失。
 * 重启后首次 getHideOnlineStatus 从 MySQL 读取，可恢复持久化状态。
 * 此权衡已被接受。
 *
 * @param connection 共享 Redis 连接实例
 * @param userRepository MySQL 用户数据仓库，用于回退读取和异步刷写
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class PrivacyRepository(
    private val connection: StatefulRedisConnection<String, String>,
    private val userRepository: UserRepository
) {
    private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    companion object {
        /** Redis key 前缀 */
        private const val KEY_PREFIX = "privacy:user:"
        /** 缓存 TTL：7 天 */
        private const val TTL_SECONDS = 7 * 24 * 3600L  // 7 天
        /** Redis 操作超时时间（毫秒） */
        private const val REDIS_TIMEOUT_MS = 500L

        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}

        /** 在线状态可见性：0=所有人可见, 2=隐藏 */
        private const val PRIVACY_VISIBLE = 0
        private const val PRIVACY_HIDDEN = 2
    }

    /**
     * 隐私设置数据模型。
     *
     * @param hideOnlineStatus 是否隐藏在线状态
     */
    @Serializable
    data class PrivacyData(val hideOnlineStatus: Boolean)

    /**
     * 获取用户在线状态可见性设置。
     *
     * 查询顺序：Redis → MySQL（回退）。
     * Redis 命中时反序列化 PrivacyData 并返回 hideOnlineStatus。
     * Redis 未命中时从 MySQL UserRepository 读取 privacyStatus，
     * 写回 Redis 后返回。
     *
     * @param userId 用户 ID
     * @return true=隐藏在线状态，false=在线状态可见
     */
    suspend fun getHideOnlineStatus(userId: Long): Boolean {
        return try {
            withTimeout(REDIS_TIMEOUT_MS) {
                val cached = redis.get("$KEY_PREFIX$userId")
                if (cached != null) {
                    val data = json.decodeFromString<PrivacyData>(cached)
                    return@withTimeout data.hideOnlineStatus
                }
            }
            // Redis 未命中，从 MySQL 回退读取
            val entity = withContext(Dispatchers.IO) { userRepository.findById(userId) }
            if (entity.isPresent) {
                val hide = entity.get().privacyStatus == PRIVACY_HIDDEN
                // 写回 Redis
                withTimeout(REDIS_TIMEOUT_MS) {
                    redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, json.encodeToString(PrivacyData(hide)))
                }
                return hide
            }
            false // 用户不存在，默认可见
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis getHideOnlineStatus timeout for userId=$userId, falling back to MySQL" }
            // 超时后从 MySQL 回退
            val entity = withContext(Dispatchers.IO) { userRepository.findById(userId) }
            return if (entity.isPresent) entity.get().privacyStatus == PRIVACY_HIDDEN else false
        } catch (e: Exception) {
            logger.error(e) { "Redis getHideOnlineStatus failed for userId=$userId" }
            false
        }
    }

    /**
     * 设置用户在线状态可见性。
     *
     * 立即写 Redis（实时生效），异步刷 MySQL（best-effort）。
     *
     * @param userId 用户 ID
     * @param hide true=隐藏在线状态，false=在线状态可见
     */
    suspend fun setHideOnlineStatus(userId: Long, hide: Boolean) {
        try {
            withTimeout(REDIS_TIMEOUT_MS) {
                redis.setex("$KEY_PREFIX$userId", TTL_SECONDS, json.encodeToString(PrivacyData(hide)))
            }
            // Redis 写成功后，异步刷 MySQL（best-effort 模式）
            withContext(Dispatchers.IO) {
                try {
                    val entity = userRepository.findById(userId)
                    if (entity.isPresent) {
                        val user = entity.get()
                        user.privacyStatus = if (hide) PRIVACY_HIDDEN else PRIVACY_VISIBLE
                        userRepository.save(user)
                    }
                } catch (e: Exception) {
                    logger.error(e) { "Async MySQL privacy update failed for userId=$userId" }
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis setHideOnlineStatus timeout for userId=$userId" }
        } catch (e: Exception) {
            logger.error(e) { "Redis setHideOnlineStatus failed for userId=$userId" }
        }
    }

    /**
     * 批量查询用户的在线状态可见性 — 使用 Redis MGET 避免 N+1 查询。
     *
     * 构造 "privacy:user:$userId" key 列表，调用 redis.mget 批量查询。
     * 遍历结果反序列化，收集 hideOnlineStatus=true 的 userId 集合。
     *
     * @param userIds 待查询的用户 ID 列表
     * @return hideOnlineStatus=true 的 userId 集合
     */
    suspend fun batchGetHideOnlineStatus(userIds: List<Long>): Set<Long> {
        if (userIds.isEmpty()) return emptySet()

        return try {
            withTimeout(REDIS_TIMEOUT_MS) {
                val keys: kotlin.Array<String> = userIds.map { "$KEY_PREFIX$it" }.toTypedArray()
                @Suppress("UNCHECKED_CAST")
                val mgetResult = redis.mget(*keys) as List<io.lettuce.core.KeyValue<String, String>>

                val hiddenUsers = mutableSetOf<Long>()
                for (i in userIds.indices) {
                    if (i >= mgetResult.size) break
                    val kv = mgetResult[i]
                    if (kv.hasValue()) {
                        val valueStr = kv.value
                        try {
                            val data = json.decodeFromString<PrivacyData>(valueStr)
                            if (data.hideOnlineStatus) {
                                hiddenUsers.add(userIds[i])
                            }
                        } catch (e: Exception) {
                            logger.warn(e) { "Failed to parse privacy data for userId=${userIds[i]}" }
                        }
                    }
                }
                hiddenUsers
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis batchGetHideOnlineStatus timeout" }
            emptySet()
        } catch (e: Exception) {
            logger.error(e) { "Redis batchGetHideOnlineStatus failed" }
            emptySet()
        }
    }
}
