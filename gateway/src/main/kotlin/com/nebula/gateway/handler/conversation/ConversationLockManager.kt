package com.nebula.gateway.handler.conversation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 会话级互斥锁管理器（D-19）。
 *
 * 按 conversationId 粒度提供互斥锁，确保同一会话的并发操作（如邀请成员、退群、踢人）串行执行，
 * 避免 memberCount 读写竞争。不同会话之间的操作不互斥。
 *
 * 用法：
 * ```
 * conversationLockManager.withLock(conversationId) {
 *     transactionTemplate.execute {
 *         // 事务内的成员操作
 *     }
 * }
 * ```
 */
class ConversationLockManager {
    /** 按 conversationId 分片的 Mutex 集合 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 在指定会话的互斥锁保护下执行代码块。
     *
     * 使用 computeIfAbsent 惰性创建 Mutex（无锁竞争时无需创建新 Mutex）。
     * 必须先获取锁再执行事务，保证事务在锁内提交（D-19）。
     *
     * @param conversationId 会话 ID
     * @param block 在锁保护下执行的挂起代码块
     * @return 代码块的返回值
     */
    suspend fun <T> withLock(conversationId: String, block: suspend () -> T): T {
        val mutex = locks.computeIfAbsent(conversationId) { Mutex() }
        return mutex.withLock { block() }
    }
}
