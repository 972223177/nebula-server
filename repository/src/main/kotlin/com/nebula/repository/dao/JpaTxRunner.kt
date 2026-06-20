package com.nebula.repository.dao

import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.EntityTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 协程友好的 JPA 事务运行器（替代 Spring TransactionTemplate）。
 *
 * 设计动机：摆脱 Spring 事务管理器依赖，提供与 Kotlin 协程原生兼容的事务边界。
 *
 * ## 协程线程切换安全
 *
 * `withContext(Dispatchers.IO) { txRunner.execute { block() } }` 中：
 * - 整个 execute 块在 [Dispatchers.IO] 调度器的某个 IO 线程上执行
 * - [EntityManager] 实例是闭包内的局部变量，跨挂起调用时引用不变
 * - 协程挂起恢复后，EM 仍指向同一实例，同一 Connection，同一事务
 * - 关键：[EntityManager] 本身不绑定 ThreadLocal，是引用传递，与协程语义一致
 *
 * 实际场景下 `Dispatchers.IO` 弹性线程池可能切换线程，但**不切换 EM 实例**。
 * Hibernate 的事务状态存储在 EM 内部（EntityTransaction），与线程无关。
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
 *             val user = em.find(UserEntity::class.java, fromUid)
 *             // ... 业务逻辑，可调用其他 suspend 函数
 *             em.persist(...)
 *             FriendAddResult(...)
 *         }
 *     }
 * }
 * ```
 *
 * @param emf JPA EntityManagerFactory
 */
class JpaTxRunner(private val emf: EntityManagerFactory) {

    /**
     * 在事务中执行 [block]，返回其结果。
     *
     * 整个块在 [Dispatchers.IO] 调度器上执行，避免阻塞调用者协程。
     * 异常时自动回滚，成功时自动提交。
     *
     * @param block 事务回调，接收当前事务的 [EntityManager]
     * @return block 的返回值
     * @throws Throwable block 抛出的任何异常（事务已回滚）
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
            // 防御性 rollback：rollback() 在非 active 状态下静默忽略
            if (tx.isActive) {
                try {
                    tx.rollback()
                } catch (rollbackEx: Exception) {
                    // rollback 失败仅记录，不掩盖原始异常
                    throw e
                }
            }
            throw e
        } finally {
            em.close()
        }
    }
}
