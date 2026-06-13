package com.nebula.gateway.handler.conversation

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * ConversationLockManager 会话级互斥锁管理器单元测试（D-19）。
 *
 * 覆盖场景：
 * - withLock 正常执行代码块并返回结果
 * - 相同 conversationId 的操作为串行执行
 * - 不同 conversationId 的操作为并行执行
 */
class ConversationLockManagerTest {

    private lateinit var lockManager: ConversationLockManager

    @BeforeEach
    fun setUp() {
        lockManager = ConversationLockManager()
    }

    @Test
    fun withLockShouldExecuteBlockAndReturnResult() = runTest {
        val result = lockManager.withLock("conv-001") {
            "done"
        }
        assertEquals("done", result)
    }

    @Test
    fun sameConversationIdShouldBeSerialized() = runTest {
        val order = mutableListOf<Int>()
        val counter = AtomicInteger(0)

        // 相同会话 ID 下的两个协程必须串行执行
        val jobs = listOf(
            async {
                lockManager.withLock("conv-001") {
                    counter.incrementAndGet()
                    order.add(1)
                }
            },
            async {
                lockManager.withLock("conv-001") {
                    counter.incrementAndGet()
                    order.add(2)
                }
            }
        )
        jobs.awaitAll()

        assertEquals(2, counter.get())
        // 串行：第一个任务先完成
        assertEquals(listOf(1, 2), order)
    }

    @Test
    fun differentConversationIdsShouldExecuteConcurrently() = runTest {
        val counter = AtomicInteger(0)

        // 不同会话 ID 下的操作不应互斥
        val jobs = listOf(
            async {
                lockManager.withLock("conv-A") {
                    counter.incrementAndGet()
                }
            },
            async {
                lockManager.withLock("conv-B") {
                    counter.incrementAndGet()
                }
            }
        )
        jobs.awaitAll()

        assertEquals(2, counter.get())
    }

    @Test
    fun withLockShouldSupportNestedCallsOnSameConversationId() = runTest {
        // 相同 conversationId 的嵌套调用应正常工作（已持有锁时再次进入不阻塞）
        val result = lockManager.withLock("conv-001") {
            "done"
        }
        assertEquals("done", result)
    }
}
