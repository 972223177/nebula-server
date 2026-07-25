package com.nebula.gateway.session

import com.nebula.common.circuit.SimpleCircuitBreaker
import com.nebula.common.redis.RedisKeys
import com.nebula.common.session.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Duration.Companion.milliseconds

/**
 * 驱逐原因 — 区分「主动登出」与「同设备互踢」，决定 eviction 回调是否向被驱逐连接推送 DISCONNECT（AUTH-06）。
 *
 * - [KICK]: 同设备互踢（新登录驱逐旧连接），被驱逐的是**其他连接**，需推送 DISCONNECT 通知其被踢下线
 * - [LOGOUT]: 主动登出（user/logout），被驱逐的就是**发起登出的连接自身**，无需推送 DISCONNECT（客户端已主动退出）
 */
enum class EvictionReason {
    KICK,
    LOGOUT
}

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
 * Redis 调用由 [redisCircuitBreaker] 保护：连续 5 次失败熔断 10s，期间快速失败。
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
    private val evictionCallbacks = CopyOnWriteArrayList<(String, EvictionReason) -> Unit>()

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
    fun onEviction(callback: (token: String, reason: EvictionReason) -> Unit) {
        evictionCallbacks.add(callback)
    }

    // ==================== 细粒度 L1 操作方法 ====================

    /**
     * 写入 Session 到 L1 本地缓存，并更新 userIdIndex。
     *
     * [register] / [validate] 内部调用此方法写入 L1。
     * 外部调用方（含测试）应优先使用 [register] / [registerWithDeviceType]，
     * 它们保证 L1+L2 一致性。
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
     * **与 [unregister] 的区别**：
     * - [removeFromLocalCache] = 仅清 L1，保留 Redis L2。——用于**断连清理**，
     *   客户端可用同一 token 重连，AuthInterceptor 从 Redis 恢复 Session。
     * - [unregister] = L1 + L2 + 回调 + 设备类型映射全清。——用于**主动下线**
     *   （登出、同设备互踢），token 彻底失效。
     *
     * ⚠️ 此方法的外部调用者（ChatService.cleanupConnection）是有意选它的。
     * 新增使用前请确认你的语义是"断连重连"还是"主动下线"。
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
     * ⚠️ 此方法目前仅 [register] 内部调用。正常写入应使用 [register] / [registerWithDeviceType]，
     * 它们保证 L1+L2 一致性。
     *
     * @param session 待保存的 Session
     */
    suspend fun saveToRedis(session: Session) {
        if (!redisCircuitBreaker.allowRequest()) {
            logger.warn { "Circuit breaker open, skipping Redis save for token=${session.token}" }
            return
        }
        try {
            withTimeout(redisTimeoutMs.milliseconds) {
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
     * ⚠️ 此方法目前仅 [unregister] 内部调用。正常移除应使用 [unregister]，
     * 它保证 L1+L2+回调一致性。
     *
     * @param token 待移除的 Session Token
     */
    suspend fun removeFromRedis(token: String) {
        if (!redisCircuitBreaker.allowRequest()) {
            logger.warn { "Circuit breaker open, skipping Redis remove for token=$token" }
            return
        }
        try {
            withTimeout(redisTimeoutMs.milliseconds) {
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
            val result = withTimeout(redisTimeoutMs.milliseconds) {
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
     * 委托给 L2 Redis 的 [SessionStore.refreshTtl]，失败时仅日志记录不阻塞主流程。
     *
     * @param token Session Token
     */
    suspend fun refreshTtl(token: String) {
        if (!redisCircuitBreaker.allowRequest()) {
            return
        }
        try {
            withTimeout(redisTimeoutMs.milliseconds) {
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
     * 注册新 Session — 写入 L1 + L2（组合方法的唯一入口）。
     *
     * 这是所有 Session 注册操作的标准路径，保证 L1 和 L2 始终一致。
     * 新增任何需要写入 Session 的操作时，应先考虑委托给此方法，
     * 而非分别调用 [addToLocalCache] + [saveToRedis]。
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
     * 注销 Session — 移除 L1 + L2 + 回调 + 设备类型清理（组合方法的唯一入口）。
     *
     * 这是所有「主动下线」操作的标准路径，保证 L1/L2/deviceTypeIndex 始终一致。
     *
     * **与 [removeFromLocalCache] 的区别**：
     * - [unregister] = L1 + L2 + 回调 + 设备类型映射全清。——用于登出、同设备互踢。
     * - [removeFromLocalCache] = 仅清 L1，保留 L2。——用于断连清理，支持重连。
     *
     * @param token 待注销的 Session Token
     * @param reason 驱逐原因（[EvictionReason.KICK] 互踢需推送 DISCONNECT / [EvictionReason.LOGOUT] 主动登出仅关流），
     *   默认 [EvictionReason.KICK]，保证既有互踢调用方无需改动
     */
    suspend fun unregister(token: String, reason: EvictionReason = EvictionReason.KICK) {
        val session = removeFromLocalCache(token)
        removeFromRedis(token)
        evictionCallbacks.forEach { it(token, reason) }
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
     * 仅查询同 userId+deviceType 当前映射的 token，不触发任何驱逐/回调。
     *
     * 供 SessionBinder.bind 判断"同一条流重登录（自踢）"：配合调用方持有的 tokenToObserver，
     * 若查到的旧 token 映射到与当前登录【相同】的 StreamObserver 实例，则判定为自踢，应走
     * [replaceSessionSilently] 而非 [registerWithDeviceType]。
     *
     * 只读查询，绝不修改 deviceTypeIndex / L1 / L2，也绝不触碰 eviction 回调（AUTH-07 自踢防护）。
     *
     * @param userId 用户 ID
     * @param deviceType 设备类型字符串
     * @return 当前映射的 token，无则 null
     */
    fun peekDeviceTypeToken(userId: Long, deviceType: String): String? =
        deviceTypeIndex[deviceTypeKey(userId, deviceType)]

    /**
     * 同连接重登录（自踢）场景：用新 Session 静默替换旧 token 映射，不推送 DISCONNECT（AUTH-07）。
     *
     * 与 [registerWithDeviceType] 的区别：
     * 当被驱逐的旧 token 映射到的连接与当前登录连接是【同一 gRPC 流实例】时，若仍走 [unregister]
     * 会向"当前连接自身"推送 DISCONNECT，导致客户端误判被踢而重连（关联 AUTH-05 互踢 / AUTH-06 登出不推送 DISCONNECT）。
     * 本方法仅清 L1/L2/设备类型映射并重写新映射，跳过 eviction 回调（不推 DISCONNECT）。
     *
     * ⚠️ 调用方（SessionBinder.bind）必须先用 [peekDeviceTypeToken] + tokenToObserver 引用相等
     * 确认是同一连接，方可调用本方法；跨连接重登录仍应走 [registerWithDeviceType] 触发正常互踢。
     * 本方法不持有任何 StreamObserver 引用，仅操作 Session 数据，无内存泄漏风险。
     *
     * @param session 新 Session
     * @return 被静默替换的旧 token（供调用方清理 tokenToObserver 旧条目，防止 stale 映射泄漏），无旧映射则 null
     */
    suspend fun replaceSessionSilently(session: Session): String? {
        val key = deviceTypeKey(session.userId, session.deviceType)
        val existingToken = deviceTypeIndex[key]
        if (existingToken != null) {
            // 仅清 L1+L2+设备类型映射，跳过 eviction 回调（不推 DISCONNECT）
            val oldSession = removeFromLocalCache(existingToken)
            removeFromRedis(existingToken)
            if (oldSession != null) deleteDeviceTypeMapping(oldSession)
        }
        // 写新映射（L1 + L2 token key）——无论是否有旧映射都写，保证新 Session 生效
        register(session)
        // 写设备类型交叉引用
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
            withTimeout(redisTimeoutMs.milliseconds) {
                sessionStore.saveRaw(
                    RedisKeys.deviceTypeMappingKey(session.userId, session.deviceType),
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
            withTimeout(redisTimeoutMs.milliseconds) {
                sessionStore.deleteKey(RedisKeys.deviceTypeMappingKey(session.userId, session.deviceType))
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
            withTimeout(redisTimeoutMs.milliseconds) {
                // 复用 findDeviceTokenFromRedis 读取当前映射值，避免重复 findRaw 内联
                val currentValue = findDeviceTokenFromRedis(userId, deviceType)
                // CQ-12: 仅当 Redis 中的值仍为旧 token 时才删除，防止误删新连接的映射
                if (currentValue == expectedToken) {
                    sessionStore.deleteKey(RedisKeys.deviceTypeMappingKey(userId, deviceType))
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
            withTimeout(redisTimeoutMs.milliseconds) {
                sessionStore.findRaw(RedisKeys.deviceTypeMappingKey(userId, deviceType))
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(e) { "Device type mapping query timeout for userId=$userId" }
            null
        } catch (e: Exception) {
            logger.error(e) { "Device type mapping query failed for userId=$userId" }
            null
        }
    }

    /**
     * 重启后从 Redis 恢复设备类型映射索引（D-05, AUTH-05）。
     *
     * 服务重启后内存 [deviceTypeIndex] 为空。若 Redis 中仍存在设备类型映射
     * （key 格式 `session:{userId}:{deviceType}` → token，由 [saveDeviceTypeMapping] 写入），
     * 则重建本地索引，确保同类型设备互踢在重启后仍可工作。
     *
     * 通过 [com.nebula.common.session.SessionStore.scanKeys] 增量扫描 `session:*` 键，
     * 过滤出设备类型映射键（共 3 段、第 1 段为 "session"、第 2 段可解析为数字 userId；
     * 排除 `session:token:{token}` 这类 token 键），逐条读取 token 并写回 [deviceTypeIndex]。
     *
     * 注意：Redis 不可用或扫描失败时记录错误并返回 0，不阻塞启动（互踢将短暂失效直至重新登录）。
     *
     * @return 成功恢复的映射条目数
     */
    suspend fun recoverDeviceTypeIndex(): Int {
        return try {
            val keys = sessionStore.scanKeys(RedisKeys.DEVICE_TYPE_SCAN_PATTERN)
            var recovered = 0
            for (key in keys) {
                val parts = key.split(":")
                // 设备类型键: session:{userId}:{deviceType}（3 段，第 2 段为数字 userId）
                // token 键:   session:token:{token}（第 2 段为 "token"），需排除
                if (parts.size == 3 && parts[0] == "session") {
                    val userId = parts[1].toLongOrNull() ?: continue
                    val deviceType = parts[2]
                    val token = findDeviceTokenFromRedis(userId, deviceType) ?: continue
                    deviceTypeIndex[deviceTypeKey(userId, deviceType)] = token
                    recovered++
                }
            }
            if (recovered > 0) {
                logger.info { "设备类型映射索引恢复完成，共恢复 $recovered 条" }
            }
            recovered
        } catch (e: Exception) {
            logger.error(e) { "设备类型映射索引恢复失败，互踢将在重启后短暂失效直至重新登录" }
            0
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
