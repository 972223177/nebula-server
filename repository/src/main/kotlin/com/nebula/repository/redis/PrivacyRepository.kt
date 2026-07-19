package com.nebula.repository.redis

import com.nebula.common.redis.RedisKeys
import com.nebula.common.redis.RedisTtl
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.dao.UserDao
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
 * @param userDao MySQL 用户数据访问对象，用于回退读取和异步刷写
 * @param txRunner JPA 事务运行器，承载 MySQL 读写事务
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class PrivacyRepository(
    private val connection: StatefulRedisConnection<String, String>,
    private val userDao: UserDao,
    private val txRunner: JpaTxRunner
) {
    private val redis: RedisCoroutinesCommands<String, String> = RedisCoroutinesCommandsImpl(connection.reactive())

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    companion object {
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
     * @param friendApprovalMode 好友申请通过模式：0=等待同意, 1=自动通过, 2=自动拒绝
     */
    @Serializable
    data class PrivacyData(
        val hideOnlineStatus: Boolean = false,
        val friendApprovalMode: Int = 0
    )

    /**
     * 获取用户在线状态可见性设置。
     *
     * 查询顺序：Redis → MySQL（回退）。
     * Redis 命中时反序列化 PrivacyData 并返回 hideOnlineStatus。
     * Redis 未命中时从 MySQL UserDao 读取 privacyStatus，
     * 写回 Redis 后返回。
     *
     * @param userId 用户 ID
     * @return true=隐藏在线状态，false=在线状态可见
     */
    suspend fun getHideOnlineStatus(userId: Long): Boolean {
        return try {
            withTimeout(RedisTtl.TIMEOUT_MS) {
                val cached = redis.get("${RedisKeys.privacyKey(userId)}")
                if (cached != null) {
                    val data = json.decodeFromString<PrivacyData>(cached)
                    return@withTimeout data.hideOnlineStatus
                }
            }
            // Redis 未命中，从 MySQL 回退读取
            val entity = txRunner.execute { em -> userDao.findById(em, userId) }
            if (entity != null) {
                val hide = entity.privacyStatus == PRIVACY_HIDDEN
                // 写回 Redis
                withTimeout(RedisTtl.TIMEOUT_MS) {
                    redis.setex("${RedisKeys.privacyKey(userId)}", RedisTtl.SEVEN_DAYS, json.encodeToString(PrivacyData(hide)))
                }
                return hide
            }
            false // 用户不存在，默认可见
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis getHideOnlineStatus timeout for userId=$userId, falling back to MySQL" }
            // 超时后从 MySQL 回退
            val entity = txRunner.execute { em -> userDao.findById(em, userId) }
            return if (entity != null) entity.privacyStatus == PRIVACY_HIDDEN else false
        } catch (e: Exception) {
            logger.error(e) { "Redis getHideOnlineStatus failed for userId=$userId" }
            false
        }
    }

    /**
     * 设置用户在线状态可见性。
     *
     * 读-改-写 Redis（保留 friendApprovalMode），异步刷 MySQL（best-effort）。
     *
     * @param userId 用户 ID
     * @param hide true=隐藏在线状态，false=在线状态可见
     */
    suspend fun setHideOnlineStatus(userId: Long, hide: Boolean) {
        try {
            withTimeout(RedisTtl.TIMEOUT_MS) {
                val existing = readPrivacyData(userId)
                redis.setex("${RedisKeys.privacyKey(userId)}", RedisTtl.SEVEN_DAYS,
                    json.encodeToString(existing.copy(hideOnlineStatus = hide)))
            }
            // Redis 写成功后，异步刷 MySQL（best-effort 模式）
            try {
                txRunner.execute { em ->
                    val entity = userDao.findById(em, userId) ?: return@execute
                    entity.privacyStatus = if (hide) PRIVACY_HIDDEN else PRIVACY_VISIBLE
                    userDao.update(em, entity)
                }
            } catch (e: Exception) {
                logger.error(e) { "Async MySQL privacy update failed for userId=$userId" }
                throw e  // M26: 重新抛出异常，而非静默吞掉，确保调用方可感知失败
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis setHideOnlineStatus timeout for userId=$userId" }
        } catch (e: Exception) {
            logger.error(e) { "Redis setHideOnlineStatus failed for userId=$userId" }
        }
    }

    /**
     * 获取用户好友申请通过模式。
     *
     * 查询顺序：Redis → MySQL（回退）。
     * 当用户不存在时返回默认值 0（等待同意）。
     *
     * @param userId 用户 ID
     * @return 好友申请通过模式：0=等待同意, 1=自动通过, 2=自动拒绝
     */
    suspend fun getFriendApprovalMode(userId: Long): Int {
        return try {
            withTimeout(RedisTtl.TIMEOUT_MS) {
                val cached = redis.get("${RedisKeys.privacyKey(userId)}")
                if (cached != null) {
                    val data = json.decodeFromString<PrivacyData>(cached)
                    return@withTimeout data.friendApprovalMode
                }
            }
            // Redis 未命中，从 MySQL 回退读取
            val entity = txRunner.execute { em -> userDao.findById(em, userId) }
            if (entity != null) {
                val mode = entity.friendApproval
                // 写回 Redis（保留现有 hideOnlineStatus，若有）
                withTimeout(RedisTtl.TIMEOUT_MS) {
                    val existing = readPrivacyData(userId)
                    redis.setex("${RedisKeys.privacyKey(userId)}", RedisTtl.SEVEN_DAYS,
                        json.encodeToString(existing.copy(friendApprovalMode = mode)))
                }
                return mode
            }
            0 // 用户不存在，默认等待同意
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis getFriendApprovalMode timeout for userId=$userId, falling back to MySQL" }
            val entity = txRunner.execute { em -> userDao.findById(em, userId) }
            return entity?.friendApproval ?: 0
        } catch (e: Exception) {
            logger.error(e) { "Redis getFriendApprovalMode failed for userId=$userId" }
            0
        }
    }

    /**
     * 设置用户好友申请通过模式。
     *
     * 读-改-写 Redis（保留 hideOnlineStatus），异步刷 MySQL（best-effort）。
     *
     * @param userId 用户 ID
     * @param mode 好友申请通过模式：0=等待同意, 1=自动通过, 2=自动拒绝
     */
    suspend fun setFriendApprovalMode(userId: Long, mode: Int) {
        try {
            withTimeout(RedisTtl.TIMEOUT_MS) {
                val existing = readPrivacyData(userId)
                redis.setex("${RedisKeys.privacyKey(userId)}", RedisTtl.SEVEN_DAYS,
                    json.encodeToString(existing.copy(friendApprovalMode = mode)))
            }
            // Redis 写成功后，异步刷 MySQL（best-effort 模式）
            try {
                txRunner.execute { em ->
                    val entity = userDao.findById(em, userId) ?: return@execute
                    entity.friendApproval = mode
                    userDao.update(em, entity)
                }
            } catch (e: Exception) {
                logger.error(e) { "Async MySQL friendApproval update failed for userId=$userId" }
                throw e
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis setFriendApprovalMode timeout for userId=$userId" }
        } catch (e: Exception) {
            logger.error(e) { "Redis setFriendApprovalMode failed for userId=$userId" }
        }
    }

    /**
     * 从 Redis 读取当前隐私设置数据，未命中时返回默认值。
     *
     * @param userId 用户 ID
     * @return 当前隐私设置数据
     */
    private suspend fun readPrivacyData(userId: Long): PrivacyData {
        return try {
            withTimeout(RedisTtl.TIMEOUT_MS) {
                val cached = redis.get("${RedisKeys.privacyKey(userId)}")
                if (cached != null) {
                    json.decodeFromString<PrivacyData>(cached)
                } else PrivacyData()
            }
        } catch (e: Exception) {
            PrivacyData()
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
            withTimeout(RedisTtl.TIMEOUT_MS) {
                val keys: kotlin.Array<String> = userIds.map { RedisKeys.privacyKey(it) }.toTypedArray()
                // Lettuce 协程 mget 返回 Flow，collect 为 List 后按索引取用；避免未检查转换与运行时恒为 null
                val mgetResult = redis.mget(*keys).toList()

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
