package com.nebula.gateway.handler.sensitiveword

import com.nebula.gateway.di.register
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector

/**
 * 敏感词 Handler 收集器 — 注册下载与重载两个 Handler（D-116）。
 */
class SensitiveWordHandlerCollector(
    private val downloadHandler: SensitiveWordDownloadHandler,
    private val reloadHandler: SensitiveWordReloadHandler
) : HandlerCollector {

    override fun registerAll(registry: HandlerRegistry) {
        registry.register(downloadHandler)
        registry.register(reloadHandler)
    }
}
