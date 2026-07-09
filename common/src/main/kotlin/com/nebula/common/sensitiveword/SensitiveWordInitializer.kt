package com.nebula.common.sensitiveword

import com.nebula.common.init.ModuleInitializer
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

/**
 * 敏感词模块启动初始化器 — 在 ServerBootstrap 启动时加载词库到内存（D-107）。
 *
 * 设计：
 * - 依赖 "common" 模块（需 ApplicationConfig 已就绪）
 * - init() 中阻塞加载词库（启动时一次性，可接受短暂阻塞）
 * - 优先加载内嵌资源基线（始终可用），若配置可达远程源则尝试覆盖
 * - 内嵌与远程均为空时才以空词库启动，仅打 WARN 日志，不阻断整体启动
 */
class SensitiveWordInitializer : ModuleInitializer, KoinComponent {

    override val name: String = "sensitive-word"

    override val dependencies: List<String> = listOf("common")

    override fun init() {
        val service = get<SensitiveWordService>()
        val ok = runBlocking { service.loadAtStartup() }
        if (ok) {
            logger.info { "敏感词库启动加载完成: 词数=${service.wordCount} version=${service.version}" }
        } else {
            logger.warn { "敏感词库启动加载失败（内嵌资源与远程源均为空），以空词库启动，检测能力降级" }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
