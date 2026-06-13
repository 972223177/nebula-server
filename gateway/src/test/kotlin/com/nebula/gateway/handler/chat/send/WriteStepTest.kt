package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.message.ChatMessage
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.redis.MessageQueueRepository
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * WriteStep 单元测试（D-04, D-10, D-13）。
 *
 * 覆盖场景：
 * - 生成 msg_id 并设置到 context.msgId
 * - 构建 ChatMessage 并设置到 context.chatMessage
 * - 调用 MessageQueueRepository.enqueue
 * - 更新会话元 Redis key（last_message_id, last_message_preview, last_updated_at）
 * - 更新去重键值为实际 msg_id（REVIEW-MEDIUM-6）
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class WriteStepTest {

    private lateinit var idGenerator: SnowflakeIdGenerator
    private lateinit var messageQueueRepository: MessageQueueRepository
    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var step: WriteStep

    private val convId = "conv-001"
    private val senderUid = 1001L
    private val msgId = 50001L

    @BeforeEach
    fun setUp() {
        idGenerator = mockk<SnowflakeIdGenerator>()
        messageQueueRepository = mockk<MessageQueueRepository>(relaxed = true)
        redis = mockk<RedisCoroutinesCommands<String, String>>(relaxed = true)
        connection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        step = WriteStep(idGenerator, messageQueueRepository, connection)
    }

    /**
     * 辅助方法：替换 WriteStep 内部的 redis 字段。
     */
    private fun injectMockRedis() {
        val field = WriteStep::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(step, redis)
    }

    @Test
    fun generateMsgIdShouldSetToContext() = runTest {
        injectMockRedis()
        coEvery { idGenerator.nextId() } returns msgId
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-entry-id"

        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        val result = step.execute(context)

        assertTrue(result, "WriteStep 应返回 true")
        assertEquals(msgId, context.msgId, "context.msgId 应设置为 Snowflake 生成的 msgId")
    }

    @Test
    fun buildChatMessageShouldSetToContext() = runTest {
        injectMockRedis()
        coEvery { idGenerator.nextId() } returns msgId
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-entry-id"

        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("Hello, World!")
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        step.execute(context)

        val chatMessage = context.chatMessage
        assertNotNull(chatMessage, "context.chatMessage 应被设置")
        assertEquals(msgId, chatMessage.msgId)
        assertEquals(convId, chatMessage.conversationId)
        assertEquals(senderUid, chatMessage.senderUid)
        assertEquals("Hello, World!", chatMessage.content)
    }

    @Test
    fun shouldCallMessageQueueRepositoryEnqueue() = runTest {
        injectMockRedis()
        coEvery { idGenerator.nextId() } returns msgId
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-entry-id"

        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        step.execute(context)

        coVerify(exactly = 1) { messageQueueRepository.enqueue(any()) }
    }

    @Test
    fun shouldUpdateConversationMetaRedisKeys() = runTest {
        injectMockRedis()
        coEvery { idGenerator.nextId() } returns msgId
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-entry-id"

        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        step.execute(context)

        // 验证三个会话元 key 都被设置
        coVerify(exactly = 1) { redis.set("conversation:$convId:last_message_id", msgId.toString()) }
        coVerify(exactly = 1) { redis.set("conversation:$convId:last_message_preview", "Hello") }
        coVerify(exactly = 1) { redis.set("conversation:$convId:last_updated_at", any()) }
    }

    @Test
    fun shouldUpdateDedupKeyWithActualMsgId() = runTest {
        injectMockRedis()
        coEvery { idGenerator.nextId() } returns msgId
        coEvery { messageQueueRepository.enqueue(any()) } returns "stream-entry-id"

        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        step.execute(context)

        // REVIEW-MEDIUM-6: 去重键值被更新为实际 msg_id
        coVerify(exactly = 1) {
            redis.setex("chat:dedup:msg-001", 7 * 24 * 3600L, msgId.toString())
        }
    }
}
