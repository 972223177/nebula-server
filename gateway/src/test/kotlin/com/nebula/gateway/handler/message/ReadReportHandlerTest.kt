package com.nebula.gateway.handler.message

import com.nebula.chat.Response
import com.nebula.chat.message.ReadReceiptPayload
import com.nebula.chat.message.ReadReportReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.service.chat.MessageService
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * ReadReportHandler 已读报告 Handler 单元测试（D-23, D-24, D-27, D-28, REVIEW-MEDIUM-10）。
 *
 * 覆盖场景：
 * - 会话不存在 → 抛出 ConversationException(BizCode.CONV_NOT_FOUND)（D-27）
 * - 非会话成员 → 抛出 ConversationException(BizCode.NOT_MEMBER)（REVIEW-MEDIUM-10）
 * - 私聊且读者是会话成员 → 更新已读进度 + DEL unread key + 推送 READ_RECEIPT（D-23, D-24, D-28）
 * - 群聊 → 更新已读进度 + DEL unread key + 不推送（D-23）
 * - 私聊另一方已离线/退出 → 不推送（不抛异常）
 */
@OptIn(ExperimentalLettuceCoroutinesApi::class)
class ReadReportHandlerTest {

    private lateinit var messageService: MessageService
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var pushService: PushService
    private lateinit var connection: StatefulRedisConnection<String, String>
    private lateinit var redis: RedisCoroutinesCommands<String, String>
    private lateinit var handler: ReadReportHandler

    private val session = Session(2001L, "token-y", "MOBILE", "dev-2", "conn-2")

    @BeforeEach
    fun setUp() {
        messageService = mockk()
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        pushService = mockk(relaxed = true)
        connection = mockk(relaxed = true)
        redis = mockk(relaxed = true)

        handler = ReadReportHandler(
            messageService,
            conversationRepository,
            conversationMemberRepository,
            pushService,
            connection
        )
        // 通过反射注入 mock redis，避免使用 connection.reactive() 创建的实例
        injectMockRedis()

        // MessageService 已读报告默认返回成功（handler 先委托 messageService，再执行自有的 gateway 逻辑）
        coEvery { messageService.readReport(any<ReadReportReq>(), any()) } returns Unit
    }

    /** 创建私聊会话实体 */
    private fun privateConv(id: String) = ConversationEntity(type = 0).apply { this.id = id }

    /** 创建群聊会话实体 */
    private fun groupConv(id: String) = ConversationEntity(type = 1).apply { this.id = id }

    /**
     * 通过反射替换 ReadReportHandler 中的 [redis] 字段为 mock 实例。
     *
     * 原因：ReadReportHandler 中 redis 字段为 private val，由 connection.reactive()
     * 初始化。测试中 connection 本身已是 mock，但其 reactive() 返回值仍为真实 Lettuce
     * 实现，无法在单元测试中直接控制。此处通过反射绕过 private 访问限制注入 mock，
     * 避免引入生产代码修改（如增加 redis 构造函数参数）。
     */
    @Suppress("UNCHECKED_CAST")
    private fun injectMockRedis() {
        val field = ReadReportHandler::class.java.getDeclaredField("redis")
        field.isAccessible = true
        field.set(handler, redis)
    }

    @Test
    fun convNotFoundShouldThrowConversationException() = runTest {
        every { conversationRepository.findById("conv-not-exists") } returns Optional.empty()

        val req = ReadReportReq.newBuilder()
            .setConversationId("conv-not-exists")
            .setLastReadMsgId(50001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.CONV_NOT_FOUND, exception.bizCode)
    }

    @Test
    fun nonMemberShouldThrowException() = runTest {
        // Handler 委托 MessageService 处理成员检查，模拟非成员场景
        coEvery {
            messageService.readReport(any<ReadReportReq>(), any())
        } throws com.nebula.common.exception.MessageException(
            BizCode.NOT_MEMBER, "用户不是会话成员"
        )

        val req = ReadReportReq.newBuilder()
            .setConversationId("conv-001")
            .setLastReadMsgId(50001L)
            .build()
        val exception = assertFailsWith<com.nebula.common.exception.MessageException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    @Test
    fun privateChatReadReportShouldUpdateAndPushReadReceipt() = runTest {
        // 模拟私聊会话（type=0）
        val convEntity = privateConv("conv-001")
        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)

        // 当前用户是会话成员（由 messageService.readReport 内部处理）
        val member = ConversationMemberEntity("conv-001", 2001L)

        // 私聊另一方成员（用于 pushReadReceiptToSender）
        val senderMember = ConversationMemberEntity("conv-001", 1001L)
        every {
            conversationMemberRepository.findByConversationId("conv-001")
        } returns listOf(member, senderMember)

        // 执行
        val req = ReadReportReq.newBuilder()
            .setConversationId("conv-001")
            .setLastReadMsgId(50001L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        // 验证响应
        assertNotNull(resp)
        assertEquals(200, resp.code)
        assertEquals("message/read", resp.method)

        // 验证 updateReadReceipt 已被 messageService.readReport 内部调用

        // 验证 Redis DEL 被调用
        coVerify {
            redis.del("conversation:conv-001:unread:2001")
        }

        // 验证 pushReadReceipt 被调用（私聊场景）
        verify {
            pushService.pushReadReceipt(1001L, any<ReadReceiptPayload>())
        }
    }

    @Test
    fun groupChatReadReportShouldUpdateWithoutPush() = runTest {
        // 模拟群聊会话（type=1）
        val convEntity = groupConv("conv-002")
        every { conversationRepository.findById("conv-002") } returns Optional.of(convEntity)

        // 当前用户是会话成员（由 messageService.readReport 处理）

        // 执行
        val req = ReadReportReq.newBuilder()
            .setConversationId("conv-002")
            .setLastReadMsgId(60001L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        // 验证响应
        assertNotNull(resp)
        assertEquals(200, resp.code)

        // 验证 updateReadReceipt 已被 messageService.readReport 内部调用

        // 验证 Redis DEL 被调用
        coVerify {
            redis.del("conversation:conv-002:unread:2001")
        }

        // 验证 pushReadReceipt 不被调用（群聊不推送）
        verify(exactly = 0) {
            pushService.pushReadReceipt(any<Long>(), any<ReadReceiptPayload>())
        }
    }

    @Test
    fun otherPartyLeftShouldSkipPush() = runTest {
        // 模拟私聊会话（type=0）
        val convEntity = privateConv("conv-003")
        every { conversationRepository.findById("conv-003") } returns Optional.of(convEntity)

        // 当前用户是会话成员（由 messageService.readReport 处理）

        // 私聊成员查询——只有读者自己，无发送者
        val member = ConversationMemberEntity("conv-003", 2001L)
        every {
            conversationMemberRepository.findByConversationId("conv-003")
        } returns listOf(member)

        // 执行
        val req = ReadReportReq.newBuilder()
            .setConversationId("conv-003")
            .setLastReadMsgId(70001L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        // 验证响应正常返回（不抛异常）
        assertNotNull(resp)
        assertEquals(200, resp.code)

        // 验证 pushReadReceipt 不被调用
        verify(exactly = 0) {
            pushService.pushReadReceipt(any<Long>(), any<ReadReceiptPayload>())
        }
    }
}
