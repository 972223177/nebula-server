package com.nebula.gateway.handler

import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.HandlerEntry
import com.nebula.gateway.dispatcher.HandlerRegistry
import java.lang.reflect.ParameterizedType

/**
 * 统一 Handler 收集器 — 经 Koin `getAll<Handler<*,*>>()` 自动发现并注册全部 Handler 到 HandlerRegistry。
 *
 * 替代原先按业务分组、需在构造函数中逐个列举 Handler 的多个 Collector 实现
 * （UserHandlerCollector / ChatHandlerCollector / ConversationHandlerCollector / FriendHandlerCollector /
 * ExternalHandlerCollector / AdminHandlerCollector / SystemHandlerCollector /
 * SensitiveWordHandlerCollector / DeliveryHandlerCollector）。
 *
 * 新增 Handler 只需在对应 Koin 模块声明 `single { XHandler(...) }`，本收集器经 `getAll()` 自动纳入，
 * 无需再修改任何 Collector 的构造函数或 registerAll()，消除了「新增一个 method 需改动 4~6 处」中的
 * Collector 编辑步骤（见 CODEBUDDY §六 的「新增 Handler 的标准动作」）。
 *
 * 类型信息提取（无 kotlin-reflect 依赖）：Handler 在声明时即 `class XHandler : Handler<Req, Resp>`，
 * Handler 为接口，其实参保存在 `javaClass.genericInterfaces` 的 ParameterizedType 中；取出真实
 * Req/Resp 的 Class 后，用 [ProtoCodec.buildCodec] 预编译序列化方法引用，与原 reified 扩展
 * （[com.nebula.gateway.di.register]）等价（运行时一次性、无循环反射开销）。
 */
class AllHandlerCollector(
    private val handlers: List<Handler<*, *>>
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        handlers.forEach { registry.register(buildEntry(it)) }
    }

    private fun buildEntry(handler: Handler<*, *>): HandlerEntry {
        val handlerType = handler.javaClass.genericInterfaces
            .filterIsInstance<ParameterizedType>()
            .first { (it.rawType as? Class<*>) == Handler::class.java }
        val reqClass = handlerType.actualTypeArguments[0] as Class<*>
        val respClass = handlerType.actualTypeArguments[1] as Class<*>
        val reqCodec = ProtoCodec.buildCodec(reqClass.kotlin)
        val respCodec = ProtoCodec.buildCodec(respClass.kotlin)
        return HandlerEntry(
            handler = handler,
            reqClass = reqClass.kotlin,
            respClass = respClass.kotlin,
            parseFrom = reqCodec.parseFrom,
            toByteArray = respCodec.toByteArray
        )
    }
}
