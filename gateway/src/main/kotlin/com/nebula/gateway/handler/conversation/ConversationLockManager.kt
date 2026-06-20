package com.nebula.gateway.handler.conversation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 会话级互斥锁管理器（D-19）。
 *
 * 按 conversationId 粒度提供互斥锁，确保同一会话的并发操作（如邀请成员、退群、踢人）串行执行，
 * 避免 memberCount 读写竞争。不同会话之间的操作不互斥。
 *
 * ## 锁身份稳定性（修复 2026-06-20）
 *
 * 早期实现 finally 中无条件 `locks.remove(conversationId)`，存在**锁身份漂移**问题：
 * 1. A 进入 withLock → computeIfAbsent 创建 Mutex M1
 * 2. A 持锁期间 finally 块中先执行 locks.remove（错误！应在释放锁之后）
 * 3. B 进入 withLock → computeIfAbsent 创建新 Mutex M2
 * 4. B 持有的 M2 与 A 的 M1 不是同一个对象，互不阻塞 → 同一会话并发执行
 *
 * 实际 finally 中 `mutex.withLock { block() }` 在 block 返回后释放锁，再 remove。
 * 但仍存在窗口：A 已释放锁并 remove，B 拿到新 Mutex 立即进入 while C 在锁中。
 * B 与 C 持不同 Mutex，互相不阻塞。
 *
 * **正确方案**：完全不删除 Mutex 实例。
 * - Kotlin Mutex 不支持 `isLocked` 查询，无法安全判断"锁已释放且无等待者"
 * - 每个 Mutex 仅 ~48 字节 + 字符串键 ~50 字节 = ~100 字节/会话
 * - 10 万活跃会话 ≈ 10MB，可接受
 * - 长期运行的"死会话"会累积，但会话 ID 是 UUID，重用概率极低
 *
 * 用法：
 * ```
 * conversationLockManager.withLock(conversationId) {
 *     // 调用 Service 层方法，事务由 Service 内部 JpaTxRunner 管理
 *     conversationService.inviteMember(req, operatorUid)
 * }
 * ```
 */
class ConversationLockManager {
    /** 按 conversationId 分片的 Mutex 集合，键不会移除以保证锁身份稳定 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * 当前活跃会话数（监控指标，调试用）。
     *
     * 等价于 locks.size，用于运维排查"会话锁数量是否异常增长"。
     */
    val activeLockCount: Int
        get() = locks.size

    /**
     * 在指定会话的互斥锁保护下执行代码块。
     *
     * 使用 computeIfAbsent 惰性创建 Mutex（无锁竞争时无需创建新 Mutex）。
     * **不再 finally 中 remove**——见类注释中的"锁身份稳定性"。
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
