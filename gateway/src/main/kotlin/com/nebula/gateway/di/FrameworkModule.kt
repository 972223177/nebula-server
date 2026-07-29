package com.nebula.gateway.di
import com.nebula.gateway.handler.MethodNames

import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerEntry
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.interceptor.*
import com.nebula.gateway.service.ChatService
import com.nebula.gateway.session.SessionRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * 框架级 Koin 模块 — 注册所有基础设施组件（D-06）。
 *
 * 设计决策引用：
 * - D-06: 拦截器通过 Koin List<Interceptor> 注入
 * - D-07: 拦截器顺序 Auth → Log → RateLimit → Exception
 */
private val logger = KotlinLogging.logger {}

val frameworkModule = module {
    /** 服务级后台任务作用域（D-85）— 延迟离线、死信补偿、设备类型清理、在线状态变更推送等跨连接存活的任务使用（IO 调度器 + SupervisorJob + 统一异常处理器） */
    single(named("serverScope")) {
        CoroutineScope(
            Dispatchers.IO + SupervisorJob() + CoroutineExceptionHandler { _, e ->
                logger.error("serverScope 未捕获异常（后台任务异常已被兜底吞掉，请检查任务内部 try-catch 是否遗漏）: ${e.stackTraceToString()}")
            }
        )
    }
    single { HandlerRegistry() }
    single { ProtoCodec }
    single { SessionRegistry(get()) } // SessionStore 从 Koin 注入

    // D-06: Dispatcher 注册 — 显式构造拦截器列表保证顺序（D-07）
    // ⚠️ 注意：不能使用 getAll<Interceptor>() 收集，因为连续注册多个
    // single<Interceptor> 时 Koin 会以最后一个覆盖前面的。
    // 改用 named qualifier + 显式列表以保证所有 4 个拦截器都被包含
    // 必须显式使用 single<Interceptor>(named(...)) 将类型声明为 Interceptor 而非具体实现类，
    // D-30, D-77: 白名单覆盖 — 在 AuthInterceptor 默认（system/ping + admin/ + system/sensitive_word）
    // 基础上额外加入 user/login 和 user/register（未持有 Token 也能调用）。
    // 使用前缀匹配：admin/ 覆盖所有 admin/* 管理接口，system/sensitive_word 覆盖客户端下载接口。
    single<Interceptor>(named("authInterceptor")) { AuthInterceptor(
        get(),
        skipMethods = setOf(MethodNames.System.PING, MethodNames.Admin.METHOD_PREFIX, MethodNames.System.SENSITIVE_WORD_PREFIX, MethodNames.User.LOGIN, MethodNames.User.REGISTER)
    ) }
    single<Interceptor>(named("logInterceptor")) { LogInterceptor() }
    single<Interceptor>(named("rateLimitInterceptor")) { RateLimitInterceptor() }
    single<Interceptor>(named("exceptionInterceptor")) { ExceptionInterceptor() }
    single { Dispatcher(
        get(),
        listOf(
            get<Interceptor>(named("authInterceptor")),
            get<Interceptor>(named("logInterceptor")),
            get<Interceptor>(named("rateLimitInterceptor")),
            get<Interceptor>(named("exceptionInterceptor"))
        ),
        get()
    ) }

    // ChatService 注册 — 依赖 gateway 组件 + service 层组件，全部从 Koin 解析（D-28）
    // D-85: 注入 serverScope 用于跨连接存活的后台任务
    single { ChatService(get(), get(), get(), get(), get(), get(), get(), get(), get(named("serverScope"))) }

    // 系统级组件
    single { PingHandler() } bind Handler::class
}

/**
 * 将 Handler 注册到 HandlerRegistry 的 inline 扩展辅助函数。
 *
 * 使用 reified 泛型在编译期获取 Req/Resp 的 KClass，通过 ProtoCodec.buildCodec()
 * 预编译序列化方法引用（D-12），运行时零反射。
 *
 * internal 可见性：供同一模块内的 HandlerCollector 实现调用（D-XX HandlerCollector 模式）。
 *
 * @param handler Handler 实例
 */
internal inline fun <reified ReqT : Any, reified RespT : Any> HandlerRegistry.register(
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
