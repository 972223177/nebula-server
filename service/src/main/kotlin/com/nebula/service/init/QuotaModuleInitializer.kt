package com.nebula.service.init

import com.nebula.common.init.ModuleInitializer
import com.nebula.service.external.QuotaManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking

/**
 * 外部服务配额模块初始化器（D-XX）。
 *
 * 通过项目既有 [ModuleInitializer] 机制在启动/关闭时管理 [QuotaManager] 生命周期，
 * 避免 server 层反向依赖 service 层（遵守 common←repository←service←gateway←server 单向依赖）：
 * - init()：调用 QuotaManager.start()（Redis 主路径；仅 Redis 降级态启动文件刷盘协程）
 * - shutdown()：调用 QuotaManager.flush()（优雅落盘）+ stop()（取消自有协程作用域）
 *
 * 由 ServiceKoinModule 以 `single<ModuleInitializer>` 注册，经 ServerBootstrap.initializeModules
 * 拓扑排序后自动 init()，经 executeShutdown 逆序 shutdown()（若未覆盖则由 Koin 关闭兜底）。
 *
 * @param quotaManager 配额管理器（由 Koin 注入）
 */
class QuotaModuleInitializer(
    private val quotaManager: QuotaManager
) : ModuleInitializer {

    private val log = KotlinLogging.logger {}

    /** 初始化器名称（用于拓扑排序日志与回滚定位） */
    override val name: String = "external-quota"

    /**
     * 依赖关系：配额管理器需使用 Redis 连接，须等待 repository 模块（Redis 客户端）就绪。
     */
    override val dependencies: List<String> = listOf("repository")

    /**
     * 启动配额管理器。
     */
    override fun init() {
        log.info { "初始化外部服务配额管理器" }
        quotaManager.start()
    }

    /**
     * 关闭配额管理器：先刷盘（文件降级态落盘）再停止协程作用域。
     */
    override fun shutdown() {
        log.info { "关闭外部服务配额管理器" }
        runBlocking { quotaManager.flush() }
        quotaManager.stop()
    }
}
