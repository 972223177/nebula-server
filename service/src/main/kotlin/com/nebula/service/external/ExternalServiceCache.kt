package com.nebula.service.external

import com.nebula.chat.external.SearchResponse
import com.nebula.common.external.ExternalServiceCacheConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*

/**
 * 外部服务结果缓存（D-XX）：L1 本地 LRU + L2 Redis 二级。
 *
 * 职责：
 * - **L1 本地内存 LRU（快路径）**：进程内 LinkedHashMap(accessOrder=true)，TTL 短（天气 2min/搜索 5min），
 *   承接同实例突发请求，避免每次都打 Redis；上限 max-entries，超出淘汰最久未访问。
 * - **L2 Redis（共享层）**：复用项目既有 Lettuce 连接，TTL 长（天气 30min/GeoAPI 24h/搜索 1h），
 *   跨实例共享、跨重启保留，是真正削减上游调用/配额的关键层。
 * - **读取顺序**：L1 命中即返 → L1 未命中查 L2，命中则回填 L1 → L2 未命中才回源，并双写 L1+L2（write-through）。
 * - **缓存键规范化**：由调用方负责 city/query 的 trim+小写+空白折叠后再作 key，防击穿。
 * - **配额联动**：L1/L2 任意一层命中直接返回、**不调 consume()**，复用不占额度。
 *
 * @param config   缓存配置（L1/L2 TTL、max-entries、Redis key 前缀）
 * @param connection 项目既有 Redis 连接（StatefulRedisConnection），由 Koin 注入，L2 走其协程命令
 */
@ExperimentalLettuceCoroutinesApi
class ExternalServiceCache(
    private val config: ExternalServiceCacheConfig,
    private val connection: StatefulRedisConnection<String, String>
) {
    private val log = KotlinLogging.logger {}
    private val redis = RedisCoroutinesCommandsImpl(connection.reactive())

    /** L1 条目（携带自身过期时间，避免 get 还需传入 category 判断 TTL） */
    private data class L1Entry(val value: String, val expireAt: Long)

    /** L1 本地内存 LRU（accessOrder=true 自动维护访问顺序），超出上限淘汰最久未访问 */
    private val l1 = object : LinkedHashMap<String, L1Entry>(config.l1.maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, L1Entry>): Boolean =
            size > config.l1.maxEntries
    }
    private val l1Mutex = Mutex()

    /**
     * 命中返回缓存值（L1 优先，其次 L2 并回填 L1），未命中返回 null。
     *
     * @param key 规范化后的缓存键（如 "weather:北京" / "geo:北京" / "search:xxx"）
     * @return 缓存的 formatted 文本，未命中返回 null
     */
    suspend fun get(key: String): String? {
        l1Mutex.withLock {
            val e = l1[key]
            if (e != null) {
                if (e.expireAt > nowMs()) return e.value
                l1.remove(key)
            }
        }
        return try {
            val v = redis.get(config.l2.keyPrefix + key)
            if (v != null) {
                l1Mutex.withLock { l1[key] = L1Entry(v, nowMs() + l1TtlMs(key)) }
                v
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn { "L2 读取失败: ${e.message}" }
            null
        }
    }

    /**
     * 双写 L1 + L2（L2 带 TTL）。L2 TTL 默认按 category 取配置；
     * 搜索可传 ttlOverrideMs 实现分级（稳定类长 TTL / 时效类短 TTL，§4.2），进一步省调用次数。
     *
     * @param key 规范化后的缓存键
     * @param value 缓存值（formatted 文本）
     * @param category 配额类别，决定默认 L2 TTL
     * @param ttlOverrideMs 可选 L2 TTL 覆盖（毫秒），用于搜索分级或 GeoAPI 24h
     */
    suspend fun put(key: String, value: String, category: QuotaCategory, ttlOverrideMs: Long? = null) {
        val l2TtlSeconds = when {
            ttlOverrideMs != null -> (ttlOverrideMs / 1000).toInt().coerceAtLeast(1)
            category == QuotaCategory.WEATHER -> config.l2.weatherTtlSeconds
            category == QuotaCategory.SEARCH -> config.l2.searchTtlSeconds
            else -> config.l2.weatherTtlSeconds
        }
        l1Mutex.withLock { l1[key] = L1Entry(value, nowMs() + l1TtlMs(key)) }
        try {
            val k = config.l2.keyPrefix + key
            redis.set(k, value)
            redis.expire(k, l2TtlSeconds.toLong())
        } catch (e: Exception) {
            log.warn { "L2 写入失败: ${e.message}" }
        }
    }

    private fun nowMs() = System.currentTimeMillis()

    /** L1 TTL 按 key 前缀区分：search 用搜索 L1 TTL，其余（天气/geo）用天气 L1 TTL */
    private fun l1TtlMs(key: String): Long =
        if (key.startsWith("search:")) config.l1.searchTtlSeconds * 1000L
        else config.l1.weatherTtlSeconds * 1000L

    /**
     * 搜索结果专用缓存写：将完整 [SearchResponse]（含 items 结构化字段）序列化为 Base64 后写入，
     * 避免通用 [put] 仅存 formatted 文本导致缓存命中时丢失结构化结果。
     * 复用通用 [put] 的双写（L1 + L2）与 TTL 逻辑，仅 value 改为 Base64 编码的 proto 字节。
     *
     * @param key 规范化后的搜索缓存键（"search:xxx"）
     * @param response 待缓存的完整搜索响应
     * @param ttlOverrideMs 可选 L2 TTL 覆盖（毫秒），用于搜索分级
     */
    suspend fun putSearchResponse(key: String, response: SearchResponse, ttlOverrideMs: Long? = null) {
        val encoded = Base64.getEncoder().encodeToString(response.toByteArray())
        put(key, encoded, QuotaCategory.SEARCH, ttlOverrideMs)
    }

    /**
     * 搜索结果专用缓存读：命中返回完整 [SearchResponse]（含 items），未命中返回 null。
     * 反序列化失败时记录 warn 并返回 null（触发回源），**绝不向上抛出**，保证缓存损坏不阻断请求。
     *
     * @param key 规范化后的搜索缓存键（"search:xxx"）
     * @return 完整搜索响应，未命中或反序列化失败返回 null
     */
    suspend fun getSearchResponse(key: String): SearchResponse? {
        val encoded = get(key) ?: return null
        return try {
            SearchResponse.parseFrom(Base64.getDecoder().decode(encoded))
        } catch (e: Exception) {
            log.warn { "搜索缓存反序列化失败（降级回源）: ${e.message}" }
            null
        }
    }

    /**
     * IP 定位结果专用缓存写：将完整 [com.nebula.chat.external.IpLocationResponse]（含结构化字段）
     * 序列化为 Base64 后写入，避免通用 [put] 仅存 formatted 文本导致命中时丢失经纬度/时区等结构化字段。
     * 复用通用 [put] 的双写（L1 + L2）与 TTL 逻辑，类别 [QuotaCategory.GEO]。
     *
     * @param key 规范化后的定位缓存键（"geoip:{ip}"）
     * @param response 待缓存的完整定位响应
     * @param ttlOverrideMs 可选 L2 TTL 覆盖（毫秒），默认取 [com.nebula.common.external.ExternalServiceCacheConfig.l2.ipGeoTtlSeconds]
     */
    suspend fun putGeoIp(key: String, response: com.nebula.chat.external.IpLocationResponse, ttlOverrideMs: Long? = null) {
        val encoded = Base64.getEncoder().encodeToString(response.toByteArray())
        put(key, encoded, QuotaCategory.GEO, ttlOverrideMs)
    }

    /**
     * IP 定位结果专用缓存读：命中返回完整 [com.nebula.chat.external.IpLocationResponse]（含结构化字段），
     * 未命中返回 null。反序列化失败时记录 warn 并返回 null（触发回源），**绝不向上抛出**。
     *
     * @param key 规范化后的定位缓存键（"geoip:{ip}"）
     * @return 完整定位响应，未命中或反序列化失败返回 null
     */
    suspend fun getGeoIp(key: String): com.nebula.chat.external.IpLocationResponse? {
        val encoded = get(key) ?: return null
        return try {
            com.nebula.chat.external.IpLocationResponse.parseFrom(Base64.getDecoder().decode(encoded))
        } catch (e: Exception) {
            log.warn { "IP 定位缓存反序列化失败（降级回源）: ${e.message}" }
            null
        }
    }
}
