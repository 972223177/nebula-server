package com.nebula.service.external

import com.nebula.common.external.ExternalServiceQuotaConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

/**
 * 配额计数类别。
 *
 * WEATHER 按日重置（和风天气免费 1000 次/天全局共享池），
 * SEARCH 按月重置（Serper 2500 次/月全局共享池）。
 */
enum class QuotaCategory { WEATHER, SEARCH }

/**
 * 计算某类别配额周期剩余秒数（weather=当日余秒，search=当月余秒）。
 *
 * 供 [QuotaManager] / [PerUserQuotaStore] 设置 Redis EXPIRE 与对外返回重置倒计时共用。
 *
 * @param category 配额类别
 * @return 距离周期重置的剩余秒数
 */
fun quotaRemainingSeconds(category: QuotaCategory): Long {
    val now = LocalDateTime.now()
    return when (category) {
        QuotaCategory.WEATHER -> {
            val tomorrow = now.toLocalDate().plusDays(1).atStartOfDay()
            max(1, java.time.temporal.ChronoUnit.SECONDS.between(now, tomorrow))
        }
        QuotaCategory.SEARCH -> {
            val firstNextMonth = now.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay()
            max(1, java.time.temporal.ChronoUnit.SECONDS.between(now, firstNextMonth))
        }
    }
}

/**
 * 外部服务配额管理器（D-XX）。
 *
 * 存储策略：
 * - **主存储 = Redis 原子计数**：key `ext:quota:<category>`，`INCRBY` 累加上游调用数，
 *   `EXPIRE` 设为对应周期剩余秒数（weather=当日余秒、search=当月余秒）实现自动重置，
 *   多实例共享同一 Redis 计数，全局池严格不突破（兜底多实例）。
 * - **降级 = 本地文件（仅 Redis 故障态）**：Redis 不可用时降级为文件计数（Mutex 保护），
 *   保证服务不中断；该态下多实例会各自独立计数（可能突破上限，属故障态，不推荐多实例跑降级）。
 *
 * 职责：
 * - 支持日和月两种滚动周期（通过 EXPIRE 自动重置，无手动边界比对）
 * - 三态判断：正常（< warn-threshold%）、预警（≥warn 且 <reject）、拒绝（≥reject-threshold）
 * - 线程安全：Redis 主路径走 Lettuce 协程命令（天然原子）；文件降级路径用 Mutex 保护读写
 *
 * @param config 配额配置（限额、阈值百分比、文件路径、刷盘间隔）
 * @param connection 项目既有 Redis 连接（StatefulRedisConnection），供协程命令计数与降级判断
 */
@io.lettuce.core.ExperimentalLettuceCoroutinesApi
class QuotaManager(
    private val config: ExternalServiceQuotaConfig,
    private val connection: StatefulRedisConnection<String, String>
) {
    private val log = KotlinLogging.logger {}

    /** Lettuce 协程命令（与 RedisDeliveryTracker 同范式） */
    private val redis = RedisCoroutinesCommandsImpl(connection.reactive())

    /** 自有协程作用域（service 模块自包含，不依赖 gateway 的 serverScope，遵守单向依赖） */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 文件降级态内存计数（仅 Redis 不可用时使用），按 QuotaCategory 分桶 */
    private val fileMutex = Mutex()
    private val fileCounts = mutableMapOf<QuotaCategory, Long>()
    private var usingFileFallback = false

    /** 启动：Redis 主路径无需刷盘；文件降级态由 consume/usage 按需进入并启动刷盘协程 */
    fun start() {
        log.info { "QuotaManager 启动（Redis 主路径，weather-daily=${config.weatherDailyLimit}, search-monthly=${config.searchMonthlyLimit}）" }
    }

    /** 停止：取消自有协程作用域（文件降级刷盘协程随之结束） */
    fun stop() {
        scope.cancel()
    }

    /**
     * 消费配额，单位 = 上游 API 调用次数（见 external-service-backend.md §3.1 计数粒度）。
     *
     * 先判断当前用量是否已达拒绝线（≥ reject-threshold）：若已达则直接拒绝、**不累加**，
     * 避免超限后计数被错误放大；否则累加并返回允许。
     *
     * @param category 配额类别
     * @param count 本次上游调用次数（weather 传实际扇出数，search 传 1）
     * @return true=允许，false=已达拒绝线（应抛 QUOTA_EXCEEDED）
     */
    suspend fun consume(category: QuotaCategory, count: Int = 1): Boolean {
        val limit = limitOf(category)
        val used = usageRaw(category)
        if (limit > 0 && used >= limit * config.rejectThreshold / 100) {
            return false
        }
        if (usingFileFallback) {
            fileMutex.withLock { fileCounts[category] = (fileCounts[category] ?: 0) + count }
        } else {
            try {
                redis.incrby(keyOf(category), count.toLong())
                // 周期重置：每次写入均尝试 EXPIRE（幂等，覆盖即刷新剩余秒数）
                redis.expire(keyOf(category), quotaRemainingSeconds(category))
            } catch (e: Exception) {
                log.warn { "Redis 配额计数失败，降级文件: ${e.message}" }
                enterFileFallback()
                fileMutex.withLock { fileCounts[category] = (fileCounts[category] ?: 0) + count }
            }
        }
        return true
    }

    /** 获取当前用量百分比（按上游调用数计，0~100） */
    suspend fun usagePercent(category: QuotaCategory): Int {
        val limit = limitOf(category)
        if (limit <= 0) return 0
        return (usageRaw(category) * 100 / limit).toInt().coerceIn(0, 100)
    }

    /** 获取配额重置剩余秒数（仅 QUOTA_EXCEEDED 场景使用） */
    fun remainingSecondsUntilReset(category: QuotaCategory): Long = quotaRemainingSeconds(category)

    /** 手动刷盘（供 shutdown hook 调用，仅文件降级态有效；Redis 主路径为幂等空操作） */
    fun flush() {
        if (usingFileFallback) persistFile()
    }

    // ─── 内部：用量读取（区分主路径 / 降级路径） ───

    /** 带类别的用量读取（主路径读 Redis，降级读文件） */
    private suspend fun usageRaw(category: QuotaCategory): Long {
        return if (usingFileFallback) {
            fileMutex.withLock { fileCounts[category] ?: 0 }
        } else {
            try {
                redis.get(keyOf(category))?.toLongOrNull() ?: 0L
            } catch (e: Exception) {
                log.warn { "Redis 配额读取失败，降级文件: ${e.message}" }
                enterFileFallback()
                fileMutex.withLock { fileCounts[category] ?: 0 }
            }
        }
    }

    // ─── 文件降级持久化（仅故障态） ───

    private suspend fun enterFileFallback() {
        fileMutex.withLock {
            if (usingFileFallback) return
            loadFileWithBoundaryCheck()
            usingFileFallback = true
            // 启动周期刷盘协程（异常退出最多丢失一个刷盘间隔内的用量）
            scope.launch {
                while (true) {
                    delay((config.flushIntervalSeconds * 1000L).milliseconds)
                    persistFile()
                }
            }
        }
    }

    private fun loadFileWithBoundaryCheck() {
        val file = fileOf()
        fileCounts[QuotaCategory.WEATHER] = 0
        fileCounts[QuotaCategory.SEARCH] = 0
        if (!file.exists()) return
        try {
            val props = file.readLines().mapNotNull { line ->
                val idx = line.indexOf('=')
                if (idx < 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }.toMap()
            // 周期边界校验：日期/月份不一致则加载时重置为 0，防止跨日/跨月带旧数绕过限额
            fileCounts[QuotaCategory.WEATHER] =
                if (props["weather.date"] == today()) props["weather.count"]?.toLongOrNull() ?: 0 else 0
            fileCounts[QuotaCategory.SEARCH] =
                if (props["search.month"] == thisMonth()) props["search.count"]?.toLongOrNull() ?: 0 else 0
        } catch (e: Exception) {
            log.warn { "配额文件解析失败，归零: ${e.message}" }
        }
    }

    private fun persistFile() {
        val file = fileOf()
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parent, file.name + ".tmp")
            val sb = StringBuilder()
            sb.append("weather.date=${today()}\n")
            sb.append("weather.count=${fileCounts[QuotaCategory.WEATHER] ?: 0}\n")
            sb.append("search.month=${thisMonth()}\n")
            sb.append("search.count=${fileCounts[QuotaCategory.SEARCH] ?: 0}\n")
            tmp.writeText(sb.toString())
            // POSIX 原子替换：崩溃只可能留下 .tmp 残留，原文件始终完整
            tmp.renameTo(file)
        } catch (e: Exception) {
            log.warn { "配额文件刷盘失败: ${e.message}" }
        }
    }

    private fun fileOf(): File {
        val path = config.filePath.replaceFirst("~", System.getProperty("user.home"))
        return File(path)
    }

    private fun today(): String = LocalDate.now().toString()
    private fun thisMonth(): String {
        val d = LocalDate.now()
        return "%04d-%02d".format(d.year, d.monthValue)
    }

    private fun limitOf(category: QuotaCategory): Long = when (category) {
        QuotaCategory.WEATHER -> config.weatherDailyLimit.toLong()
        QuotaCategory.SEARCH -> config.searchMonthlyLimit.toLong()
    }

    private fun keyOf(category: QuotaCategory): String = "ext:quota:${category.name.lowercase()}"
}
