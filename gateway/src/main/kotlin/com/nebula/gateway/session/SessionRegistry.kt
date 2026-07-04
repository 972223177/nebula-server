package com.nebula.gateway.session

import kotlinx.serialization.json.Json
import com.nebula.common.circuit.SimpleCircuitBreaker
import com.nebula.common.session.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Session 注册中心 — L1(ConcurrentHashMap) + L2(SessionStore) 二级缓存（D-18）。
 *
 * 职责：
 * - L1 本地缓存（ConcurrentHashMap）提供毫秒级内存读取，用于高频认证场景
 * - L2 缓存（SessionStore）提供跨节点持久化存储
 * - 提供组合方法（validate/register/unregister）和细粒度方法双入口
 * - 缓存驱逐回调注册点，用于连接清理时关闭 StreamObserver（D-20）
 *
 * L2 调用使用 500ms 超时保护，后端不可用时降级为纯 L1 缓存（Review 反馈#6）。
 * 已登录用户不受影响（Session 在 L1 中），新登录用户在后端恢复前无法完成跨节点认证。
 * TODO: Phase 11 添加熔断器（如 Resilience4j）保护后端调用。
 *
 * @param sessionStore Session 持久化存储接口
 */
class SessionRegistry(
    private val sessionStore: SessionStore
) {
    /** L1 本地缓存 — token → Session 映射 */
    private val localCache = ConcurrentHashMap<String, Session>()

    /** userId → token 集合索引，用于多设备管理（D-18） */
    private val userIdIndex = ConcurrentHashMap<Long, MutableSet<String>>()

    /** 设备类型索引 — userId:deviceType → token，用于同类型设备互踢（D-05, AUTH-05） */
    private val deviceTypeIndex = ConcurrentHashMap<String, String>()

    /** 缓存驱逐回调列表 — 当 Session 被驱逐时通知关闭 StreamObserver（D-20） */
    private val evictionCallbacks = CopyOnWriteArrayList<(String) -> Unit>()

    /** Json 实例用于 Session 与 JSON 互转，存储到 Redis */
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    /** L2 Redis 调用超时时间（毫秒）*/
    private val redisTimeoutMs = 500L

    /**
     * R-03: 负缓存 — token → 过期时间戳，防止不存在的 token 反复穿透到 Redis。
     *
     * 当 Redis 查询返回 null 时，将 token 写入负缓存，有效期内直接返回 null 不查 Redis。
     * 注册新 Session 时清除对应负缓存条目。
     */
    private val negativeCache = ConcurrentHashMap<String, Long>()

    /** R-03: 负缓存 TTL（毫秒），默认 60s */
    private val negativeCacheTtlMs = 60_000L

    /**
     * C-07: Redis 调用熔断器 — 连续失败 5 次后熔断 10s，期间快速失败不查 Redis。
     *
     * 熔断期间已登录用户不受影响（L1 缓存命中），新登录/跨节点认证降级。
     */
    private val redisCircuitBreaker = SimpleCircuitBreaker()

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
            // 同时清理设备类型索引（D-05）
            val deviceKey = deviceTypeKey(session.userId, session.deviceType)
            deviceTypeIndex.remove(deviceKey, token)
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
     * 将 Session 序列化为 JSON 字符串后，通过 SessionStore.save() 写入。
     * 使用 500ms 超时保护，超时仅日志记录不阻塞注册流程。
     *
     * @param session 待保存的 Session
     */
    suspend fun saveToRedis(session: Session) {
        if (!redisCircuitBreaker.allowRequest()) {
            logger.warn { "Circuit breaker open, skipping Redis save for token=${session.token}" }
            return
        }
        try {
            withTimeout(redisTimeoutMs) {
                val sessionJson = json.encodeToString(session)
                sessionStore.save(session.token, sessionJson)
            }
            redisCircuitBreaker.recordSuccess()
        } catch (e: TimeoutCancellationException) {
            redisCircuitBreaker.recordFailure()
            logger.warn(e) { "Redis save timeout for token=${session.token}, degraded to L1 only" }
        } catch (e: Exception) {
            redisCircuitBreaker.recordFailure()
            logger.error(e) { "Redis save failed for token=${session.token}, degraded to L1 only" }
        }
    }

    /**
     * 从 Redis（L2）移除 Session。
     *
     * @param token 待移除的 Session Token
     */
    suspend fun removeFromRedis(token: String) {
        if (!redisCircuitBreaker.allowRequest()) {
            logger.warn { "Circuit breaker open, skipping Redis remove for token=$token" }
            return
        }
        try {
            withTimeout(redisTimeoutMs) {
                sessionStore.delete(token)
            }
            redisCircuitBreaker.recordSuccess()
        } catch (e: TimeoutCancellationException) {
            redisCircuitBreaker.recordFailure()
            logger.warn(e) { "Redis remove timeout for token=$token" }
        } catch (e: Exception) {
            redisCircuitBreaker.recordFailure()
            logger.error(e) { "Redis remove failed for token=$token" }
        }
    }

    /**
     * 从 Redis（L2）查询 Session。
     *
     * 通过 SessionStore.findByToken() 查询，反序列化 JSON 字符串为 Session。
     * 使用 500ms 超时保护，超时返回 null 不阻塞调用方。
     *
     * @param token Session Token
     * @return 反序列化的 Session，若不存在或查询失败则返回 null
     */
    suspend fun queryFromRedis(token: String): Session? {
        if (!redisCircuitBreaker.allowRequest()) {
            logger.warn { "Circuit breaker open, skipping Redis query for token=$token" }
            return null
        }
        return try {
            val result = withTimeout(redisTimeoutMs) {
                val sessionJson = sessionStore.findByToken(token)
                if (sessionJson != null) {
                    json.decodeFromString<Session>(sessionJson)
                } else null
            }
            redisCircuitBreaker.recordSuccess()
            result
        } catch (e: TimeoutCancellationException) {
            redisCircuitBreaker.recordFailure()
            logger.warn(e) { "Redis query timeout for token=$token, degraded to L1 only" }
            null
        } catch (e: Exception) {
            redisCircuitBreaker.recordFailure()
            logger.error(e) { "Redis query failed for token=$token" }
            null
        }
    }

    // ==================== 组合方法 ====================

    /**
     * 验证 Session Token 并返回 Session。
     *
     * 查询顺序：L1 本地缓存 → L1 负缓存（R-03 防穿透）→ L2 Redis。
     * L1 命中直接返回；L2 查询到结果后写入 L1 再返回；
     * L2 返回 null 时写入负缓存，有效期内不再查 Redis。
     *
     * @param token Session Token
     * @return 有效的 Session，若不存在或查询失败则返回 null
     */
    suspend fun validate(token: String): Session? {
        // L1 正缓存
        getFromLocalCache(token)?.let { return it }

        // R-03: L1 负缓存（防穿透）
        val negExpiry = negativeCache[token]
        if (negExpiry != null) {
            if (System.currentTimeMillis() < negExpiry) return null
            negativeCache.remove(token) // 已过期，清除
        }

        // L2 Redis
        val session = queryFromRedis(token)
        if (session != null) {
            addToLocalCache(session)
        } else {
            // R-03: 写入负缓存，防止同一无效 token 反复穿透
            negativeCache[token] = System.currentTimeMillis() + negativeCacheTtlMs
        }
        return session
    }

    /**
     * 刷新 Session TTL — 每次请求认证通过后调用，防止活跃用户被强制下线。
     *
     * 委托给 L2 Redis 的 [sessionStore.refreshTtl]，失败时仅日志记录不阻塞主流程。
     *
     * @param token Session Token
     */
    suspend fun refreshTtl(token: String) {
        if (!redisCircuitBreaker.allowRequest()) {
            return
        }
        try {
            withTimeout(redisTimeoutMs) {
                sessionStore.refreshTtl(token)
            }
            redisCircuitBreaker.recordSuccess()
        } catch (e: TimeoutCancellationException) {
            redisCircuitBreaker.recordFailure()
            logger.warn(e) { "Redis refreshTtl timeout for token=$token" }
        } catch (e: Exception) {
            redisCircuitBreaker.recordFailure()
            logger.error(e) { "Redis refreshTtl failed for token=$token" }
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
        // R-03: 清除负缓存（新注册的 token 可能之前被缓存为不存在）
        negativeCache.remove(session.token)
    }

    /**
     * 注销 Session — 移除 L1 本地缓存 + L2 Redis + 触发所有驱逐回调。
     *
     * @param token 待注销的 Session Token
     */
    suspend fun unregister(token: String) {
        val session = removeFromLocalCache(token)
        removeFromRedis(token)
        evictionCallbacks.forEach { it(token) }
        // 清理 Redis 设备类型映射（D-05）
        if (session != null) {
            deleteDeviceTypeMapping(session)
        }
    }

    // ==================== 设备类型管理（D-05, AUTH-05） ====================

    /**
     * 按设备类型注册 Session — 同类型设备互踢（D-05, AUTH-05）。
     *
     * 流程：
     * 1. 检查 deviceTypeIndex 是否存在同 userId+deviceType 的旧 token
     * 2. 若存在，unregister 旧 token（触发驱逐回调 → LOGOUT 推送）
     * 3. 注册新 Session（L1 + L2 token key）
     * 4. 写入设备类型交叉引用到 Redis（key: "session:{userId}:{deviceType}" → token, TTL 7天）
     * 5. 更新本地 deviceTypeIndex
     *
     * @param session 新 Session
     * @return 被驱逐的旧 Session token，若无则 null
     */
    suspend fun registerWithDeviceType(session: Session): String? {
        val key = deviceTypeKey(session.userId, session.deviceType)
        val existingToken = deviceTypeIndex[key]

        if (existingToken != null) {
            // 同设备类型的旧连接存在，触发踢下线（LOGOUT 推送在 eviction callback 中完成）
            unregister(existingToken)
        }

        // 注册新 Session（L1 + L2 token key）
        register(session)

        // 写入设备类型交叉引用到 Redis
        saveDeviceTypeMapping(session)

        // 更新本地索引
        deviceTypeIndex[key] = session.token
        return existingToken
    }

    /**
     * 生成设备类型索引 key。
     *
     * @param userId 用户 ID
     * @param deviceType 设备类型字符串
     * @return key 格式: "$userId:$deviceType"
     */
    private fun deviceTypeKey(userId: Long, deviceType: String): String = "$userId:$deviceType"

    /**
     * 将设备类型映射持久化到 Redis（D-05）。
     *
     * 服务器重启后可以从 Redis 恢复设备类型映射，确保同类型互踢在重启后仍可工作。
     * 使用 500ms 超时保护，超时仅日志记录不阻塞注册流程。
     *
     * @param session 当前注册的 Session
     */
    private suspend fun saveDeviceTypeMapping(session: Session) {
        try {
            withTimeout(redisTimeoutMs) {
                sessionStore.saveRaw(
                    "session:${session.userId}:${session.deviceType}",
                    session.token
                )
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Device type mapping save timeout for userId=${session.userId}, degraded to local only" }
        } catch (e: Exception) {
            logger.error(e) { "Device type mapping save failed for userId=${session.userId}" }
        }
    }

    /**
     * 删除 Redis 中的设备类型映射。
     *
     * @param session 被移除的 Session
     */
    private suspend fun deleteDeviceTypeMapping(session: Session) {
        try {
            withTimeout(redisTimeoutMs) {
                sessionStore.deleteKey("session:${session.userId}:${session.deviceType}")
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Device type mapping delete timeout for userId=${session.userId}" }
        } catch (e: Exception) {
            logger.error(e) { "Device type mapping delete failed for userId=${session.userId}" }
        }
    }

    /**
     * H2 修复：公开的条件清理设备类型映射方法，供 ChatService.cleanupConnection 调用。
     *
     * 连接断开时清理 Redis 中的 deviceType→token 映射，防止泄漏。
     * 注意：不清除 token 主 key（token 保留以支持断连重连）。
     *
     * **条件删除策略（CQ-12）**：先读取 Redis 当前值，仅当值仍为 expectedToken 时才删除。
     * 防止旧连接的异步清理误删新连接重连后写入的映射（竞态条件修复）。
     * 非原子操作（read-then-delete），但窗口极小且最坏影响为映射暂时丢失，可被下次登录覆盖。
     *
     * @param userId 用户 ID
     * @param deviceType 设备类型字符串
     * @param expectedToken 期望的旧 token，仅当 Redis 中映射值为该 token 时才执行删除
     */
    suspend fun cleanupDeviceTypeMapping(userId: Long, deviceType: String, expectedToken: String) {
        try {
            withTimeout(redisTimeoutMs) {
                val key = "session:$userId:$deviceType"
                val currentValue = sessionStore.findRaw(key)
                // CQ-12: 仅当 Redis 中的值仍为旧 token 时才删除，防止误删新连接的映射
                if (currentValue == expectedToken) {
                    sessionStore.deleteKey(key)
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Device type mapping cleanup timeout for userId=$userId" }
        } catch (e: Exception) {
            logger.error(e) { "Device type mapping cleanup failed for userId=$userId" }
        }
    }

    /**
     * 从 Redis 查找设备类型映射（重启后恢复用）。
     *
     * @param userId 用户 ID
     * @param deviceType 设备类型字符串
     * @return token 字符串，若不存在则返回 null
     */
    private suspend fun findDeviceTokenFromRedis(userId: Long, deviceType: String): String? {
        return try {
            withTimeout(redisTimeoutMs) {
                sessionStore.findRaw("session:$userId:$deviceType")
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Device type mapping query timeout for userId=$userId" }
            null
        } catch (e: Exception) {
            logger.error(e) { "Device type mapping query failed for userId=$userId" }
            null
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
