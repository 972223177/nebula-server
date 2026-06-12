package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.common.BizCode
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * ValidateStep 单元测试（D-08, D-13, D-14）。
 *
 * 覆盖场景：
 * - 消息内容为空 → 抛出 SendMessageException(INVALID_PARAM)
 * - client_message_id 为空 → 抛出 SendMessageException(INVALID_PARAM)
 * - 非会话成员 senderUid → 抛出 SendMessageException(NOT_MEMBER)
 * - 合法请求 → 返回 true 继续链
 */
class ValidateStepTest {

    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var step: ValidateStep

    private val convId = "conv-001"
    private val senderUid = 1001L

    @BeforeEach
    fun setUp() {
        conversationMemberRepository = mockk<ConversationMemberRepository>()
        step = ValidateStep(conversationMemberRepository)
    }

    @Test
    fun `内容为空时抛出 INVALID_PARAM 异常`() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("")  // 空内容
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        try {
            step.execute(context)
            fail("应抛出 SendMessageException(INVALID_PARAM)")
        } catch (e: SendMessageException) {
            assertEquals(BizCode.INVALID_PARAM, e.bizCode)
        }
    }

    @Test
    fun `client_message_id 为空时抛出 INVALID_PARAM 异常`() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("Hello")
            .setClientMessageId("")  // 空 client_message_id
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        try {
            step.execute(context)
            fail("应抛出 SendMessageException(INVALID_PARAM)")
        } catch (e: SendMessageException) {
            assertEquals(BizCode.INVALID_PARAM, e.bizCode)
        }
    }

    @Test
    fun `非会话成员抛出 NOT_MEMBER 异常`() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        // 模拟非成员：findByConversationIdAndUserId 返回 null
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(convId, senderUid)
        } returns null

        try {
            step.execute(context)
            fail("应抛出 SendMessageException(NOT_MEMBER)")
        } catch (e: SendMessageException) {
            assertEquals(BizCode.NOT_MEMBER, e.bizCode)
        }
    }

    @Test
    fun `合法请求返回 true 继续链`() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId(convId)
            .setContent("Hello, World!")
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = senderUid)

        // 模拟成员身份验证通过
        coEvery {
            conversationMemberRepository.findByConversationIdAndUserId(convId, senderUid)
        } returns ConversationMemberEntity(convId, senderUid)

        val result = step.execute(context)

        assertTrue(result, "合法请求应返回 true 继续链")
    }
}
