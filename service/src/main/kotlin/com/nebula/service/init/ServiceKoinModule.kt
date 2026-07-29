package com.nebula.service.init

import com.nebula.common.init.DeadLetterCallback
import com.nebula.common.init.ModuleInitializer
import com.nebula.service.admin.DeadLetterService
import com.nebula.service.admin.DeadLetterServiceImpl
import com.nebula.service.chat.MessageService
import com.nebula.service.chat.MessageServiceImpl
import com.nebula.service.conversation.ConversationQueryService
import com.nebula.service.conversation.ConversationService
import com.nebula.service.conversation.GroupService
import com.nebula.service.external.*
import com.nebula.service.friend.FriendRequestService
import com.nebula.service.friend.FriendService
import com.nebula.service.friend.FriendshipService
import com.nebula.service.sequence.SeqService
import com.nebula.service.sequence.SeqServiceImpl
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.OnlineStatusServiceImpl
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.user.UserPrivacyServiceImpl
import com.nebula.service.user.UserService
import com.nebula.service.user.UserServiceImpl
import org.koin.dsl.bind
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
    single { UserServiceImpl(get(), get(), get(), get()) }                             // 用户业务实现
    single { UserService(get()) }                                                      // 聚合 Facade：by 委托 UserServiceImpl，Handler 零改动
    single { UserPrivacyServiceImpl(get(), get()) }                                 // 隐私设置实现
    single { UserPrivacyService(get()) }                                           // 聚合 Facade：by 委托 UserPrivacyServiceImpl，Handler 零改动
    single { OnlineStatusServiceImpl(get()) }                                  // 在线状态实现
    single { OnlineStatusService(get()) }                                       // 聚合 Facade：by 委托 OnlineStatusServiceImpl，Handler 零改动
    single { MessageServiceImpl(get(), get(), get(), get(), get(), get(), get(), get()) } // 消息业务实现
    single { MessageService(get()) }                                                        // 聚合 Facade：by 委托 MessageServiceImpl，Handler 零改动
    single { GroupService(get(), get(), get(), get()) }                 // 群生命周期 + 成员（ConversationService Facade 子服务）
    single { ConversationQueryService(get(), get(), get(), get(), get()) } // 会话查询/列表/删除/私聊（ConversationService Facade 子服务）
    single { ConversationService(get(), get()) }                          // 聚合服务：by 委托 Group/Query 子服务，Handler 零改动
    single { FriendRequestService(get(), get(), get(), get(), get(), get(), get()) } // 好友申请子域（FriendService 子服务）
    single { FriendshipService(get(), get(), get(), get(), get(), get()) }            // 好友关系/查询子域（FriendService 子服务）
    single { FriendService(get(), get()) }                                            // 聚合服务：by 委托两子服务，Handler 零改动
    single { DeadLetterServiceImpl(get(), get(), get(), get()) }                         // 死信业务实现（同时实现 DeadLetterCallback）
    single { DeadLetterService(get()) }                                                   // 聚合 Facade：by 委托 DeadLetterServiceImpl + DeadLetterCallback，桥接零改动
    single<DeadLetterCallback> { get<DeadLetterService>() }
    single { SeqServiceImpl(get()) }                                          // 序列号实现
    single { SeqService(get()) }                                               // 聚合 Facade：by 委托 SeqServiceImpl，Handler 零改动

    // ─── 外部服务（天气 / 搜索第三方 API 集成，见 external-service-backend.md） ───
    // 配置 bean（ExternalServiceConfig / QuotaConfig / CacheConfig）由 server 层在 startKoin 注册，
    // 此处通过 get<T>() 按类型注入；Redis 连接（StatefulRedisConnection）由 repository 模块注册。
    single { ExternalServiceCache(get(), get()) }                       // ExternalServiceCacheConfig + Redis 连接
    single { PerUserQuotaStore(get()) }                                 // Redis 连接（每用户防御子限）
    single { QuotaManager(get(), get()) }                               // ExternalServiceQuotaConfig + Redis 连接
    single { WeatherServiceImpl(get(), get()) }                                      // 天气实现
    single { WeatherService(get()) }                                                 // 聚合 Facade：by 委托 WeatherServiceImpl，Handler 零改动
    single { SearchServiceImpl(get()) }                                              // 搜索实现
    single { SearchService(get()) }                                                  // 聚合 Facade：by 委托 SearchServiceImpl，Handler 零改动
    single { GeoIpServiceImpl(get()) }                                               // IP 地理实现
    single { GeoIpService(get()) }                                                   // 聚合 Facade：by 委托 GeoIpServiceImpl，Handler 零改动
    // 外部服务执行体：每个服务一个 ExternalServiceInvoker 实现，Koin 按类型 getAll 聚合，
    // ExternalServiceOrchestrator 仅做注册表 + 通用 invoke 入口，文件不随服务数增长
    single { ExternalServicePipeline(get(), get(), get(), get(), get()) }
    // 注意：同接口多实现必须用 bind 显式绑定接口类型，否则三个 single<ExternalServiceInvoker<*>>
    // 主 key 相同会互相覆盖（Koin 按主类型索引），getAll<ExternalServiceInvoker<*>>() 只会聚合出 1 个，
    // 导致 ExternalServiceOrchestrator 实际只持有最后注册的服务（其余调用落入 NOT_FOUND）。
    // bind 把每个实现的主类型设为具体类、同时挂接接口类型，getAll 才能正确聚合全部实现。
    single { WeatherInvoker(get(), get()) } bind ExternalServiceInvoker::class
    single { WebSearchInvoker(get(), get()) } bind ExternalServiceInvoker::class
    single { GeoIpInvoker(get(), get()) } bind ExternalServiceInvoker::class
    single { ExternalServiceOrchestrator(getAll<ExternalServiceInvoker<*>>().associateBy { it.serviceId }) }

    // 配额模块生命周期（由 ModuleInitializer 在启动/关闭时管理，遵守分层依赖）
    // 注意：无参构造 —— QuotaManager 在 init() 阶段才懒解析，避免收集阶段急切依赖运行时 declare 的 Redis 连接
    single<ModuleInitializer> { QuotaModuleInitializer() }
}
