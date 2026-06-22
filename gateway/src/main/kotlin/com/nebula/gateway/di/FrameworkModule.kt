package com.nebula.gateway.di

import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.dispatcher.HandlerEntry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.handler.system.SystemHandlerCollector
import com.nebula.gateway.interceptor.AuthInterceptor
import com.nebula.gateway.interceptor.ExceptionInterceptor
import com.nebula.gateway.interceptor.Interceptor
import com.nebula.gateway.interceptor.LogInterceptor
import com.nebula.gateway.interceptor.RateLimitInterceptor
import com.nebula.gateway.service.ChatService
import com.nebula.gateway.session.SessionRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 框架级 Koin 模块 — 注册所有基础设施组件（D-06）。
 *
 * 设计决策引用：
 * - D-06: 拦截器通过 Koin List<Interceptor> 注入
 * - D-07: 拦截器顺序 Auth → Log → RateLimit → Exception
 */
val frameworkModule = module {
    /** 用于 fire-and-forget 后台任务的共享协程作用域（IO 调度器 + SupervisorJob） */
    single(named("sendHandlerScope")) { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    single { HandlerRegistry() }
    single { ProtoCodec }
    single { SessionRegistry(get()) } // SessionStore 从 Koin 注入

    // D-06: Dispatcher 注册 — 自动注入 HandlerRegistry + Interceptors + ProtoCodec
    // 使用 getAll<Interceptor>() 收集所有注册的 Interceptor 实现，而非直接解析 List<T>
    single { Dispatcher(get(), getAll<Interceptor>(), get()) }

    // ChatService 注册 — 依赖 gateway 组件 + service 层组件，全部从 Koin 解析（D-28）
    single { ChatService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    // D-07: 拦截器以 List<Interceptor> 注入，顺序决定执行顺序
    single<Interceptor> { AuthInterceptor(
        get(),
        skipMethods = setOf("system/ping", "user/login", "user/register")
    ) }
    single<Interceptor> { LogInterceptor() }
    single<Interceptor> { RateLimitInterceptor() }
    single<Interceptor> { ExceptionInterceptor() }

    // 系统级组件
    single { PingHandler() }
    single<HandlerCollector>(named("system")) { SystemHandlerCollector(get()) }
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
