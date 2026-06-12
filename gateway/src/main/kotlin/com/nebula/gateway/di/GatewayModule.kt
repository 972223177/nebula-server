package com.nebula.gateway.di

import com.nebula.chat.Response
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.HandlerEntry
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.handler.user.BatchGetStatusHandler
import com.nebula.gateway.handler.user.BatchGetUserHandler
import com.nebula.gateway.handler.user.GetPrivacyHandler
import com.nebula.gateway.handler.user.GetProfileHandler
import com.nebula.gateway.handler.user.LoginHandler
import com.nebula.gateway.handler.user.RegisterHandler
import com.nebula.gateway.handler.user.SearchUserHandler
import com.nebula.gateway.handler.user.SetPrivacyHandler
import com.nebula.gateway.interceptor.AuthInterceptor
import com.nebula.gateway.interceptor.ExceptionInterceptor
import com.nebula.gateway.interceptor.Interceptor
import com.nebula.gateway.interceptor.LogInterceptor
import com.nebula.gateway.interceptor.RateLimitInterceptor
import com.nebula.gateway.handler.chat.send.DedupStep
import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.chat.send.SendMessageStep
import com.nebula.gateway.handler.chat.send.ValidateStep
import com.nebula.gateway.handler.chat.send.WriteStep
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.SessionRegistry
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.redis.SessionRepository
import io.lettuce.core.api.StatefulRedisConnection
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

// Koin 4.x DSL: getAll() 用于解析所有 SendMessageStep 实现列表

/**
 * 框架级 Koin 模块 — 注册所有基础设施组件（D-06）。
 *
 * 设计决策引用：
 * - D-06: 拦截器通过 Koin List<Interceptor> 注入
 * - D-07: 拦截器顺序 Auth → Log → RateLimit → Exception
 */
val frameworkModule = module {
    single { HandlerRegistry() }
    single { ProtoCodec }
    single { SessionRegistry(get()) } // SessionRepository 从 Koin 注入

    // D-07: 拦截器以 List<Interceptor> 注入，顺序决定执行顺序
    // Phase 5: user/login 和 user/register 不需要认证（公共方法，D-30）
    single<Interceptor> { AuthInterceptor(
        get(),
        skipMethods = setOf("system/ping", "user/login", "user/register")
    ) }
    single<Interceptor> { LogInterceptor() }
    single<Interceptor> { RateLimitInterceptor() }
    single<Interceptor> { ExceptionInterceptor() }
}

/**
 * 业务 Handler Koin 模块 — 注册所有 Handler 和业务组件。
 *
 * Phase 5: 包含 9 个 Handler（PingHandler + 8 个用户业务 Handler）
 * Phase 6: 新增 UserStreamRegistry/PushService/3 个 Handler/3 个 SendMessageStep/SendMessageHandler scope
 * 新增 Handler 只需在此模块中添加 single { } 声明即可被 Koin 自动管理。
 */
val handlerModule = module {
    single { PingHandler() }
    single { LoginHandler(get(), get()) }        // UserRepository + SessionRegistry
    single { RegisterHandler(get(), get(), get()) } // UserRepository + SnowflakeIdGenerator + EntityManagerFactory
    single { SearchUserHandler(get()) }          // UserRepository
    single { GetProfileHandler(get()) }          // UserRepository
    single { BatchGetUserHandler(get()) }        // UserRepository
    single { BatchGetStatusHandler(get(), get()) } // OnlineStatusRepository + PrivacyRepository
    single { SetPrivacyHandler(get()) }          // PrivacyRepository
    single { GetPrivacyHandler(get()) }          // PrivacyRepository

    // Phase 6: Chat & Message（D-13 Step 链 + PushService + 推送基础设施）
    /** SendMessageHandler 使用 IO 调度器的后台协程执行 fire-and-forget 推送 */
    single(named("sendHandlerScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    single { UserStreamRegistry() }                                                    // D-01
    single { PushService(get(), get()) }                                                // PushService(UserStreamRegistry, ConversationMemberRepository)
    // Step 链注册 — 显式构建列表传递给 SendMessageHandler（D-13）
    single { 
        listOf<SendMessageStep>(
            ValidateStep(get()),                          // ValidateStep(ConversationMemberRepository)
            DedupStep(get()),                             // DedupStep(RedisConnection)
            WriteStep(get(), get(), get())                // WriteStep(SnowflakeIdGenerator, MessageQueueRepository, RedisConnection)
        )
    }
    single { SendMessageHandler(get(), get(), get(), get(), get(named("sendHandlerScope"))) } // SendMessageHandler(steps, pushSvc, convMemberRepo, redisConn, scope)
    single { PullMessagesHandler(get(), get()) }                                         // PullMessagesHandler(MessageRepository, ConversationRepository)
    single { ReadReportHandler(get(), get(), get(), get()) }                             // ReadReportHandler(ConvRepo, ConvMemberRepo, PushSvc, RedisConn)
}

/**
 * 将 Handler 注册到 HandlerRegistry 的 inline 扩展辅助函数。
 *
 * 使用 reified 泛型在编译期获取 Req/Resp 的 KClass，通过 ProtoCodec.buildCodec()
 * 预编译序列化方法引用（D-12），运行时零反射。
 *
 * Review 修复：消除 registerHandlers() 中为每个 Handler 重复编写 HandlerEntry
 * 构建代码的模板冗余。
 *
 * @param handler Handler 实例
 */
private inline fun <reified ReqT : Any, reified RespT : Any> HandlerRegistry.register(
    handler: Handler<ReqT, RespT>
) {
    val reqCodec = ProtoCodec.buildCodec(ReqT::class)
    val respCodec = ProtoCodec.buildCodec(RespT::class)
    this.register(
        HandlerEntry(
            handler = handler,
            reqClass = ReqT::class,
            respClass = RespT::class,
            parseFrom = reqCodec.parseFrom,
            toByteArray = respCodec.toByteArray
        )
    )
}

/**
 * 将所有 Handler 注册到 HandlerRegistry。
 *
 * 在 Koin 初始化完成后调用（NebulaServer.kt 中 startKoin 之后），
 * 确保所有 Handler 单例已创建后再注册到 HandlerRegistry 的 method → HandlerEntry 映射表。
 *
 * 使用 inline 扩展函数 [HandlerRegistry.register] 消除重复模板代码
 * （Review 修复：替代原每 Handler 编写完整 HandlerEntry 构建的方式）。
 *
 * 新增 Handler 只需在 handlerModule 中添加 single { } 声明，并在此函数的参数列表末尾追加即可。
 *
 * @param registry HandlerRegistry 实例
 * @param protoCodec ProtoCodec 实例
 * @param pingHandler PingHandler 实例
 * @param loginHandler LoginHandler 实例
 * @param registerHandler RegisterHandler 实例
 * @param searchUserHandler SearchUserHandler 实例
 * @param getProfileHandler GetProfileHandler 实例
 * @param batchGetUserHandler BatchGetUserHandler 实例
 * @param batchGetStatusHandler BatchGetStatusHandler 实例
 * @param setPrivacyHandler SetPrivacyHandler 实例
 * @param getPrivacyHandler GetPrivacyHandler 实例
 * @param sendMessageHandler SendMessageHandler 实例
 * @param pullMessagesHandler PullMessagesHandler 实例
 * @param readReportHandler ReadReportHandler 实例
 */
fun registerHandlers(
    registry: HandlerRegistry,
    protoCodec: ProtoCodec,
    pingHandler: PingHandler,
    loginHandler: LoginHandler,
    registerHandler: RegisterHandler,
    searchUserHandler: SearchUserHandler,
    getProfileHandler: GetProfileHandler,
    batchGetUserHandler: BatchGetUserHandler,
    batchGetStatusHandler: BatchGetStatusHandler,
    setPrivacyHandler: SetPrivacyHandler,
    getPrivacyHandler: GetPrivacyHandler,
    sendMessageHandler: SendMessageHandler,
    pullMessagesHandler: PullMessagesHandler,
    readReportHandler: ReadReportHandler
) {
    // 使用 inline 扩展函数逐个注册，消除重复的 HandlerEntry 构建代码（Review 修复）
    registry.register(pingHandler)               // system/ping: Request → Response
    registry.register(loginHandler)              // user/login: LoginReq → LoginResp
    registry.register(registerHandler)           // user/register: RegisterReq → RegisterResp
    registry.register(searchUserHandler)         // user/search: SearchUserReq → SearchUserResp
    registry.register(getProfileHandler)         // user/getProfile: GetProfileReq → GetProfileResp
    registry.register(batchGetUserHandler)       // user/batchGet: BatchIdRequest → BatchGetUserResp
    registry.register(batchGetStatusHandler)     // user/batchGetStatus: BatchIdRequest → BatchGetStatusResp
    registry.register(setPrivacyHandler)         // user/setPrivacy: SetPrivacyReq → Response
    registry.register(getPrivacyHandler)         // user/getPrivacy: GetPrivacyReq → GetPrivacyResp
    registry.register(sendMessageHandler)        // chat/send: SendMessageReq → SendMessageResp
    registry.register(pullMessagesHandler)       // message/pull: PullMessagesReq → PullMessagesResp
    registry.register(readReportHandler)         // message/read: ReadReportReq → Response
}
