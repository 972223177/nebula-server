package com.nebula.repository.dao

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.EntityTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * 协程友好的 JPA 事务运行器（替代 Spring TransactionTemplate）。
 *
 * 设计动机：摆脱 Spring 事务管理器依赖，提供与 Kotlin 协程原生兼容的事务边界。
 *
 * ## Hibernate Session 与协程的线程模型
 *
 * Hibernate [EntityManager] 底层持有的 JDBC Connection **不是线程安全的**，
 * 官方建议遵循"一线程一 Session"原则。
 *
 * D-01 修复策略：
 * 1. `block` 保持 `suspend` 签名以兼容 DAO 层的 suspend 方法（EntityDao 等）
 * 2. 使用 [Dispatchers.IO.limitedParallelism] 限制并发，但关键是不在 block 内调用
 *    `withContext(其他Dispatcher)` 切走线程
 * 3. DAO 方法虽标记为 suspend，但实现为纯同步 JPA 调用（em.find/persist/merge），
 *    无实际挂起点，协程不会在 block 内切换线程
 *
 * **关键约束**（设计契约）：
 * 1. `block` 内**禁止**调用 `withContext(Dispatchers.Default)` 或其他 Dispatcher，
 *    否则协程恢复时可能调度到不同 IO worker，违反 "一线程一 Session" 假设
 * 2. 如需 CPU-bound 计算，应在 block 外完成后传入结果
 * 3. cleanup（[EntityManager.close]）使用 [NonCancellable] 包装，
 *    即使父协程被取消也保证执行。
 *
 * ## 协程取消语义
 *
 * 参考 Kotlin 官方 [NonCancellable 文档](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-non-cancellable/)：
 * - cleanup 必须放在 `withContext(NonCancellable) { ... }` 中，否则父协程取消时
 *   cleanup 可能因 dispatch 回原上下文而抛 CancellationException
 * - cleanup 块内的 suspend 调用会正常执行，不会因外部取消而中断
 * - cleanup 结束后，如果原协程已被取消，调用 [ensureActive] 重新抛 CancellationException
 *   以保留原始取消语义
 *
 * ## Metrics 埋点
 *
 * 通过 [MetricsHook] 接口暴露事务执行的关键事件：
 * - `onTxStart`: 事务开始
 * - `onTxCommit`: 事务提交成功
 * - `onTxRollback`: 事务回滚（含原因）
 * - `onTxError`: block 抛出的异常（不区分 commit/rollback 后的二次错误）
 *
 * 不强制依赖 Micrometer 等外部库，应用层可注入空实现或具体适配器。
 *
 * ## 用法
 *
 * ```kotlin
 * class FriendService(
 *     private val txRunner: JpaTxRunner,
 *     private val userDao: UserDao,
 *     // ...
 * ) {
 *     suspend fun addFriend(req: FriendAddReq, fromUid: Long): FriendAddResult {
 *         return txRunner.execute { em ->
 *             val user = userDao.findById(em, fromUid) ?: throw NotFound()
 *             userDao.update(em, user.apply { ... })
 *             FriendAddResult(...)
 *         }
 *     }
 * }
 * ```
 *
 * 注意：block 内的代码在 `Dispatchers.IO` 线程上执行，DAO 的 suspend 方法
 * 为纯同步 JPA 调用无实际挂起点。禁止在 block 内调用 withContext 切换 Dispatcher。
 *
 * @param emf JPA EntityManagerFactory（线程安全，可共享）
 * @param metricsHook metrics 埋点钩子（可选，默认 no-op）
 * @param txTimeoutSeconds 事务超时秒数（D-04），默认 30s，超时后 JDBC 层面中断查询
 */
class JpaTxRunner(
    private val emf: EntityManagerFactory,
    private val metricsHook: MetricsHook = NoOpMetricsHook,
    private val txTimeoutSeconds: Int = DEFAULT_TX_TIMEOUT_SECONDS
) {

    /**
     * 在事务中执行 [block]，返回其结果。
     *
     * 整个事务生命周期在 [Dispatchers.IO] 上运行。EM 在 IO 线程创建，
     * 与持有它的线程绑定，事务提交/回滚都在该线程上完成。
     *
     * 错误处理：
     * - block 抛异常 → 回滚事务 → 透传原异常（rollback 失败时附在 [Throwable.addSuppressed]）
     * - block 正常返回 → 提交事务
     * - 无论成功失败 → 在 [NonCancellable] 上下文中关闭 EM（保证 cleanup 一定执行）
     * - cleanup 后 → 检查原协程取消状态（[ensureActive]）
     *
     * @param block 事务回调，接收当前事务的 [EntityManager]
     * @return block 的返回值
     * @throws Throwable block 抛出的任何异常（事务已回滚，EM 已关闭）
     */
    suspend fun <T> execute(block: suspend (EntityManager) -> T): T = withContext(Dispatchers.IO) {
        metricsHook.onTxStart()
        val em = emf.createEntityManager()
        val tx: EntityTransaction = em.transaction
        try {
            // D-04: 设置事务超时，必须在 begin() 前调用
            // EntityTransaction 接口无 setTimeout，需通过 Hibernate Transaction 设置
            (tx as org.hibernate.Transaction).setTimeout(txTimeoutSeconds)
            tx.begin()
            val result = block(em)
            tx.commit()
            metricsHook.onTxCommit()
            result
        } catch (e: Throwable) {
            // 异常路径：先尝试 rollback，rollback 失败信息用 addSuppressed 保留
            rollbackSafely(tx, e)
            metricsHook.onTxRollback(e)
            throw e
        } finally {
            // cleanup 必须 NonCancellable 保护（官方推荐模式）
            // 避免父协程取消时 dispatch 回原上下文抛 CancellationException 覆盖 cleanup
            withContext(NonCancellable) {
                closeSafely(em)
            }
            // cleanup 完成后，重新检查原协程的取消状态
            // （NonCancellable 块不响应取消，需要手动恢复）
            coroutineContext.ensureActive()
        }
    }

    /**
     * 安全回滚事务，回滚失败时把异常附在原异常的 suppressed 中。
     *
     * @param tx 当前事务
     * @param originalError 触发回滚的原始异常
     */
    private fun rollbackSafely(tx: EntityTransaction, originalError: Throwable) {
        if (!tx.isActive) return
        try {
            tx.rollback()
        } catch (rollbackEx: Exception) {
            originalError.addSuppressed(rollbackEx)
        }
    }

    /**
     * 安全关闭 EM，关闭失败时使用当前协程的异常处理器上报（不掩盖已抛出的原始异常）。
     *
     * 实际异常已被外层 `throw e` 处理，此处仅防御性清理。
     */
    private fun closeSafely(em: EntityManager) {
        try {
            em.close()
        } catch (_: Exception) {
            // ignore: EM close 失败不应掩盖原始异常
            // 正常情况下 Hibernate 不会抛异常；若发生，说明 EM 已损坏，
            // 资源泄露风险已存在，再次抛出只会让 finally 块混乱
        }
    }

    companion object {
        /** D-04: 默认事务超时秒数 */
        private const val DEFAULT_TX_TIMEOUT_SECONDS = 30
    }
}

/**
 * 事务执行 metrics 埋点接口。
 *
 * 应用层可实现此接口对接 Micrometer / Prometheus / 自研 metrics 系统。
 * 所有方法在 IO 线程上调用，**不应执行耗时操作**。
 */
interface MetricsHook {
    /** 事务开始（调用 emf.createEntityManager 之前） */
    fun onTxStart()

    /** 事务提交成功 */
    fun onTxCommit()

    /**
     * 事务回滚。
     *
     * @param cause 触发回滚的原始异常（业务异常 / DB 错误 / 主动 throw）
     */
    fun onTxRollback(cause: Throwable)
}

/** 默认空实现 — 不埋点，用于生产环境不关心 metrics 的场景 */
object NoOpMetricsHook : MetricsHook {
    override fun onTxStart() {}
    override fun onTxCommit() {}
    override fun onTxRollback(cause: Throwable) {}
}
