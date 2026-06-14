package com.nebula.gateway.admin

import com.nebula.service.admin.DeadLetterService
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * DeadLetterCompensator 单元测试（Phase 10, D-76）。
 *
 * 覆盖场景：
 * - 启动补偿循环后，经过一个间隔周期应调用 compensate()
 * - 停止补偿后，不再调用 compensate()
 * - 多次 start() 不重复启动协程
 * - 先停止再重启，补偿循环重新正常工作
 * - compensate() 抛出异常后，下一个周期仍继续执行
 *
 * 使用 StandardTestDispatcher + advanceTimeBy 控制时间，避免真实等待 10 分钟。
 */
class DeadLetterCompensatorTest {

    private lateinit var deadLetterService: DeadLetterService
    private lateinit var scope: CoroutineScope
    private lateinit var compensator: DeadLetterCompensator

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        deadLetterService = mockk<DeadLetterService>(relaxed = true)
        scope = CoroutineScope(dispatcher)
        compensator = DeadLetterCompensator(deadLetterService, scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        clearMocks(deadLetterService)
    }

    @Test
    fun startShouldLaunchCompensationLoop() = runTest(dispatcher.scheduler) {
        // 执行
        compensator.start()
        advanceTimeBy(600_001)

        // 验证：10 分钟周期内应调用 compensate() 一次
        coVerify(exactly = 1) { deadLetterService.compensate() }

        // 清理
        compensator.stop()
    }

    @Test
    fun stopShouldCancelJob() = runTest(dispatcher.scheduler) {
        // 执行
        compensator.start()
        compensator.stop()
        advanceTimeBy(600_001)

        // 验证：停止后不应再调用 compensate()
        coVerify(exactly = 0) { deadLetterService.compensate() }
    }

    @Test
    fun multipleStartShouldNotDuplicate() = runTest(dispatcher.scheduler) {
        // 执行：连续调用两次 start()
        compensator.start()
        compensator.start()
        advanceTimeBy(600_001)

        // 验证：compensate() 应只调用一次（不重复启动协程）
        coVerify(exactly = 1) { deadLetterService.compensate() }

        // 清理：停止补偿任务，防止 runTest 等待协程完成
        compensator.stop()
    }

    @Test
    fun stopAndRestartShouldWork() = runTest(dispatcher.scheduler) {
        // 执行：开始 → 停止 → 重新开始
        compensator.start()
        compensator.stop()
        compensator.start()
        advanceTimeBy(600_001)

        // 验证：重新启动后应正常调用 compensate()
        coVerify(exactly = 1) { deadLetterService.compensate() }

        // 清理
        compensator.stop()
    }

    @Test
    fun compensateExceptionShouldNotCrashLoop() = runTest(dispatcher.scheduler) {
        // 准备：compensate() 每次调用都抛出异常
        coEvery { deadLetterService.compensate() } throws RuntimeException("数据库连接超时")

        // 执行：经过两个周期的时长
        compensator.start()
        advanceTimeBy(1_200_001)

        // 验证：即使第一次抛异常，循环仍继续，第二次仍会调用
        coVerify(exactly = 2) { deadLetterService.compensate() }

        // 清理
        compensator.stop()
    }
}
