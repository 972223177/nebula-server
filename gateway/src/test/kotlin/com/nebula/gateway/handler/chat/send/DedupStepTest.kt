package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * DedupStep 单元测试（D-07, D-13, D-72）。
 *
 * D-72：去重逻辑已下沉到 MessageQueueRepository.checkAndSetDedup() 中，
 * DedupStep 目前为 no-op 占位，execute 始终返回 true。
 *
 * 覆盖场景：
 * - execute 返回 true 继续链
 */
class DedupStepTest {

    private val step = DedupStep()

    @Test
    fun executeShouldReturnTrue() = runTest {
        val req = SendMessageReq.newBuilder()
            .setConversationId("conv-001")
            .setContent("Hello")
            .setClientMessageId("msg-001")
            .build()
        val context = SendContext(req = req, senderUid = 1001L)

        val result = step.execute(context)

        assertTrue(result, "no-op DedupStep 应返回 true 继续链")
    }
}
