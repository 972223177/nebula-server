package com.nebula.gateway.handler.message

import com.nebula.chat.message.MessageSeqReq
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.exception.MessageException
import com.nebula.service.sequence.SeqService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * MessageSeqHandler 单元测试（Phase 10）。
 *
 * 覆盖场景：
 * - 正常查询会话序列号
 * - conversationId 为空时抛 [MessageException]（INVALID_PARAM）
 * - 无 Session 上下文时抛 [BizException]（UNAUTHORIZED）
 *
 * Session 注入使用 TestHelper.withSession()。
 */
class MessageSeqHandlerTest {

    private lateinit var seqService: SeqService
    private lateinit var handler: MessageSeqHandler

    @BeforeEach
    fun setUp() {
        seqService = mockk<SeqService>(relaxed = true)
        handler = MessageSeqHandler(seqService)
    }

    @Test
    fun handleShouldReturnSeqForValidRequest() = runTest {
        // 准备：currentSeq 返回 42
        coEvery { seqService.currentSeq("conv-001", 1001L) } returns 42L

        // 执行：在 Session 上下文中调用
        val resp = com.nebula.gateway.testutil.withSession {
            val req = MessageSeqReq.newBuilder()
                .setConversationId("conv-001")
                .build()
            handler.handle(req)
        }

        // 验证
        assertNotNull(resp, "响应不应为空")
        assertEquals(42L, resp.seq, "应返回协程上下文的序列号 42")
    }

    @Test
    fun handleShouldThrowWhenConversationIdIsBlank() = runTest {
        // 执行：传入空 conversationId
        val exception = assertFailsWith<MessageException> {
            com.nebula.gateway.testutil.withSession {
                val req = MessageSeqReq.newBuilder()
                    .setConversationId("")
                    .build()
                handler.handle(req)
            }
        }

        // 验证
        assertEquals(BizCode.INVALID_PARAM, exception.bizCode, "空 conversationId 应抛出 INVALID_PARAM")
    }

    @Test
    fun handleShouldRequireSession() = runTest {
        // 执行：在无 Session 上下文中调用
        val exception = assertFailsWith<BizException> {
            val req = MessageSeqReq.newBuilder()
                .setConversationId("conv-001")
                .build()
            handler.handle(req)
        }

        // 验证：抛出 UNAUTHORIZED 异常
        assertEquals(BizCode.UNAUTHORIZED, exception.bizCode, "无 Session 时应抛出 UNAUTHORIZED")
    }
}
