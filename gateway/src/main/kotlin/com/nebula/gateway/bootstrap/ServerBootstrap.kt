package com.nebula.gateway.bootstrap

import com.nebula.common.init.ModuleInitializer
import com.nebula.common.init.topologicalSort
import com.nebula.gateway.di.gatewayModules
import com.nebula.gateway.service.ChatService
import com.nebula.gateway.session.SessionRegistry
import com.nebula.service.init.ServiceInitModule
import com.nebula.service.sequence.SeqService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.lettuce.core.api.StatefulRedisConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import javax.sql.DataSource

/**
 * 服务启动引导器 — 封装所有跨层启动逻辑，供 server 层通过 gateway API 统一调用（D-28）。
 *
 * 职责：
 * - 聚合所有层的 Koin 模块定义（通过 ServiceInitModule 间接引用 repository 模块）
 * - 执行 ModuleInitializer 发现、拓扑排序、初始化和回滚（CQ-09）
 * - 执行 Redis 序列号恢复（D-81/H21）— 委托 SeqService
 * - 配置死信桥接回调 — 通过 Koin 解析 DeadLetterCallback
 * - 获取 ChatService — 由 Koin 管理
 * - 执行资源关闭
 */
object ServerBootstrap {

    private val logger = KotlinLogging.logger {}

    /**
     * 所有 Koin 模块列表 — 通过 ServiceInitModule 间接聚合 common/repository/service 三层。
     *
     * server 层只需 import ServiceInitModule，无需直接依赖 repository 模块（D-28）。
     */
    val koinModules: List<Module> = ServiceInitModule.allModules + gatewayModules

    /**
     * 执行模块初始化流程。
     *
     * 流程：
     * 1. 从 Koin 容器发现所有 [ModuleInitializer] 实现
     * 2. 按依赖拓扑排序
     * 3. 依次执行 init()，追踪已初始化列表
     * 4. 失败时逆序回滚已初始化的模块（CQ-09）
     * 5. 创建 eager instances
     *
     * @param koin Koin 容器实例
     * @throws IllegalStateException 当模块初始化失败时抛出
     */
    fun initializeModules(koin: Koin) {
        val initializers: List<ModuleInitializer> = koin.getAll()
        val sortedInitializers = initializers.topologicalSort()

        val initialized = mutableListOf<ModuleInitializer>()
        try {
            sortedInitializers.forEach { init ->
                init.init()
                initialized.add(init)
            }
        } catch (e: Exception) {
            logger.error(e) { "模块初始化失败: ${(initialized.lastOrNull()?.name ?: "unknown")}，开始逆序回滚 ${initialized.size} 个已初始化模块" }
            initialized.reversed().forEach { init ->
                try {
                    init.shutdown()
                    logger.info { "已回滚模块: ${init.name}" }
                } catch (t: Throwable) {
                    logger.error(t) { "回滚模块 ${init.name} 失败（资源可能已泄露）" }
                }
            }
            throw IllegalStateException("服务器初始化失败: ${e.message}", e)
        }

        koin.createEagerInstances()
    }

    /**
     * 从 MySQL 恢复 Redis 序列号（D-81/H21，D-28 重构版）。
     *
     * 委托 SeqService.recoverSequences() 执行，通过闭包提供
     * Conversations/Message/Member 数据（从 service 层获取，避免直接访问 repository）。
     *
     * 修复（2026-06-20）：原 conversationSupplier 返回 emptyList() 导致序列号恢复完全失效。
     * 改为调用 ConversationService.getAllActiveConversations() 分页扫描所有未解散会话。
     *
     * @param koin Koin 容器实例
     */
    fun recoverSequences(koin: Koin) {
        runBlocking {
            val seqService = koin.get<SeqService>()
            val conversationService: com.nebula.service.conversation.ConversationService = koin.get()
            val messageService: com.nebula.service.chat.MessageService = koin.get()

            seqService.recoverSequences(
                conversationSupplier = {
                    // 分页扫描所有未解散会话（status=0），由 Service 层控制批次大小
                    conversationService.getAllActiveConversations()
                },
                msgCountByConv = { convId ->
                    with(kotlinx.coroutines.Dispatchers.IO) {
                        messageService.countByConversationId(convId)
                    }
                },
                memberSupplier = { convId ->
                    conversationService.getConversationMembers(convId).map { it.userId }
                }
            )
        }
    }

    /**
     * 重启后从 Redis 恢复设备类型映射索引（D-05, AUTH-05）。
     *
     * 服务重启后内存 deviceTypeIndex 为空，需从 Redis 中仍存在的
     * session:{userId}:{deviceType} 映射重建，确保同类型设备互踢在重启后仍工作。
     * 委托 [SessionRegistry.recoverDeviceTypeIndex] 执行。
     *
     * 应在接受连接前（构造 ChatService 之前）调用，使互踢在重启瞬间即生效。
     *
     * @param koin Koin 容器实例
     */
    fun recoverDeviceTypeIndex(koin: Koin) {
        runBlocking {
            val sessionRegistry = koin.get<SessionRegistry>()
            val recovered = sessionRegistry.recoverDeviceTypeIndex()
            logger.info { "设备类型映射索引恢复完成，共 $recovered 条" }
        }
    }

    /**
     * 注入死信创建回调（M11，D-28 重构版）。
     *
     * 通过 Koin 解析 [com.nebula.common.init.DeadLetterCallback]，
     * 设置到 MessageRepositoryImpl 的 onDeadLetter 回调。
     * 回调解析通过 repository 模块内注册的 bean 完成。
     *
     * @param koin Koin 容器实例
     */
    fun setupDeadLetterBridge(koin: Koin) {
        // DeadLetterCallback 由 serviceKoinModule 注册为 single<DeadLetterCallback> { get<DeadLetterService>() }
        // MessageRepositoryImpl 由 RepositoryModuleInitializer 创建并声明
        // onDeadLetter 赋值在 RepositoryModuleInitializer.init() 中完成
        // 此处仅做日志记录，实际桥接在模块初始化阶段已完成
        logger.info { "死信桥接就绪（通过 DeadLetterCallback 接口，D-28）" }
    }

    /**
     * 从 Koin 容器获取 ChatService 实例（D-28 重构版）。
     *
     * ChatService 已由 frameworkModule 注册为 Koin 单例，
     * 通过构造函数注入所有依赖，无需 ServerBootstrap 手动构造。
     *
     * @param koin Koin 容器实例
     * @return 已构造好的 ChatService 实例
     */
    fun createChatService(koin: Koin): ChatService = koin.get()

    /**
     * 执行服务关闭操作 — 按依赖逆序释放资源。
     *
     * 关闭顺序：
     * 1. 关闭 Redis 连接
     * 2. 关闭数据库连接池
     *
     * @param koin Koin 容器实例
     */
    fun executeShutdown(koin: Koin) {
        // 2026-07 review R2（F2 加固）：先取消服务级后台协程作用域，再释放底层资源。
        // serverScope 承载延迟离线、死信补偿、设备类型清理、在线状态变更推送等跨连接存活任务；
        // Koin 默认 stopKoin() 不会自动 cancel 用户自定义的 CoroutineScope，需在此兜底释放。
        // 放在 Redis/DB 关闭之前，确保关闭瞬间的在途后台任务先被取消，避免其访问已关闭的连接而报错。
        try {
            koin.get<CoroutineScope>(named("serverScope")).cancel()
            logger.info { "serverScope 已取消" }
        } catch (e: Exception) {
            logger.error(e) { "取消 serverScope 失败" }
        }

        try {
            val redisConn = koin.get<StatefulRedisConnection<String, String>>()
            redisConn.close()
            logger.info { "Redis 连接已关闭" }
        } catch (e: Exception) {
            logger.error(e) { "关闭 Redis 连接失败" }
        }

        try {
            val ds = koin.get<DataSource>()
            (ds as? AutoCloseable)?.close()
            logger.info { "数据库连接池已关闭" }
        } catch (e: Exception) {
            logger.error(e) { "关闭数据库连接池失败" }
        }
    }
}
