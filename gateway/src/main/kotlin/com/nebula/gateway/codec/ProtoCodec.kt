package com.nebula.gateway.codec

import com.nebula.gateway.dispatcher.HandlerEntry
import com.google.protobuf.ByteString
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.reflect.KClass

/**
 * Proto 编解码器 — MethodHandles 预编译，运行时零反射开销（D-12）。
 *
 * 注册 Handler 时一次性查找并缓存方法引用：
 * - parseFrom: static method, (byte[]) → ProtoMsg
 * - toByteArray: instance method, () → byte[]
 *
 * 使用 [MethodHandles.lookup] 而非 Java 反射，性能提升 10-50 倍。
 * 空载荷处理（Pitfall 5）：当 bytes 为空数组时返回默认实例而非调用 parseFrom。
 */
object ProtoCodec {

    /**
     * 为指定 Proto 类构建序列化/反序列化方法引用。
     *
     * 在注册 Handler 时调用，运行时复用缓存的 [CodecPair]。
     *
     * @param protoClass Proto 消息的 KClass
     * @return [CodecPair] 包含 parseFrom 和 toByteArray 方法引用
     */
    fun buildCodec(protoClass: KClass<*>): CodecPair {
        val javaClass = protoClass.java
        val lookup = MethodHandles.lookup()

        // parseFrom(byte[]) → ProtoMsg (static method)
        val parseFromHandle = lookup.findStatic(
            javaClass,
            "parseFrom",
            MethodType.methodType(javaClass, ByteArray::class.java)
        )

        // toByteArray() → byte[] (instance method)
        val toByteArrayHandle = lookup.findVirtual(
            javaClass,
            "toByteArray",
            MethodType.methodType(ByteArray::class.java)
        )

        return CodecPair(
            parseFrom = { bytes ->
                @Suppress("UNCHECKED_CAST")
                parseFromHandle.invoke(bytes) as Any
            },
            toByteArray = { obj ->
                @Suppress("UNCHECKED_CAST")
                toByteArrayHandle.invoke(obj) as ByteArray
            }
        )
    }

    /**
     * 编解码方法引用对。
     *
     * @param parseFrom 反序列化函数，(ByteArray) -> Any
     * @param toByteArray 序列化函数，(Any) -> ByteArray
     */
    data class CodecPair(
        /** 反序列化函数，将 ByteArray 转为 Proto 消息实例 */
        val parseFrom: (ByteArray) -> Any,
        /** 序列化函数，将 Proto 消息实例转为 ByteArray */
        val toByteArray: (Any) -> ByteArray
    )

    /**
     * 从 HandlerEntry 中反序列化请求参数。
     *
     * 将 [ByteString] 转换为 [ByteArray] 后委托给 HandlerEntry.parseFrom。
     * 空载荷时调用 parseFrom(ByteArray(0)) 返回 Proto 默认实例，
     * 而非直接返回 ByteArray，避免 Handler 收到错误类型的 ClassCastException。
     *
     * @param entry Handler 注册条目
     * @param params Proto bytes 格式的请求参数
     * @return 反序列化后的请求对象
     */
    fun deserialize(entry: HandlerEntry, params: ByteString): Any {
        // 空载荷返回 Proto 默认实例，而非 ByteArray
        if (params.isEmpty) {
            return entry.parseFrom(ByteArray(0))
        }
        return entry.parseFrom(params.toByteArray())
    }

    /**
     * 通过 HandlerEntry 序列化响应结果。
     *
     * 委托给 HandlerEntry.toByteArray。
     *
     * @param entry Handler 注册条目
     * @param result 响应结果对象
     * @return 序列化后的字节数组
     */
    fun serialize(entry: HandlerEntry, result: Any): ByteArray {
        return entry.toByteArray(result)
    }
}
