package com.nebula.gateway.di

import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.sensitiveword.SensitiveWordDownloadHandler
import com.nebula.gateway.handler.sensitiveword.SensitiveWordHandlerCollector
import com.nebula.gateway.handler.sensitiveword.SensitiveWordReloadHandler
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * 敏感词 Handler Koin 模块 — 注册敏感词下载与重载 Handler 及其 Collector（D-115）。
 */
val sensitiveWordHandlerModule = module {
    single { SensitiveWordDownloadHandler(get()) }
    single { SensitiveWordReloadHandler(get(), get()) }

    single<HandlerCollector>(named("sensitive-word")) {
        SensitiveWordHandlerCollector(get(), get())
    }
}
