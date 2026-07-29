package com.nebula.service.chat

import com.nebula.chat.chat.SendMessageReq
import com.nebula.repository.entity.MessageEntity
import com.nebula.repository.redis.MessageQueueRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * MessagePersistHelper 独立单元测试（P2 抽取）。
 *
 * 覆盖三类「非编排」落库细节：
 * - enqueueMessage：Redis Stream 字段构造（尤其 messageType 必须写数字值，不能写枚举名）；
 * - checkAndSetDedup：Redis SETNX 去重结果透传；
 * - toChatMessage：MessageEntity → ChatMessage 字段映射。
 */
class MessagePersistHelperTest {

    private val repo = mockk<MessageQueueRepository>()
    private val helper = MessagePersistHelper(repo)

    @Test
    fun enqueueMessageBuildsStreamFieldsWithNumericMessageType() = runTest {
        val slot = slot<Map<String, String>>()
        coEvery { repo.enqueue(capture(slot)) } returns "stream-id"

        val req = SendMessageReq.newBuilder()
            .setConversationId("c1")
            .setContent("hi")
            .setClientMessageId("cm1")
            .setMessageTypeValue(2)
            .setClientTs(50L)
            .build()

        helper.enqueueMessage(req, 999L, 7L, 123L)

        coVerify(exactly = 1) { repo.enqueue(any()) }
        val f = slot.captured
        assertEquals("999", f["id"])
        assertEquals("c1", f["conversationId"])
        assertEquals("7", f["senderUid"])
        assertEquals("2", f["messageType"]) // 数字值，非枚举名（否则被判毒消息→死信）
        assertEquals("hi", f["content"])
        assertEquals("cm1", f["clientMessageId"])
        assertEquals("50", f["clientTs"])
        assertEquals("123", f["serverTs"])
        assertEquals("", f["payload"])
    }

    @Test
    fun checkAndSetDedupReturnsFalseOnDuplicate() = runTest {
        coEvery { repo.checkAndSetDedup("cm1", 7L) } returns false
        assertEquals(false, helper.checkAndSetDedup("cm1", 7L))
    }

    @Test
    fun toChatMessageMapsAllFields() {
        val entity = MessageEntity("c1", 7L, 2, "hi", null, null, 50L, 123L).apply { id = 42L }
        val msg = helper.toChatMessage(entity)
        assertEquals(42L, msg.msgId)
        assertEquals("c1", msg.conversationId)
        assertEquals(7L, msg.senderUid)
        assertEquals(2, msg.messageTypeValue)
        assertEquals("hi", msg.content)
        assertEquals(50L, msg.clientTs)
        assertEquals(123L, msg.serverTs)
        assertEquals(0, msg.payload.size())
    }
}
