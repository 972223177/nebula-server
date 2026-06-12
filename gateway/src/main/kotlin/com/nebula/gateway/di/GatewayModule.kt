package com.nebula.gateway.di

import com.nebula.chat.Request
import com.nebula.chat.Response
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.HandlerEntry
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.interceptor.AuthInterceptor
import com.nebula.gateway.interceptor.ExceptionInterceptor
import com.nebula.gateway.interceptor.Interceptor
import com.nebula.gateway.interceptor.LogInterceptor
import com.nebula.gateway.interceptor.RateLimitInterceptor
import com.nebula.gateway.session.SessionRegistry
import com.nebula.repository.redis.SessionRepository
import org.koin.dsl.module
import kotlin.reflect.KClass

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
    single<Interceptor> { AuthInterceptor(get(), skipMethods = setOf("system/ping")) }
    single<Interceptor> { LogInterceptor() }
    single<Interceptor> { RateLimitInterceptor() }
    single<Interceptor> { ExceptionInterceptor() }
}

/**
 * 业务 Handler Koin 模块 — 注册业务 Handler。
 *
 * Phase 5+ 按业务域在此模块中继续追加 Handler 注册。
 */
val handlerModule = module {
    single { PingHandler() }
}

/**
 * 将 Handler 注册到 HandlerRegistry。
 *
 * 在 Koin 初始化完成后调用（NebulaServer.kt 中 startKoin 之后），
 * 确保所有 Handler 单例已创建后再注册到 HandlerRegistry 的 method → HandlerEntry 映射表。
 *
 * 每次新增 Handler 时，在此函数中添加对应 HandlerEntry 构建逻辑。
 * 注册时通过 ProtoCodec.buildCodec() 预编译序列化方法引用（D-12），运行时零反射。
 *
 * @param registry HandlerRegistry 实例
 * @param protoCodec ProtoCodec 实例
 * @param pingHandler PingHandler 实例
 */
fun registerHandlers(
    registry: HandlerRegistry,
    protoCodec: ProtoCodec,
    pingHandler: PingHandler
) {
    // PingHandler: method="system/ping", Req=Request, Resp=Response
    val reqCodec = ProtoCodec.buildCodec(Request::class)
    val respCodec = ProtoCodec.buildCodec(Response::class)
    registry.register(
        HandlerEntry(
            handler = pingHandler,
            reqClass = Request::class,
            respClass = Response::class,
            parseFrom = reqCodec.parseFrom,
            toByteArray = respCodec.toByteArray
        )
    )
    // Phase 5+: 在此处添加更多 Handler 注册
}
