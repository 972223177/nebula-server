package com.nebula.service.init

import com.nebula.common.init.DeadLetterCallback
import com.nebula.common.init.ModuleInitializer
import com.nebula.service.admin.DeadLetterService
import com.nebula.service.chat.MessageService
import com.nebula.service.conversation.ConversationService
import com.nebula.service.friend.FriendService
import com.nebula.service.external.ExternalServiceCache
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.external.PerUserQuotaStore
import com.nebula.service.external.QuotaManager
import com.nebula.service.external.SearchService
import com.nebula.service.external.WeatherService
import com.nebula.service.sequence.SeqService
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.user.UserService
import org.koin.dsl.module

/**
 * Service 层 Koin 模块 — 注册所有业务服务实例。
 *
 * 放置于 service 模块而非 gateway 模块，因为 service 构造函数的依赖
 * 包含 repository 层类型，gateway 层不应可见（D-28 分层架构）。
 *
 * 所有需要 MySQL 写入/读取的服务现在都通过 [com.nebula.repository.dao.JpaTxRunner] 承载事务。
 */
val serviceKoinModule = module {
    single { UserService(get(), get(), get(), get()) }
    single { UserPrivacyService(get(), get()) }
    single { OnlineStatusService(get()) }
    single { MessageService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { ConversationService(get(), get(), get(), get(), get()) }
    single { FriendService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { DeadLetterService(get(), get(), get(), get()) }
    single<DeadLetterCallback> { get<DeadLetterService>() }
    single { SeqService(get()) }

    // ─── 外部服务（天气 / 搜索第三方 API 集成，见 external-service-backend.md） ───
    // 配置 bean（ExternalServiceConfig / QuotaConfig / CacheConfig）由 server 层在 startKoin 注册，
    // 此处通过 get<T>() 按类型注入；Redis 连接（StatefulRedisConnection）由 repository 模块注册。
    single { ExternalServiceCache(get(), get()) }                       // ExternalServiceCacheConfig + Redis 连接
    single { PerUserQuotaStore(get()) }                                 // Redis 连接（每用户防御子限）
    single { QuotaManager(get(), get()) }                               // ExternalServiceQuotaConfig + Redis 连接
    single { WeatherService(get(), get()) }                            // ExternalServiceConfig + ExternalServiceCache（GeoAPI 缓存）
    single { SearchService(get()) }                                     // ExternalServiceConfig
    single { ExternalServiceOrchestrator(get(), get(), get(), get(), get(), get(), get()) }

    // 配额模块生命周期（由 ModuleInitializer 在启动/关闭时管理，遵守分层依赖）
    // 注意：无参构造 —— QuotaManager 在 init() 阶段才懒解析，避免收集阶段急切依赖运行时 declare 的 Redis 连接
    single<ModuleInitializer> { QuotaModuleInitializer() }
}
