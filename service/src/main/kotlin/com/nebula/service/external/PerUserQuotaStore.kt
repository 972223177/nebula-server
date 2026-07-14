package com.nebula.service.external

import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl

/**
 * 每用户配额防御子限存储（D-XX）—— 基于 Redis 计数器，保护全局共享池不被单用户烧光（§3.1/§7.1）。
 *
 * 计数维度：userId + QuotaCategory + 周期（weather 按 date、search 按 month）。
 * Redis key 形如 `ext:pu:<category>:<userId>:<periodKey>`（periodKey = 今日日期 / 本月），
 * 用 EXPIRE 设为对应周期剩余秒数实现自动过期重置（无需手动比对边界，规避文件方案的跨周期风险）。
 * 线程安全：走 Lettuce 协程 API——内部通过 `RedisCoroutinesCommandsImpl(connection.reactive())` 获取协程命令
 * （与 SendMessageHandler / RedisDeliveryTracker 同范式），`incr` / `expire` 均为 `suspend` 方法。
 *
 * @param connection 项目既有 Redis 连接（StatefulRedisConnection），由 Koin 注入，与 QuotaManager 共用
 */
@ExperimentalLettuceCoroutinesApi
class PerUserQuotaStore(
    private val connection: StatefulRedisConnection<String, String>
) {
    private val log = KotlinLogging.logger {}
    private val redis = RedisCoroutinesCommandsImpl(connection.reactive())

    /**
     * 校验并递增每用户计数；返回 true=通过（未超防御子限 limit），false=已超子限（拒绝）。
     *
     * 采用 INCR 后判断计数是否为周期首次（==1）再 EXPIRE 的范式：周期内累计、跨周期自动归零。
     * 注意 INCR 与 EXPIRE 并非原子（若需严格原子可在 Lua 脚本中完成），但配合 EXPIRE 周期重置
     * 足以保证不跨周期累积，且首次 EXPIRE 失败也不影响计数正确性（仅丢失自动过期，可由兜底逻辑回收）。
     *
     * @param userId 用户 ID
     * @param category 配额类别
     * @param limit 防御子限（weather-per-user-daily-limit / search-per-user-monthly-limit）
     * @param periodKey 周期键（今日日期 / 本月）
     * @return true=未超子限，false=已超（应拒绝该用户）
     */
    suspend fun tryConsume(userId: Long, category: QuotaCategory, limit: Int, periodKey: String): Boolean {
        val key = "ext:pu:${category.name.lowercase()}:$userId:$periodKey"
        return try {
            val count = redis.incr(key) ?: 0L
            if (count == 1L) {
                redis.expire(key, quotaRemainingSeconds(category))
            }
            count <= limit
        } catch (e: Exception) {
            // Redis 不可用时防御子限降级为放行（避免故障态误杀正常用户），由全局共享池兜底
            log.warn { "每用户配额计数失败，放行: ${e.message}" }
            true
        }
    }

    /** 获取某用户当前周期已用计数（用于日志/调试） */
    suspend fun used(userId: Long, category: QuotaCategory, periodKey: String): Long {
        val key = "ext:pu:${category.name.lowercase()}:$userId:$periodKey"
        return try {
            redis.get(key)?.toLongOrNull() ?: 0
        } catch (e: Exception) {
            0
        }
    }
}
