package com.nebula.service.chat

import com.nebula.chat.chat.SendMessageReq
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.dao.*
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.service.sequence.SeqService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.persistence.EntityManager
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith

/**
 * MessageService.sendMessage 写顺序单元测试（mock 风格）。
 *
 * 覆盖 237991c 提交的核心修复：写顺序由「先更新会话元信息 → 再 enqueue」
 * 反转为「先 enqueue 到 Redis Stream → 再更新会话元信息」，以消除「概要超前、
 * 消息永久丢失」的漂移窗口（表现为「会话列表有概要、message/pull 却为空」）。
 */
class MessageServiceSendOrderTest {

    private lateinit var messageDao: MessageDao
    private lateinit var conversationMemberDao: ConversationMemberDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var friendshipDao: FriendshipDao
    private lateinit var txRunner: JpaTxRunner
    private lateinit var messageQueueRepository: MessageQueueRepository
    private lateinit var idGenerator: SnowflakeIdGenerator
    private lateinit var seqService: SeqService
    private lateinit var em: EntityManager
    private lateinit var messageService: MessageService

    private val convId = "private:1:2"
    private val senderUid = 1L

    @BeforeEach
    fun setup() {
        messageDao = mockk()
        conversationMemberDao = mockk()
        conversationDao = mockk()
        friendshipDao = mockk()
        txRunner = mockk()
        messageQueueRepository = mockk()
        idGenerator = mockk()
        seqService = mockk()
        em = mockk(relaxed = true)
        messageService = MessageService(
            messageDao, conversationMemberDao, conversationDao,
            friendshipDao, txRunner, messageQueueRepository, idGenerator, seqService
        )

        coEvery { txRunner.execute<Any>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[0] as suspend (EntityManager) -> Any).invoke(em)
        }
        coEvery { idGenerator.nextId() } returns 12345L

        // 默认：会话成员有效 + 会话存在（群聊 type=2 跳过私聊好友检查）
        val conv = ConversationEntity(type = 2, name = "g").apply { id = convId }
        val member = ConversationMemberEntity(convId, senderUid)
        coEvery { conversationMemberDao.findByConversationIdAndUserId(em, any(), any()) } returns member
        coEvery { conversationDao.findById(em, any()) } returns conv
        coEvery { seqService.nextSeq(any(), any()) } returns 1L
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-id"
    }

    private fun buildReq() = SendMessageReq.newBuilder()
        .setConversationId(convId)
        .setContent("123")
        .setClientMessageId("cmid-1")
        .setMessageTypeValue(0)
        .setClientTs(100L)
        .build()

    @Test
    fun sendMessageShouldEnqueueToStreamOnSuccess() = runTest {
        val result = messageService.sendMessage(buildReq(), senderUid)

        assertEquals(12345L, result.msgId)
        coVerify(exactly = 1) { messageQueueRepository.enqueue(any()) }
    }

    @Test
    fun sendMessageShouldKeepEnqueueWhenConvMetaUpdateFails() = runTest {
        // 验证写顺序反转: enqueue 先于元信息更新。
        // 若仍是旧顺序(先元信息后 enqueue), 元信息更新失败会导致 enqueue 永不执行(消息丢失);
        // 新顺序下 enqueue 已先完成, 消息已在 Stream 会落库, 不丢。
        var callCount = 0
        coEvery { txRunner.execute<Any>(any()) } coAnswers {
            callCount++
            if (callCount == 2) throw RuntimeException("db update failed")
            @Suppress("UNCHECKED_CAST")
            (args[0] as suspend (EntityManager) -> Any).invoke(em)
        }

        assertFailsWith<RuntimeException> {
            messageService.sendMessage(buildReq(), senderUid)
        }
        // 元信息更新失败, 但消息已经写入 Stream(可落库), 不因旧顺序而丢失
        coVerify(exactly = 1) { messageQueueRepository.enqueue(any()) }
    }

    @Test
    fun sendMessageShouldPropagateEnqueueFailure() = runTest {
        coEvery { messageQueueRepository.enqueue(any()) } throws RuntimeException("redis down")

        assertFailsWith<RuntimeException> {
            messageService.sendMessage(buildReq(), senderUid)
        }
        // enqueue 失败时整体失败；且只执行了前置只读校验那一次事务，
        // 会话元信息更新事务(在 enqueue 之后)不会执行，避免概要超前指向不存在的消息
        coVerify(exactly = 1) { txRunner.execute<Any>(any()) }
    }
}
