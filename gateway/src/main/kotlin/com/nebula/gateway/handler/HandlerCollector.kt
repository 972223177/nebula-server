package com.nebula.gateway.handler

import com.nebula.gateway.dispatcher.HandlerRegistry

/**
 * Handler 收集器接口 — 按业务分组注册 Handler 到 HandlerRegistry。
 *
 * 每个业务包实现一个 Collector，通过 Koin 的 getAll() 自动发现和注入。
 * 新增 Handler 只需在对应的 Collector 中添加一行 registry.register() 调用，
 * 无需修改 GatewayModule.kt 或 NebulaServer.kt。
 */
interface HandlerCollector {

    /**
     * 将本组所有 Handler 注册到 [HandlerRegistry]。
     *
     * @param registry HandlerRegistry 实例
     */
    fun registerAll(registry: HandlerRegistry)
}
