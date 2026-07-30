package com.nebula.gateway.fanout

import com.nebula.common.init.ModuleInitializer
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlinx.coroutines.runBlocking

/**
 * fan-out 消费者启动器（§七 Durable Outbox）。
 *
 * 依赖 repository 模块（[RedisStreamQueue] 通用端口定义在 common，实现由 RepositoryModuleInitializer 经 koin.declare 注册），
 * 在其之后启动 [FanoutWorker] 消费循环。worker 跑在 serverScope，关闭由 ServerBootstrap 统一取消。
 */
class FanoutModuleInitializer : ModuleInitializer, KoinComponent {

    override val name = "fanout"

    override val dependencies = listOf("repository")

    override fun init() {
        runBlocking { get<FanoutWorker>().start() }
    }
}
