package com.nebula.gateway.admin

import com.nebula.service.admin.DeadLetterService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 死信补偿定时任务（D-76）。
 *
 * 每隔 10 分钟扫描一次 pending 状态的死信记录，调用 [DeadLetterService.compensate]
 * 尝试重新入队。使用 CoroutineScope 控制生命周期，支持 start()/stop() 管理。
 *
 * @param deadLetterService 死信服务
 * @param scope 协程作用域（使用 sendHandlerScope，IO 调度器 + SupervisorJob）
 */
class DeadLetterCompensator(
    private val deadLetterService: DeadLetterService,
    private val scope: CoroutineScope
) {

    companion object {
        private val logger = KotlinLogging.logger {}

        /** 补偿间隔：10 分钟（单位毫秒） */
        private const val COMPENSATE_INTERVAL_MS = 600_000L
    }

    /** 补偿任务的 Job 引用，用于取消 */
    private var compensationJob: Job? = null

    /**
     * 启动补偿定时任务。
     *
     * 在协程中循环执行，每次执行后等待 10 分钟再次扫描。
     * 通过 isActive 检查支持优雅停止。
     */
    fun start() {
        if (compensationJob?.isActive == true) {
            logger.warn { "死信补偿任务已在运行" }
            return
        }
        compensationJob = scope.launch {
            logger.info { "死信补偿任务已启动，间隔=${COMPENSATE_INTERVAL_MS}ms" }
            while (isActive) {
                try {
                    val count = deadLetterService.compensate()
                    if (count > 0) {
                        logger.info { "死信补偿处理了 $count 条记录" }
                    }
                } catch (e: Exception) {
                    logger.error(e) { "死信补偿执行异常" }
                }
                delay(COMPENSATE_INTERVAL_MS)
            }
        }
    }

    /**
     * 停止补偿定时任务。
     */
    fun stop() {
        compensationJob?.cancel()
        compensationJob = null
        logger.info { "死信补偿任务已停止" }
    }
}
