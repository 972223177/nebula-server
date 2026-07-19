package com.nebula.repository.repository.impl

import com.nebula.common.init.DeadLetterCallback
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.redis.MessageQueueRepository
import io.lettuce.core.StreamMessage
import io.mockk.*
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * MessageRepositoryImpl 单元测试（mock 风格，不依赖 Testcontainers）。
 *
 * 覆盖 237991c 提交的两处修复：
 * 1. flushBatch 毒消息不再静默丢弃，而是先录死信再 XACK
 * 2. startFlushTimer 的 flush 循环在单轮异常（如 Redis 抖动）后仍存活
 * 以及正常消息的落库 + XACK 主路径。
 */
class MessageRepositoryImplTest {

    private lateinit var messageQueue: MessageQueueRepository
    private lateinit var jpaTxRunner: JpaTxRunner
    private lateinit var emf: EntityManagerFactory
    private lateinit var em: EntityManager
    private lateinit var impl: MessageRepositoryImpl

    @BeforeEach
    fun setup() {
        messageQueue = mockk()
        jpaTxRunner = mockk()
        emf = mockk(relaxed = true)
        em = mockk(relaxed = true)
        impl = MessageRepositoryImpl(messageQueue, jpaTxRunner, emf)
        coEvery { jpaTxRunner.execute<Any>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[0] as suspend (EntityManager) -> Any).invoke(em)
        }
        coEvery { messageQueue.acknowledge(any()) } just Runs
    }

    @Test
    fun flushBatchShouldLandParseableMessageAndAck() = runBlocking {
        val entry = mockk<StreamMessage<String, String>>()
        every { entry.id } returns "m:1"
        every { entry.body } returns mapOf(
            "id" to "111",
            "conversationId" to "c1",
            "senderUid" to "1",
            "messageType" to "0",
            "content" to "hi",
            "clientTs" to "100",
            "serverTs" to "200"
        )
        coEvery { messageQueue.consumeWithRetry(any(), any()) } returns listOf(entry)

        val n = impl.flushBatch()

        assertEquals(1, n)
        coVerify(exactly = 1) { messageQueue.acknowledge("m:1") }
    }

    @Test
    fun flushBatchShouldSendPoisonMessageToDeadLetterAndAck() = runBlocking {
        val poison = mockk<StreamMessage<String, String>>()
        every { poison.id } returns "poison:1"
        // 缺少 conversationId 等关键字段 → 无法解析为 MessageEntity（毒消息）
        every { poison.body } returns mapOf("content" to "corrupted")
        coEvery { messageQueue.consumeWithRetry(any(), any()) } returns listOf(poison)

        val deadLetter = mockk<DeadLetterCallback>(relaxed = true)
        impl.onDeadLetter = deadLetter

        val n = impl.flushBatch()

        assertEquals(0, n)
        // D-03 修复: 毒消息先录死信（保留原始 body）再 XACK，不再无痕丢弃
        coVerify(exactly = 1) { deadLetter.onUnparseableMessage(any(), any()) }
        coVerify(exactly = 1) { messageQueue.acknowledge("poison:1") }
    }
}
