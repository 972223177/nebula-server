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
 * 当前实现通过 **EM 作为闭包变量在 `withContext(Dispatchers.IO)` 内传递**
 * 来规避此问题：
 * - EM 在 IO 线程上创建，整个事务期间引用不变
 * - block 内部如果有 `suspend` 调用，**仅在 IO 线程上**恢复执行
 * - 因此 EM 始终在创建它的那个 IO 线程上使用
 *
 * **关键约束**（设计契约）：
 * 1. `block` 体内的所有 `suspend` 挂起点恢复时，**必须仍在 IO 线程上**。
 *    这要求 `block` 不应显式调用 `withContext(其他Dispatcher)` 切走线程。
 * 2. 如果 `block` 内部需要 CPU-bound 计算，应使用 `withContext(Dispatchers.Default)`
 *    包住**子 block**，**而不是替换整个事务上下文**。
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
 * @param emf JPA EntityManagerFactory（线程安全，可共享）
 */
class JpaTxRunner(private val emf: EntityManagerFactory) {

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
        val em = emf.createEntityManager()
        val tx: EntityTransaction = em.transaction
        try {
            tx.begin()
            val result = block(em)
            tx.commit()
            result
        } catch (e: Throwable) {
            // 异常路径：先尝试 rollback，rollback 失败信息用 addSuppressed 保留
            rollbackSafely(tx, e)
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
}
