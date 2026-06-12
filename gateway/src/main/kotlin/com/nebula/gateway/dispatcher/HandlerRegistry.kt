package com.nebula.gateway.dispatcher

import java.util.concurrent.ConcurrentHashMap

/**
 * Handler 注册中心 — 线程安全的 method→Handler 映射表（D-11）。
 *
 * 使用 [ConcurrentHashMap] 保证并发安全，支持注册和查询操作。
 * 注册时使用 [putIfAbsent] 防止相同 method 被重复注册（Pitfall 4）。
 */
class HandlerRegistry {

    private val registry = ConcurrentHashMap<String, HandlerEntry>()

    /**
     * 注册 Handler 到指定 method 路由。
     *
     * [putIfAbsent] 保证原子性：如果 method 已存在，抛出 [IllegalStateException]。
     *
     * @param entry Handler 注册条目
     * @throws IllegalStateException 如果 method 已注册
     */
    fun register(entry: HandlerEntry) {
        check(registry.putIfAbsent(entry.handler.method, entry) == null) {
            "Duplicate method: ${entry.handler.method}"
        }
    }

    /**
     * 根据 method 查询 Handler 条目。
     *
     * @param method 路由标识字符串
     * @return HandlerEntry 或 null（未注册）
     */
    fun get(method: String): HandlerEntry? = registry[method]
}
