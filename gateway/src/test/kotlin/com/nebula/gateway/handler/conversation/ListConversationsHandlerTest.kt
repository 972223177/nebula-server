package com.nebula.gateway.handler.conversation

import com.nebula.chat.conversation.ConvListReq
import com.nebula.chat.conversation.ConversationBrief
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.service.conversation.ConversationService
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ListConversationsHandler 会话列表 Handler 单元测试（D-01, D-13, D-21）。
 *
 * 覆盖场景：
 * - cursor=0 首次查询返回会话列表
 * - cursor>0 翻页返回更旧的会话
 * - hasMore=true（返回数>limit）截断多取的记录
 * - hasMore=false（返回数≤limit）不截断
 * - 空列表（用户无会话）正常返回空
 * - ConversationBrief 字段映射验证（type/updatedAt/lastReadMsgId）
 */
class ListConversationsHandlerTest {

    private lateinit var conversationService: ConversationService
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var handler: ListConversationsHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        handler = ListConversationsHandler(conversationService)
    }

    @Test
    fun `cursor=0首次查询返回会话列表`() = runTest {
        val now = LocalDateTime.now()
        val conv1 = ConversationEntity(type = 0).apply { id = "conv-001"; updatedAt = now }
        val conv2 = ConversationEntity(type = 1).apply { id = "conv-002"; updatedAt = now.minusDays(1) }
        val conversations = listOf(conv1, conv2)

        every {
            conversationRepository.findConversationsByUserId(1001L, null, any())
        } returns conversations
        every {
            conversationMemberRepository.findByConversationIdsAndUserId(
                listOf("conv-001", "conv-002"), 1001L
            )
        } returns listOf(
            ConversationMemberEntity("conv-001", 1001L).apply { lastReadMessageId = 50001L },
            ConversationMemberEntity("conv-002", 1001L).apply { lastReadMessageId = 60001L }
        )

        val req = ConvListReq.newBuilder()
            .setLimit(20)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(2, resp.conversationsCount)
        assertFalse(resp.hasMore)
        assertEquals("conv-001", resp.conversationsList[0].conversationId)
        assertEquals("conv-002", resp.conversationsList[1].conversationId)
        // 群聊 type=1 映射为 "group"
        assertEquals("group", resp.conversationsList[1].type)
    }

    @Test
    fun `cursor大于0翻页返回更旧的会话`() = runTest {
        val now = LocalDateTime.now()
        val cursorMillis = now.minusDays(1).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val oldConv = ConversationEntity(type = 0).apply {
            id = "conv-003"; updatedAt = now.minusDays(2)
        }

        every {
            conversationRepository.findConversationsByUserId(1001L, any(), any())
        } returns listOf(oldConv)
        every {
            conversationMemberRepository.findByConversationIdsAndUserId(listOf("conv-003"), 1001L)
        } returns listOf(
            ConversationMemberEntity("conv-003", 1001L).apply { lastReadMessageId = 70001L }
        )

        val req = ConvListReq.newBuilder()
            .setCursor(cursorMillis)
            .setLimit(20)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(1, resp.conversationsCount)
        assertEquals("conv-003", resp.conversationsList[0].conversationId)
        assertEquals(70001L, resp.conversationsList[0].lastReadMsgId)
    }

    @Test
    fun `hasMore为true返回数超过limit时截断`() = runTest {
        val now = LocalDateTime.now()
        // 返回 limit+1 条（11 条，实际截断为 10 条）
        val conversations = (1..11).map { i ->
            ConversationEntity(type = 0).apply {
                id = "conv-${i.toString().padStart(2, '0')}"
                updatedAt = now.minusHours(i.toLong())
            }
        }

        every {
            conversationRepository.findConversationsByUserId(1001L, null, any())
        } returns conversations
        // 截断后只有 10 个会话 ID 参与批量查成员
        val resultIds = conversations.dropLast(1).map { it.id!! }
        every {
            conversationMemberRepository.findByConversationIdsAndUserId(resultIds, 1001L)
        } returns resultIds.map {
            ConversationMemberEntity(it, 1001L).apply { lastReadMessageId = 0 }
        }

        val req = ConvListReq.newBuilder()
            .setLimit(10)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(10, resp.conversationsCount)
        assertTrue(resp.hasMore)
    }

    @Test
    fun `hasMore为false返回数不超过limit`() = runTest {
        val now = LocalDateTime.now()
        // 返回 5 条，少于 limit=10
        val conversations = (1..5).map { i ->
            ConversationEntity(type = 0).apply {
                id = "conv-0${i}"
                updatedAt = now.minusHours(i.toLong())
            }
        }
        val convIds = conversations.map { it.id!! }

        every {
            conversationRepository.findConversationsByUserId(1001L, null, any())
        } returns conversations
        every {
            conversationMemberRepository.findByConversationIdsAndUserId(convIds, 1001L)
        } returns convIds.map {
            ConversationMemberEntity(it, 1001L).apply { lastReadMessageId = 0 }
        }

        val req = ConvListReq.newBuilder()
            .setLimit(10)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(5, resp.conversationsCount)
        assertFalse(resp.hasMore)
    }

    @Test
    fun `空列表用户无会话返回空`() = runTest {
        every {
            conversationRepository.findConversationsByUserId(1001L, null, any())
        } returns emptyList()

        val req = ConvListReq.newBuilder()
            .setLimit(20)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(0, resp.conversationsCount)
        assertFalse(resp.hasMore)
    }

    @Test
    fun `ConversationBrief字段映射验证`() = runTest {
        val now = LocalDateTime.now()
        val epochMilli = now.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val conv = ConversationEntity(type = 0).apply {
            id = "conv-001"
            name = "测试私聊"
            avatar = "http://a.com/1.png"
            lastMessageId = 999L
            lastMessagePreview = "最后一句话"
            lastMessageTs = 1700000000000L
            updatedAt = now
        }

        every {
            conversationRepository.findConversationsByUserId(1001L, null, any())
        } returns listOf(conv)
        every {
            conversationMemberRepository.findByConversationIdsAndUserId(listOf("conv-001"), 1001L)
        } returns listOf(
            ConversationMemberEntity("conv-001", 1001L).apply { lastReadMessageId = 50001L }
        )

        val req = ConvListReq.newBuilder()
            .setLimit(20)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(1, resp.conversationsCount)
        val brief = resp.conversationsList[0]
        assertEquals("conv-001", brief.conversationId)
        // type=0 → "private"
        assertEquals("private", brief.type)
        assertEquals("测试私聊", brief.name)
        assertEquals("http://a.com/1.png", brief.avatarUrl)
        assertEquals(999L, brief.lastMessageId)
        assertEquals("最后一句话", brief.lastMessagePreview)
        assertEquals(1700000000000L, brief.lastMessageTs)
        // updatedAt → epoch millis
        assertEquals(epochMilli, brief.lastUpdatedAt)
        assertEquals(50001L, brief.lastReadMsgId)
    }
}
