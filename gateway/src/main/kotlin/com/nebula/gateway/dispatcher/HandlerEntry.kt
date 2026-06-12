package com.nebula.gateway.dispatcher

import com.nebula.gateway.handler.Handler
import kotlin.reflect.KClass

/**
 * Handler 注册条目 — 持有 Handler 实例 + 类型信息 + 序列化方法引用（D-13）。
 *
 * 在 Handler 注册到 [HandlerRegistry] 时由调用方构建，
 * 存储运行时可用的序列化/反序列化方法引用以避免反射开销。
 *
 * @param handler Handler 实例
 * @param reqClass Req 的 KClass
 * @param respClass Resp 的 KClass
 * @param parseFrom Req 的 parseFrom(bytes) 方法引用，(ByteArray) -> Any
 * @param toByteArray Resp 的 toByteArray() 方法引用，(Any) -> ByteArray
 */
data class HandlerEntry(
    val handler: Handler<*, *>,
    val reqClass: KClass<*>,
    val respClass: KClass<*>,
    val parseFrom: (ByteArray) -> Any,
    val toByteArray: (Any) -> ByteArray
)
