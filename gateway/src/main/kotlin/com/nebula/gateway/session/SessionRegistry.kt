package com.nebula.gateway.session

import kotlinx.serialization.json.Json
import com.nebula.repository.redis.SessionRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Session 注册中心 — L1(ConcurrentHashMap) + L2(Redis SessionRepository) 二级缓存（D-18）。
 *
 * 职责：
 * - L1 本地缓存（ConcurrentHashMap）提供毫秒级内存读取，用于高频认证场景
 * - L2 Redis 缓存（SessionRepository）提供跨节点持久化存储
 * - 提供组合方法（validate/register/unregister）和细粒度方法双入口
 * - 缓存驱逐回调注册点，用于连接清理时关闭 StreamObserver（D-20）
 *
 * L2 Redis 调用使用 500ms 超时保护，Redis 不可用时降级为纯 L1 缓存（Review 反馈#6）。
 * 已登录用户不受影响（Session 在 L1 中），新登录用户在 Redis 恢复前无法完成跨节点认证。
 * TODO: Phase 11 添加熔断器（如 Resilience4j）保护 Redis 调用。
 *
 * @param sessionRepository Redis Session 操作接口
 */
class SessionRegistry(
    private val sessionRepository: SessionRepository
) {
    /** L1 本地缓存 — token → Session 映射 */
    private val localCache = ConcurrentHashMap<String, Session>()

    /** userId → token 集合索引，用于多设备管理（D-18） */
    private val userIdIndex = ConcurrentHashMap<Long, MutableSet<String>>()

    /** 缓存驱逐回调列表 — 当 Session 被驱逐时通知关闭 StreamObserver（D-20） */
    private val evictionCallbacks = CopyOnWriteArrayList<(String) -> Unit>()

    /** Json 实例用于 Session 与 JSON 互转，存储到 Redis */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** L2 Redis 调用超时时间（毫秒）*/
    private val redisTimeoutMs = 500L

    /**
     * 注册缓存驱逐回调。
     *
     * 当 Session 被 unregister() 移除时，所有注册的回调会被依次调用。
     * 典型用途：ChatGatewayImpl 注册回调以关闭对应连接的 StreamObserver。
     *
     * @param callback 接收 token 参数的回调函数
     */
    fun onEviction(callback: (token: String) -> Unit) {
        evictionCallbacks.add(callback)
    }

    // ==================== 细粒度 L1 操作方法 ====================

    /**
     * 写入 Session 到 L1 本地缓存，并更新 userIdIndex。
     *
     * @param session 待写入的 Session
     */
    fun addToLocalCache(session: Session) {
        localCache[session.token] = session
        userIdIndex.compute(session.userId) { _, existingTokens ->
            val tokens = existingTokens ?: ConcurrentHashMap.newKeySet()
            tokens.add(session.token)
            tokens
        }
    }

    /**
     * 从 L1 本地缓存移除 Session，并更新 userIdIndex。
     *
     * @param token 待移除的 Session Token
     * @return 被移除的 Session，若不存在则返回 null
     */
    fun removeFromLocalCache(token: String): Session? {
        val session = localCache.remove(token)
        if (session != null) {
            userIdIndex.computeIfPresent(session.userId) { _, tokens ->
                tokens.remove(token)
                if (tokens.isEmpty()) null else tokens
            }
        }
        return session
    }

    /**
     * 从 L1 本地缓存获取 Session。
     *
     * @param token Session Token
     * @return 缓存的 Session，若不存在则返回 null
     */
    fun getFromLocalCache(token: String): Session? = localCache[token]

    // ==================== 细粒度 L2 Redis 操作方法 ====================

    /**
     * 保存 Session 到 Redis（L2）。
     *
     * 将 Session 序列化为 JSON 字符串后，通过 SessionRepository.save() 写入 Redis。
     * 使用 500ms 超时保护，超时仅日志记录不阻塞注册流程。
     *
     * @param session 待保存的 Session
     */
    suspend fun saveToRedis(session: Session) {
        try {
            withTimeout(redisTimeoutMs) {
                val sessionJson = json.encodeToString(session)
                sessionRepository.save(session.token, sessionJson)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis save timeout for token=${session.token}, degraded to L1 only" }
        } catch (e: Exception) {
            logger.error(e) { "Redis save failed for token=${session.token}, degraded to L1 only" }
        }
    }

    /**
     * 从 Redis（L2）移除 Session。
     *
     * @param token 待移除的 Session Token
     */
    suspend fun removeFromRedis(token: String) {
        try {
            withTimeout(redisTimeoutMs) {
                sessionRepository.delete(token)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis remove timeout for token=$token" }
        } catch (e: Exception) {
            logger.error(e) { "Redis remove failed for token=$token" }
        }
    }

    /**
     * 从 Redis（L2）查询 Session。
     *
     * 通过 SessionRepository.findByToken() 查询，反序列化 JSON 字符串为 Session。
     * 使用 500ms 超时保护，超时返回 null 不阻塞调用方。
     *
     * @param token Session Token
     * @return 反序列化的 Session，若不存在或查询失败则返回 null
     */
    suspend fun queryFromRedis(token: String): Session? {
        return try {
            withTimeout(redisTimeoutMs) {
                val sessionJson = sessionRepository.findByToken(token)
                if (sessionJson != null) {
                    json.decodeFromString<Session>(sessionJson)
                } else null
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Redis query timeout for token=$token, degraded to L1 only" }
            null
        } catch (e: Exception) {
            logger.error(e) { "Redis query failed for token=$token" }
            null
        }
    }

    // ==================== 组合方法 ====================

    /**
     * 验证 Session Token 并返回 Session。
     *
     * 查询顺序：L1 本地缓存 → L2 Redis。
     * L1 命中直接返回；L2 查询到结果后写入 L1 再返回。
     * L2 超时或失败时返回 null。
     *
     * @param token Session Token
     * @return 有效的 Session，若不存在或查询失败则返回 null
     */
    suspend fun validate(token: String): Session? {
        return getFromLocalCache(token) ?: queryFromRedis(token)?.also {
            addToLocalCache(it)
        }
    }

    /**
     * 注册新 Session — 写入 L1 本地缓存 + L2 Redis。
     *
     * @param session 待注册的 Session
     */
    suspend fun register(session: Session) {
        addToLocalCache(session)
        saveToRedis(session)
    }

    /**
     * 注销 Session — 移除 L1 本地缓存 + L2 Redis + 触发所有驱逐回调。
     *
     * @param token 待注销的 Session Token
     */
    suspend fun unregister(token: String) {
        removeFromLocalCache(token)
        removeFromRedis(token)
        evictionCallbacks.forEach { it(token) }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
