package com.nebula.gateway.handler.conversation

import com.nebula.chat.conversation.ConvListReq
import com.nebula.chat.conversation.ConvListResp
import com.nebula.chat.conversation.ConversationBrief
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.service.conversation.ConversationService
import io.mockk.coEvery
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
    private lateinit var handler: ListConversationsHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    /** 构建一个 ConversationBrief */
    private fun brief(
        id: String,
        type: String = "private",
        name: String = "",
        lastReadMsgId: Long = 0L,
        updatedAt: Long = 0L
    ) = ConversationBrief.newBuilder()
        .setConversationId(id)
        .setType(type)
        .setName(name)
        .setLastReadMsgId(lastReadMsgId)
        .setLastUpdatedAt(updatedAt)
        .build()

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        handler = ListConversationsHandler(conversationService)
    }

    @Test
    fun cursorZeroShouldReturnConversationList() = runTest {
        val now = LocalDateTime.now()
        val epochMs = now.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val mockResp = ConvListResp.newBuilder()
            .addConversations(brief("conv-001", type = "private", updatedAt = epochMs))
            .addConversations(brief("conv-002", type = "group", updatedAt = now.minusDays(1).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()))
            .setHasMore(false)
            .build()

        coEvery { conversationService.listConversations(any(), any(), any()) } returns mockResp

        val req = ConvListReq.newBuilder().setLimit(20).build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(2, resp.conversationsCount)
        assertFalse(resp.hasMore)
        assertEquals("conv-001", resp.conversationsList[0].conversationId)
        assertEquals("conv-002", resp.conversationsList[1].conversationId)
        assertEquals("group", resp.conversationsList[1].type)
    }

    @Test
    fun cursorGreaterThanZeroShouldReturnOlderConversations() = runTest {
        val now = LocalDateTime.now()
        val cursorMillis = now.minusDays(1).atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val mockResp = ConvListResp.newBuilder()
            .addConversations(brief("conv-003", lastReadMsgId = 70001L))
            .setHasMore(false)
            .build()

        coEvery { conversationService.listConversations(any(), any(), any()) } returns mockResp

        val req = ConvListReq.newBuilder().setCursor(cursorMillis).setLimit(20).build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(1, resp.conversationsCount)
        assertEquals("conv-003", resp.conversationsList[0].conversationId)
        assertEquals(70001L, resp.conversationsList[0].lastReadMsgId)
    }

    @Test
    fun hasMoreTrueShouldTruncateExceedingLimit() = runTest {
        val mockResp = ConvListResp.newBuilder()
        for (i in 1..10) {
            mockResp.addConversations(brief("conv-${i.toString().padStart(2, '0')}"))
        }
        mockResp.setHasMore(true)
        coEvery { conversationService.listConversations(any(), any(), any()) } returns mockResp.build()

        val req = ConvListReq.newBuilder().setLimit(10).build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(10, resp.conversationsCount)
        assertTrue(resp.hasMore)
    }

    @Test
    fun hasMoreFalseShouldNotExceedLimit() = runTest {
        val mockResp = ConvListResp.newBuilder()
        for (i in 1..5) {
            mockResp.addConversations(brief("conv-0$i"))
        }
        mockResp.setHasMore(false)
        coEvery { conversationService.listConversations(any(), any(), any()) } returns mockResp.build()

        val req = ConvListReq.newBuilder().setLimit(10).build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(5, resp.conversationsCount)
        assertFalse(resp.hasMore)
    }

    @Test
    fun emptyListShouldReturnEmpty() = runTest {
        val emptyResp = ConvListResp.newBuilder().setHasMore(false).build()
        coEvery { conversationService.listConversations(any(), any(), any()) } returns emptyResp

        val req = ConvListReq.newBuilder().setLimit(20).build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(0, resp.conversationsCount)
        assertFalse(resp.hasMore)
    }

    @Test
    fun conversationBriefFieldMappingShouldBeCorrect() = runTest {
        val now = LocalDateTime.now()
        val epochMilli = now.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
        val mockResp = ConvListResp.newBuilder()
            .addConversations(ConversationBrief.newBuilder()
                .setConversationId("conv-001")
                .setType("private")
                .setName("测试私聊")
                .setAvatarUrl("http://a.com/1.png")
                .setLastMessageId(999L)
                .setLastMessagePreview("最后一句话")
                .setLastMessageTs(1700000000000L)
                .setLastUpdatedAt(epochMilli)
                .setLastReadMsgId(50001L)
                .build())
            .setHasMore(false)
            .build()
        coEvery { conversationService.listConversations(any(), any(), any()) } returns mockResp

        val req = ConvListReq.newBuilder().setLimit(20).build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(1, resp.conversationsCount)
        val brief = resp.conversationsList[0]
        assertEquals("conv-001", brief.conversationId)
        assertEquals("private", brief.type)
        assertEquals("测试私聊", brief.name)
        assertEquals("http://a.com/1.png", brief.avatarUrl)
        assertEquals(999L, brief.lastMessageId)
        assertEquals("最后一句话", brief.lastMessagePreview)
        assertEquals(1700000000000L, brief.lastMessageTs)
        assertEquals(epochMilli, brief.lastUpdatedAt)
        assertEquals(50001L, brief.lastReadMsgId)
    }
}
