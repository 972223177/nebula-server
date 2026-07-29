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
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Service 层 Koin 模块 — 注册所有业务服务实例。
 *
 * 放置于 service 模块而非 gateway 模块，因为 service 构造函数的依赖
 * 包含 repository 层类型，gateway 层不应可见（D-28 分层架构）。
 *
 * 所有需要 MySQL 写入/读取的服务现在都通过 [com.nebula.repository.dao.JpaTxRunner] 承载事务。
 *
 * 注册约定（字面 Facade 统一结构）：
 * - 单域服务用 `facade(::XxxServiceImpl) { XxxService(it) }` 一行完成「实现 + 聚合 Facade」双注册，
 *   实现类构造参数由 Koin 编译期注入（[singleOf] + `new`，无反射、免手写 `get()` 链）。
 * - 多子域聚合（如 `ConversationService` 直接组合子服务、无独立 `XxxServiceImpl`）用 `singleOf(::XxxService)`。
 * - 需要双接口委托（如 `DeadLetterService` 额外实现 `DeadLetterCallback`）在 `facade(...)` 后追加
 *   `single<DeadLetterCallback> { get<DeadLetterService>() }`。
 */
val serviceKoinModule = module {
    // ─── 用户域 ───
    facade(::UserServiceImpl) { UserService(it) }                 // 注册 UserServiceImpl + 聚合 UserService
    facade(::UserPrivacyServiceImpl) { UserPrivacyService(it) }   // 隐私设置
    facade(::OnlineStatusServiceImpl) { OnlineStatusService(it) } // 在线状态

    // ─── 消息域 ───
    facade(::MessageServiceImpl) { MessageService(it) }           // 消息业务（含 MessagePersistHelper 落库基础设施）

    // ─── 会话 / 群组（多子域 Facade，无独立 Impl）───
    singleOf(::GroupService)                                      // 群生命周期 + 成员
    singleOf(::ConversationQueryService)                          // 会话查询/列表/删除/私聊
    singleOf(::ConversationService)                               // 聚合：by 委托 Group/Query 子服务，Handler 零改动

    // ─── 好友（多子域 Facade，无独立 Impl）───
    singleOf(::FriendRequestService)                              // 好友申请子域
    singleOf(::FriendshipService)                                 // 好友关系/查询子域
    singleOf(::FriendService)                                     // 聚合：by 委托两子服务，Handler 零改动

    // ─── 序列号 ───
    facade(::SeqServiceImpl) { SeqService(it) }                   // 全局序列号分配

    // ─── 死信（双接口委托：DeadLetterOperations + DeadLetterCallback）───
    facade(::DeadLetterServiceImpl) { DeadLetterService(it) }     // 死信业务实现 + 聚合 Facade
    single<DeadLetterCallback> { get<DeadLetterService>() }       // 桥接注入点（ServerBootstrap.setupDeadLetterBridge）

    // ─── 外部服务（天气 / 搜索 / IP 定位第三方 API 集成，见 external-service-backend.md）───
    // 配置 bean（ExternalServiceConfig / QuotaConfig / CacheConfig）由 server 层在 startKoin 注册，
    // 此处通过 get<T>() 按类型注入；Redis 连接（StatefulRedisConnection）由 repository 模块注册。
    singleOf(::ExternalServiceCache)                              // ExternalServiceCacheConfig + Redis 连接
    singleOf(::PerUserQuotaStore)                                 // Redis 连接（每用户防御子限）
    singleOf(::QuotaManager)                                      // ExternalServiceQuotaConfig + Redis 连接
    facade(::WeatherServiceImpl) { WeatherService(it) }           // 天气（和风 + 降级 wttr.in）
    facade(::SearchServiceImpl) { SearchService(it) }            // 联网搜索（Serper）
    facade(::GeoIpServiceImpl) { GeoIpService(it) }              // IP 地理定位（高德 Amap）

    // 外部服务执行体：每个服务一个 ExternalServiceInvoker 实现，Koin 按类型 getAll 聚合，
    // ExternalServiceOrchestrator 仅做注册表 + 通用 invoke 入口，文件不随服务数增长。
    singleOf(::ExternalServicePipeline)                           // 模板方法（缓存→配额→调上游→写缓存）
    // 注意：同接口多实现必须用 bind 显式绑定接口类型，否则三个 single<ExternalServiceInvoker<*>>
    // 主 key 相同会互相覆盖（Koin 按主类型索引），getAll<ExternalServiceInvoker<*>>() 只会聚合出 1 个，
    // 导致 ExternalServiceOrchestrator 实际只持有最后注册的服务（其余调用落入 NOT_FOUND）。
    // bind 把每个实现的主类型设为具体类、同时挂接接口类型，getAll 才能正确聚合全部实现。
    singleOf(::WeatherInvoker).bind(ExternalServiceInvoker::class)
    singleOf(::WebSearchInvoker).bind(ExternalServiceInvoker::class)
    singleOf(::GeoIpInvoker).bind(ExternalServiceInvoker::class)
    // 注册表聚合：把各 Invoker 按 serviceId 收进 Map（getAll 自动收集全部 bind 实现）
    single { ExternalServiceOrchestrator(getAll<ExternalServiceInvoker<*>>().associateBy { it.serviceId }) }

    // 配额模块生命周期（由 ModuleInitializer 在启动/关闭时管理，遵守分层依赖）
    // 无参构造 —— QuotaManager 在 init() 阶段才懒解析，避免收集阶段急切依赖运行时 declare 的 Redis 连接
    single<ModuleInitializer> { QuotaModuleInitializer() }
}
