package com.nebula.service.external

import com.nebula.common.external.ExternalServiceCacheConfig
import com.nebula.common.external.ExternalServiceExceptions
import com.nebula.common.external.ExternalServiceQuotaConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.LocalDate

/**
 * 外部服务编排管线（D-XX）—— 三个外部服务共用的「配额 + 缓存 + 净化」骨架。
 *
 * 完整流程（由 [run] 固定）：
 * - 缓存命中（不扣配额）→ 全局配额拒绝线检查 → 每用户防御子限 → 预估扣减
 *   → 调上游 Service（[fetch]）→ 写缓存（[store]）。
 *
 * 各服务以 [ExternalServiceInvoker] 表达差异（缓存读写/上游调用/净化/构造响应），
 * 复用本管线即天然获得配额 + 缓存 + 净化保护，杜绝手抄遗漏。
 *
 * @param quotaManager 全局配额管理器（Redis 主存储 + 文件降级）
 * @param cache 外部服务缓存（L1 + L2）
 * @param perUserQuotaStore 每用户防御子限存储（Redis 计数）
 * @param quotaConfig 配额配置（限额、阈值、每用户子限）
 * @param cacheConfig 缓存配置（L2 分级 TTL）
 */
class ExternalServicePipeline(
    private val quotaManager: QuotaManager,
    internal val cache: ExternalServiceCache,
    private val perUserQuotaStore: PerUserQuotaStore,
    internal val quotaConfig: ExternalServiceQuotaConfig,
    internal val cacheConfig: ExternalServiceCacheConfig
) {
    private val log = KotlinLogging.logger {}

    /**
     * 通用编排骨架（模板方法）—— 各外部服务的公共管线。
     *
     * 顺序严格固定：缓存命中（不扣配额）→ 全局配额拒绝线检查 → 每用户防御子限
     * → 预估扣减 → 上游调用 + 构造响应（[fetch]）→ 写缓存（[store]）。
     * 任一配额阶段失败统一抛 [com.nebula.common.exception.BizException] QUOTA_EXCEEDED，
     * 由 ExceptionInterceptor 转为 Response。
     *
     * 各服务仅通过 lambda 表达差异，复用本骨架即天然获得配额 + 缓存 + 净化保护：
     * - [load] 从缓存读取并重建返回类型（命中即返回，不扣配额）；
     * - [fetch] 调上游 service + 净化 + 构造 Proto 响应（统一经 [ExternalContentSanitizer]）；
     * - [store] 将结果写回缓存（含分级 TTL）。
     *
     * @param userId 调用方用户 ID（每用户防御子限）
     * @param cacheKey 规范化缓存键
     * @param category 配额类别（WEATHER/SEARCH/GEO）
     * @param perUserLimit 每用户防御子限（日/月）
     * @param period 每用户子限周期键（日/月）
     * @param estimate 预估上游调用次数（天气约 8，搜索/定位 1）
     * @param load 缓存读取（命中返回重建的响应）
     * @param fetch 上游调用 + 净化 + 构造响应
     * @param store 结果写回缓存
     * @return 服务响应（缓存命中或上游新鲜结果）
     * @throws com.nebula.common.exception.BizException QUOTA_EXCEEDED（由 ExceptionInterceptor 转为 Response）
     */
    suspend fun <T> run(
        userId: Long,
        cacheKey: String,
        category: QuotaCategory,
        perUserLimit: Int,
        period: String,
        estimate: Int = 1,
        load: suspend () -> T?,
        fetch: suspend () -> T,
        store: suspend (T) -> Unit
    ): T {
        load()?.let {
            log.debug { "外部服务缓存命中 cacheKey=$cacheKey" }
            return it
        }
        if (quotaManager.usagePercent(category) >= quotaConfig.rejectThreshold) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(category))
        }
        if (!perUserQuotaStore.tryConsume(userId, category, perUserLimit, period)) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(category))
        }
        if (!quotaManager.consume(category, estimate)) {
            throw ExternalServiceExceptions.quotaExceeded(quotaManager.remainingSecondsUntilReset(category))
        }
        val result = fetch()
        store(result)
        return result
    }

    /** 当日日期键（每用户天气日限周期） */
    fun dateKey(): String = LocalDate.now().toString()

    /** 当月键（每用户搜索月限周期） */
    fun monthKey(): String {
        val d = LocalDate.now()
        return "%04d-%02d".format(d.year, d.monthValue)
    }
}
