package com.nebula.gateway.fanout

import com.nebula.chat.message.ChatMessage
import com.nebula.common.init.DeadLetterCallback
import com.nebula.common.redis.RedisStreamQueue
import com.nebula.service.chat.MessageService
import com.nebula.service.conversation.ConversationMemberInfo

import com.nebula.chat.group.GroupMemberRole
import com.nebula.service.conversation.ConversationService
import io.mockk.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import java.util.Base64

/**
 * FanoutWorker 单元测试（§七 Durable Outbox）。
 *
 * 直接驱动 [FanoutWorker.processOne]（internal）验证核心可靠性逻辑：
 * - 正常路径：未读自增（服务层幂等）+ 推送，排除发送方；
 * - PEL 重投：worker 再次调用 incrementUnreadCount，幂等由服务层 unread_dedup 保证（不双计），推送仍补（客户端去重）；
 * - 毒消息（关键字段缺失 / proto 不可解析 / body 与 chatMessage 字段错位）：落死信且不抛异常（调用方确认释放 PEL）；
 * - 瞬态失败（未读 DB / 推送抛异常）：向上抛出，调用方留 PEL 下轮重试（不确认）。
 *
 * 注：processBatch 的 Redis 消费 / XACK 行为由集成测试覆盖（需真实 Redis），本测试聚焦纯逻辑。
 */
class FanoutWorkerTest {

    private val fanoutQueue = mockk<RedisStreamQueue>()
    private val conversationService = mockk<ConversationService>()
    private val messageService = mockk<MessageService>()
    private val pushService = mockk<com.nebula.gateway.push.PushService>(relaxed = true)
    private val deadLetterCallback = mockk<DeadLetterCallback>(relaxed = true)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val worker = FanoutWorker(fanoutQueue, conversationService, messageService, pushService, deadLetterCallback, scope)

    private val chatMessage = ChatMessage.newBuilder()
        .setMsgId(1L)
        .setConversationId("conv-1")
        .setSenderUid(1001L)
        .build()

    private fun validBody(convId: String = "conv-1", msgId: Long = 1L, senderUid: Long = 1001L) = mapOf(
        "conversationId" to convId,
        "msgId" to msgId.toString(),
        "senderUid" to senderUid.toString(),
        "chatMessage" to Base64.getEncoder().encodeToString(chatMessage.toByteArray())
    )

    private fun members(vararg uids: Long) = uids.map { ConversationMemberInfo(userId = it, role = GroupMemberRole.MEMBER) }

    @Test
    fun happyPathIncrementsUnreadAndPushes_excludingSender() = runTest {
        coEvery { conversationService.getConversationMembers("conv-1") } returns members(1002L, 1001L, 1003L)
        coEvery { messageService.incrementUnreadCount("conv-1", 1L, 1001L) } returns Unit
        coEvery { pushService.pushMessageToMembers(any(), any()) } returns Unit

        worker.processOne(validBody())

        // 发送方 1001 被排除，仅 1002 / 1003 加未读 + 推送
        coVerify(exactly = 1) { messageService.incrementUnreadCount("conv-1", 1L, 1001L) }
        coVerify(exactly = 1) { pushService.pushMessageToMembers(listOf(1002L, 1003L), chatMessage) }
    }

    @Test
    fun pelRetryReinvokesButServiceDedupsUnread() = runTest {
        // PEL 重投：worker 再次调用 incrementUnreadCount，但服务层 unread_dedup 保证不双计；
        // 推送仍执行（客户端按 msgId 去重，重投无害）。
        coEvery { conversationService.getConversationMembers("conv-1") } returns members(1002L, 1001L)
        coEvery { messageService.incrementUnreadCount("conv-1", 1L, 1001L) } returns Unit
        coEvery { pushService.pushMessageToMembers(any(), any()) } returns Unit

        worker.processOne(validBody())
        worker.processOne(validBody())

        // 未读自增被调用两次（幂等由服务层 unread_dedup 保证，不在 worker 层跳过）
        coVerify(exactly = 2) { messageService.incrementUnreadCount("conv-1", 1L, 1001L) }
        // 推送每次都执行
        coVerify(exactly = 2) { pushService.pushMessageToMembers(listOf(1002L), chatMessage) }
    }

    @Test
    fun poisonMessageIsDeadLetteredWithoutThrowing() = runTest {
        // 缺失 conversationId → 毒消息
        worker.processOne(mapOf("msgId" to "1", "senderUid" to "1001", "chatMessage" to "x"))

        coVerify(exactly = 1) { deadLetterCallback.onUnparseableMessage(any(), any()) }
        // 不抛异常：调用方据此 XACK 释放 PEL（R3 根治）
    }

    @Test
    fun invalidProtoIsDeadLetteredWithoutThrowing() = runTest {
        worker.processOne(
            mapOf(
                "conversationId" to "conv-1",
                "msgId" to "1",
                "senderUid" to "1001",
                "chatMessage" to Base64.getEncoder().encodeToString("not-a-proto".toByteArray())
            )
        )

        coVerify(exactly = 1) { deadLetterCallback.onUnparseableMessage(any(), any()) }
    }

    @Test
    fun bodyChatMessageMismatchIsDeadLettered() = runTest {
        // chatMessage 内部 conversationId 与 body 不一致 → 毒消息（审查 #4 交叉校验）
        val mismatched = ChatMessage.newBuilder()
            .setMsgId(1L)
            .setConversationId("conv-OTHER")
            .setSenderUid(1001L)
            .build()
        worker.processOne(
            mapOf(
                "conversationId" to "conv-1",
                "msgId" to "1",
                "senderUid" to "1001",
                "chatMessage" to Base64.getEncoder().encodeToString(mismatched.toByteArray())
            )
        )

        coVerify(exactly = 1) { deadLetterCallback.onUnparseableMessage(any(), any()) }
        coVerify(exactly = 0) { messageService.incrementUnreadCount(any(), any(), any()) }
    }

    @Test
    fun transientUnreadFailurePropagatesForPelRetry() = runTest {
        coEvery { conversationService.getConversationMembers("conv-1") } returns members(1002L, 1001L)
        coEvery { messageService.incrementUnreadCount("conv-1", 1L, 1001L) } throws RuntimeException("db down")

        // 未读 DB 失败向上抛出 → processBatch 不 XACK，留 PEL 下轮重试
        assertFailsWith<RuntimeException> {
            worker.processOne(validBody())
        }
    }

    @Test
    fun transientPushFailurePropagatesForPelRetry() = runTest {
        coEvery { conversationService.getConversationMembers("conv-1") } returns members(1002L, 1001L)
        coEvery { messageService.incrementUnreadCount("conv-1", 1L, 1001L) } returns Unit
        coEvery { pushService.pushMessageToMembers(any(), any()) } throws RuntimeException("push downstream down")

        // 瞬态失败向上抛出 → processBatch 不 XACK，留 PEL 下轮重试
        assertFailsWith<RuntimeException> {
            worker.processOne(validBody())
        }
    }
}
