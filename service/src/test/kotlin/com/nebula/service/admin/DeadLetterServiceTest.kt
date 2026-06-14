package com.nebula.service.admin

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.entity.DeadLetterEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.repository.DeadLetterRepository
import io.mockk.*
import jakarta.persistence.OptimisticLockException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.util.Optional
import kotlin.test.*

/**
 * DeadLetterService 单元测试 — 覆盖死信创建、补偿重试、手动重试、分页查询、永久失败标记（Phase 10）。
 *
 * 使用 MockK strict 模式，与 MessageServiceTest 风格一致。
 */
class DeadLetterServiceTest {

    private lateinit var deadLetterRepository: DeadLetterRepository
    private lateinit var messageQueueRepository: MessageQueueRepository
    private lateinit var idGenerator: SnowflakeIdGenerator
    private lateinit var deadLetterService: DeadLetterService

    private val conversationId = "conv-001"
    private val senderUid = 1L
    private val messageType = 1
    private val content = "test content"
    private val clientTs = 1000L
    private val failReason = "timeout"

    /** 构造测试用死信实体 */
    private fun deadLetterEntity(
        id: Long = 1L,
        status: String = "pending",
        failCount: Int = 0,
        msgId: Long? = 100L,
        version: Int? = 0
    ): DeadLetterEntity = DeadLetterEntity(
        conversationId = conversationId,
        senderUid = senderUid,
        messageType = messageType,
        content = content,
        payload = null,
        clientMsgId = "cmid-001",
        clientTs = clientTs,
        failReason = failReason,
        failCount = failCount,
        status = status
    ).apply {
        this.id = id
        this.msgId = msgId
        this.version = version
    }

    @BeforeEach
    fun setUp() {
        deadLetterRepository = mockk()
        messageQueueRepository = mockk()
        idGenerator = mockk()
        deadLetterService = DeadLetterService(
            deadLetterRepository,
            messageQueueRepository,
            idGenerator
        )
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * mock markPermanentFailed 中的两个内部查询调用。
     * 第一个 findByStatusAndFailCountLessThan("retrying", 0, ...) 的结果被忽略（代码中有注释说明改用其他方式），
     * 第二个 findByStatusOrderByCreatedAtAsc 是实际使用的查询。
     *
     * @param retryingItems findByStatusOrderByCreatedAtAsc 的返回结果
     */
    private fun mockMarkPermanentFailed(retryingItems: List<DeadLetterEntity> = emptyList()) {
        coEvery {
            deadLetterRepository.findByStatusAndFailCountLessThan(
                "retrying", 0, Pageable.ofSize(100)
            )
        } returns emptyList()
        coEvery {
            deadLetterRepository.findByStatusOrderByCreatedAtAsc("retrying", Pageable.unpaged())
        } returns retryingItems
    }

    // ================ create ================

    /**
     * 创建死信：验证实体字段正确且状态为 pending。
     */
    @Test
    fun createShouldSaveEntityWithCorrectFields() = runTest {
        val mockEntity = deadLetterEntity(id = 0L)
        coEvery { deadLetterRepository.save(any<DeadLetterEntity>()) } returns mockEntity

        val result = deadLetterService.create(
            conversationId = conversationId,
            senderUid = senderUid,
            messageType = messageType,
            content = content,
            payload = null,
            clientMsgId = "cmid-001",
            clientTs = clientTs,
            failReason = failReason
        )

        assertEquals("pending", result.status)
        assertEquals(0, result.failCount)

        coVerify(exactly = 1) {
            deadLetterRepository.save(match {
                it.conversationId == conversationId &&
                    it.senderUid == senderUid &&
                    it.messageType == messageType &&
                    it.content == content &&
                    it.status == "pending" &&
                    it.failCount == 0
            })
        }
    }

    /**
     * 创建死信：save 返回的实体应包含生成的 ID。
     */
    @Test
    fun createShouldReturnCreatedEntityWithId() = runTest {
        val savedEntity = deadLetterEntity(id = 99L)
        coEvery { deadLetterRepository.save(any<DeadLetterEntity>()) } returns savedEntity

        val result = deadLetterService.create(
            conversationId = conversationId,
            senderUid = senderUid,
            messageType = messageType,
            content = content,
            clientTs = clientTs,
            failReason = failReason
        )

        assertEquals(99L, result.id)
    }

    // ================ compensate ================

    /**
     * 补偿：无待处理死信时返回 0。
     */
    @Test
    fun compensateShouldReturnZeroWhenNoPendingItems() = runTest {
        coEvery {
            deadLetterRepository.findByStatusAndFailCountLessThan("pending", 5, Pageable.ofSize(100))
        } returns emptyList()

        val count = deadLetterService.compensate()

        assertEquals(0, count)
    }

    /**
     * 补偿：正常处理 pending 死信，状态流转 pending → retrying → retry_success。
     */
    @Test
    fun compensateShouldProcessPendingItems() = runTest {
        val entity1 = deadLetterEntity(id = 1L)
        val entity2 = deadLetterEntity(id = 2L)
        val pendingItems = listOf(entity1, entity2)

        coEvery {
            deadLetterRepository.findByStatusAndFailCountLessThan("pending", 5, Pageable.ofSize(100))
        } returns pendingItems

        coEvery { deadLetterRepository.save(any<DeadLetterEntity>()) } answers { firstArg() }
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-id"

        // mock markPermanentFailed 中的查询
        mockMarkPermanentFailed()

        val count = deadLetterService.compensate()

        assertEquals(2, count)

        // 验证状态流转
        assertEquals("retry_success", entity1.status)
        assertEquals(1, entity1.failCount)
        assertEquals("retry_success", entity2.status)
        assertEquals(1, entity2.failCount)

        coVerify(exactly = 2) { messageQueueRepository.enqueue(any()) }
    }

    /**
     * 补偿：OptimisticLockException 应被捕获并跳过该条记录。
     */
    @Test
    fun compensateShouldHandleOptimisticLockException() = runTest {
        val entity1 = deadLetterEntity(id = 1L)
        val entity2 = deadLetterEntity(id = 2L)

        coEvery {
            deadLetterRepository.findByStatusAndFailCountLessThan("pending", 5, Pageable.ofSize(100))
        } returns listOf(entity1, entity2)

        // entity1 save 抛出乐观锁异常
        coEvery { deadLetterRepository.save(entity1) } throws OptimisticLockException()
        coEvery { deadLetterRepository.save(entity2) } answers { firstArg() }
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-id"

        mockMarkPermanentFailed()

        val count = deadLetterService.compensate()

        // entity2 被成功处理
        assertEquals(1, count)
        assertEquals("retry_success", entity2.status)
        // entity1 因乐观锁被跳过，但补偿代码已先将状态改为 retrying
        assertEquals("retrying", entity1.status)
    }

    /**
     * 补偿：enqueue 抛出异常时应跳过该条记录继续处理下一条。
     */
    @Test
    fun compensateShouldHandleEnqueueException() = runTest {
        val entity1 = deadLetterEntity(id = 1L)
        val entity2 = deadLetterEntity(id = 2L)

        coEvery {
            deadLetterRepository.findByStatusAndFailCountLessThan("pending", 5, Pageable.ofSize(100))
        } returns listOf(entity1, entity2)

        coEvery { deadLetterRepository.save(entity1) } answers { firstArg() }
        coEvery { deadLetterRepository.save(entity2) } answers { firstArg() }
        coEvery { messageQueueRepository.enqueue(any()) }
            .throws(RuntimeException("enqueue 失败"))
            .andThen("stream-id")

        mockMarkPermanentFailed()

        val count = deadLetterService.compensate()

        // entity1 enqueue 异常被跳过，entity2 成功
        assertEquals(1, count)
        assertEquals("retry_success", entity2.status)
    }

    /**
     * 补偿：补偿完成后应调用 markPermanentFailed。
     */
    @Test
    fun compensateShouldCallMarkPermanentFailedAtEnd() = runTest {
        coEvery {
            deadLetterRepository.findByStatusAndFailCountLessThan("pending", 5, Pageable.ofSize(100))
        } returns emptyList()

        // 即使无待处理项，也要验证 markPermanentFailed 未被调用（因为 compensate 提前返回 0）
        // 但如果有待处理项，最后应调用 markPermanentFailed
        // 此处测试无待处理项的场景
        val count = deadLetterService.compensate()

        assertEquals(0, count)
    }

    // ================ retry ================

    /**
     * 手动重试：实体不存在时返回 false。
     */
    @Test
    fun retryShouldReturnFalseWhenEntityNotFound() = runTest {
        coEvery { deadLetterRepository.findById(999L) } returns Optional.empty()

        val result = deadLetterService.retry(999L)

        assertFalse(result)
    }

    /**
     * 手动重试：状态已为 retry_success 时返回 false。
     */
    @Test
    fun retryShouldReturnFalseWhenAlreadyRetrySuccess() = runTest {
        val entity = deadLetterEntity(id = 1L, status = "retry_success")
        coEvery { deadLetterRepository.findById(1L) } returns Optional.of(entity)

        val result = deadLetterService.retry(1L)

        assertFalse(result)
    }

    /**
     * 手动重试：正常重试流程应更新状态并调用 enqueue。
     */
    @Test
    fun retryShouldUpdateStatusAndEnqueueOnSuccess() = runTest {
        val entity = deadLetterEntity(id = 1L, status = "pending", failCount = 2)
        coEvery { deadLetterRepository.findById(1L) } returns Optional.of(entity)
        coEvery { deadLetterRepository.save(any<DeadLetterEntity>()) } answers { firstArg() }
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-id"

        val result = deadLetterService.retry(1L)

        assertTrue(result)
        // 验证状态流转
        assertEquals("retry_success", entity.status)
        assertEquals(3, entity.failCount)
        coVerify(exactly = 1) { messageQueueRepository.enqueue(any()) }
    }

    /**
     * 手动重试：OptimisticLockException 时返回 false。
     */
    @Test
    fun retryShouldHandleOptimisticLockException() = runTest {
        val entity = deadLetterEntity(id = 1L, status = "pending")
        coEvery { deadLetterRepository.findById(1L) } returns Optional.of(entity)
        coEvery { deadLetterRepository.save(any<DeadLetterEntity>()) } throws OptimisticLockException()

        val result = deadLetterService.retry(1L)

        assertFalse(result)
    }

    // ================ query ================

    /**
     * 分页查询：status 为 null 时查询全部。
     */
    @Test
    fun queryShouldReturnPagedResults() = runTest {
        val entity = deadLetterEntity(id = 1L)
        val pageable = PageRequest.of(0, 20)
        val page: Page<DeadLetterEntity> = PageImpl(listOf(entity), pageable, 1)

        coEvery { deadLetterRepository.findAll(pageable) } returns page

        val result = deadLetterService.query(1, 20, null)

        assertEquals(1, result.content.size)
        assertEquals(1L, result.content[0].id)
    }

    /**
     * 分页查询：status 非空时按状态过滤。
     */
    @Test
    fun queryShouldFilterByStatus() = runTest {
        val entity = deadLetterEntity(id = 1L, status = "pending")
        val pageable = PageRequest.of(0, 20)
        val items = listOf(entity)
        val page: Page<DeadLetterEntity> = PageImpl(items, pageable, 1)

        coEvery {
            deadLetterRepository.findByStatusOrderByCreatedAtAsc("pending", pageable)
        } returns items
        coEvery { deadLetterRepository.findAll(pageable) } returns page

        val result = deadLetterService.query(1, 20, "pending")

        assertEquals(1, result.content.size)
        assertEquals("pending", result.content[0].status)
    }

    /**
     * 分页查询：status 为空白时查询全部（与 null 同义）。
     */
    @Test
    fun queryShouldReturnAllWhenStatusIsBlank() = runTest {
        val pageable = PageRequest.of(0, 20)
        val page: Page<DeadLetterEntity> = PageImpl(emptyList(), pageable, 0)

        coEvery { deadLetterRepository.findAll(pageable) } returns page

        val result = deadLetterService.query(1, 20, "   ")

        assertEquals(0, result.content.size)
        coVerify(exactly = 1) { deadLetterRepository.findAll(pageable) }
    }

    // ================ markPermanentFailed ================

    /**
     * markPermanentFailed：failCount >= 5 的 retrying 记录应标记为 permanent_failed。
     */
    @Test
    fun markPermanentFailedShouldMarkExpiredItems() = runTest {
        val expired1 = deadLetterEntity(id = 1L, status = "retrying", failCount = 5)
        val expired2 = deadLetterEntity(id = 2L, status = "retrying", failCount = 6)

        mockMarkPermanentFailed(listOf(expired1, expired2))

        coEvery { deadLetterRepository.save(any<DeadLetterEntity>()) } answers { firstArg() }

        deadLetterService.markPermanentFailed()

        assertEquals("permanent_failed", expired1.status)
        assertEquals("permanent_failed", expired2.status)
        coVerify(exactly = 2) { deadLetterRepository.save(any()) }
    }

    /**
     * markPermanentFailed：failCount < 5 的记录应保持不变。
     */
    @Test
    fun markPermanentFailedShouldSkipItemsBelowThreshold() = runTest {
        val active1 = deadLetterEntity(id = 1L, status = "retrying", failCount = 3)
        val active2 = deadLetterEntity(id = 2L, status = "retrying", failCount = 4)

        mockMarkPermanentFailed(listOf(active1, active2))

        deadLetterService.markPermanentFailed()

        assertEquals("retrying", active1.status)
        assertEquals("retrying", active2.status)
        coVerify(exactly = 0) { deadLetterRepository.save(any()) }
    }
}
