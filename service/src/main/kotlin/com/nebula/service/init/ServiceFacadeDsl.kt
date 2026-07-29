package com.nebula.service.init

import org.koin.core.definition.KoinDefinition
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf

/**
 * 字面 Facade 双注册助手 —— 一行完成「实现类 + 聚合 Facade」的注册，
 * 实现类构造参数由 Koin 在**编译期**按类型注入（复用 [singleOf] 的 `new(constructor)` 机制，无反射、免手写 `get()` 链）。
 *
 * 等价展开：
 * ```kotlin
 * singleOf(::XxxServiceImpl)   // key = XxxServiceImpl，构造参数按类型自动注入
 * single { XxxService(get()) } // key = XxxService（facadeCtor 返回）
 * ```
 *
 * 用法：
 * ```kotlin
 * facade(::UserServiceImpl) { UserService(it) }
 * ```
 *
 * 多子域服务（如 `ConversationService` 直接聚合子服务、无独立 `XxxServiceImpl`）不适用本助手，
 * 直接 `singleOf(::ConversationService)` 即可；需要双接口委托（如 `DeadLetterService` 额外实现 `DeadLetterCallback`）
 * 在 `facade(...)` 之后追加 `single<DeadLetterCallback> { get<DeadLetterService>() }`。
 *
 * @param I 实现类类型（如 `UserServiceImpl`）
 * @param F 聚合 Facade 类型（如 `UserService`）
 * @param implCtor 实现类构造函数引用（按主构造器参数个数匹配下方 arity 重载）
 * @param facadeCtor 由实现实例构造 Facade，`(impl) -> XxxService(impl)`
 */
inline fun <reified I : Any, reified F : Any> Module.facade(
    crossinline implCtor: () -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}

inline fun <reified I : Any, reified F : Any, reified T1> Module.facade(
    crossinline implCtor: (T1) -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}

inline fun <reified I : Any, reified F : Any, reified T1, reified T2> Module.facade(
    crossinline implCtor: (T1, T2) -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}

inline fun <reified I : Any, reified F : Any, reified T1, reified T2, reified T3> Module.facade(
    crossinline implCtor: (T1, T2, T3) -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}

inline fun <reified I : Any, reified F : Any, reified T1, reified T2, reified T3, reified T4> Module.facade(
    crossinline implCtor: (T1, T2, T3, T4) -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}

inline fun <reified I : Any, reified F : Any, reified T1, reified T2, reified T3, reified T4, reified T5> Module.facade(
    crossinline implCtor: (T1, T2, T3, T4, T5) -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}

inline fun <reified I : Any, reified F : Any, reified T1, reified T2, reified T3, reified T4, reified T5, reified T6> Module.facade(
    crossinline implCtor: (T1, T2, T3, T4, T5, T6) -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}

inline fun <reified I : Any, reified F : Any, reified T1, reified T2, reified T3, reified T4, reified T5, reified T6, reified T7> Module.facade(
    crossinline implCtor: (T1, T2, T3, T4, T5, T6, T7) -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}

inline fun <reified I : Any, reified F : Any, reified T1, reified T2, reified T3, reified T4, reified T5, reified T6, reified T7, reified T8> Module.facade(
    crossinline implCtor: (T1, T2, T3, T4, T5, T6, T7, T8) -> I,
    noinline facadeCtor: (I) -> F,
): KoinDefinition<F> {
    singleOf(implCtor)
    return single { facadeCtor(get()) }
}
