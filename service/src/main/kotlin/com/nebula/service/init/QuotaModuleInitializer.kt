package com.nebula.service.init

import com.nebula.common.init.ModuleInitializer
import com.nebula.service.external.QuotaManager
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.get

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
 * 设计决策（时序修复）：
 * [QuotaManager] 依赖 `StatefulRedisConnection`，而该连接由 [com.nebula.repository.init.RepositoryModuleInitializer.init]
 * 在运行时通过 `koin.declare(...)` 动态注册，并非静态 `single {}` 定义。
 * 若在本类构造函数注入 QuotaManager，会在 ServerBootstrap 收集阶段
 * （`koin.getAll<ModuleInitializer>()`）被急切实例化，而彼时 repository 的 init() 尚未执行，
 * Redis 连接还未 declare，导致 `NoDefinitionFoundException`（先有鸡先有蛋）。
 * 因此改为在 init() 阶段（此时 dependencies=["repository"] 已保证 repository 先初始化）
 * 通过 KoinComponent 懒解析 QuotaManager，并缓存供 shutdown 复用。
 */
class QuotaModuleInitializer : ModuleInitializer, KoinComponent {

    private val log = KotlinLogging.logger {}

    /** init() 阶段解析并缓存的 QuotaManager 实例，供 shutdown 复用；未初始化时为 null */
    private var quotaManager: QuotaManager? = null

    /** 初始化器名称（用于拓扑排序日志与回滚定位） */
    override val name: String = "external-quota"

    /**
     * 依赖关系：配额管理器需使用 Redis 连接，须等待 repository 模块（Redis 客户端）就绪。
     */
    override val dependencies: List<String> = listOf("repository")

    /**
     * 启动配额管理器。
     *
     * 延迟到此处解析 [QuotaManager]：拓扑排序保证 repository 模块已 init()，
     * Redis 连接（[io.lettuce.core.api.StatefulRedisConnection]）此刻已就绪。
     */
    override fun init() {
        log.info { "初始化外部服务配额管理器" }
        val manager = get<QuotaManager>().also { quotaManager = it }
        manager.start()
    }

    /**
     * 关闭配额管理器：先刷盘（文件降级态落盘）再停止协程作用域。
     */
    override fun shutdown() {
        log.info { "关闭外部服务配额管理器" }
        val manager = quotaManager ?: return
        runBlocking { manager.flush() }
        manager.stop()
    }
}
