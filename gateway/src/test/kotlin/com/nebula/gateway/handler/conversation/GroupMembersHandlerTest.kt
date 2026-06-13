package com.nebula.gateway.handler.conversation

import com.nebula.chat.conversation.GroupMembersReq
import com.nebula.chat.conversation.GroupMembersResp
import com.nebula.chat.group.GroupMember
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * GroupMembersHandler 群成员列表 Handler 单元测试（D-06）。
 *
 * 覆盖场景：
 * - 正常返回成员列表（含 username/displayName/avatar/role/joinedAt）
 * - 非成员访问抛 NOT_MEMBER
 * - 空会话（0 成员）返回空列表
 * - 返回字段映射验证（LocalDateTime→epoch millis）
 */
class GroupMembersHandlerTest {

    private lateinit var conversationService: ConversationService
    private lateinit var handler: GroupMembersHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        handler = GroupMembersHandler(conversationService)
    }

    @Test
    fun getGroupMembersShouldReturnFullMemberInfo() = runTest {
        val now = LocalDateTime.now()

        val member0 = GroupMember.newBuilder()
            .setUid(1001L)
            .setUsername("alice")
            .setDisplayName("爱丽丝")
            .setAvatarUrl("http://a.com/1.png")
            .setRole("owner")
            .setJoinedAt(now.minusDays(7).atZone(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        val member1 = GroupMember.newBuilder()
            .setUid(1002L)
            .setUsername("bob")
            .setDisplayName("鲍勃")
            .setAvatarUrl("http://a.com/2.png")
            .setRole("member")
            .setJoinedAt(now.minusDays(3).atZone(ZoneOffset.UTC).toInstant().toEpochMilli())
            .build()
        val mockResp = GroupMembersResp.newBuilder()
            .addMembers(member0)
            .addMembers(member1)
            .build()

        coEvery { conversationService.getGroupMembers(any(), any()) } returns mockResp

        val req = GroupMembersReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(2, resp.membersCount)

        val firstMember = resp.membersList[0]
        assertEquals(1001L, firstMember.uid)
        assertEquals("alice", firstMember.username)
        assertEquals("爱丽丝", firstMember.displayName)
        assertEquals("http://a.com/1.png", firstMember.avatarUrl)
        assertEquals("owner", firstMember.role)

        val secondMember = resp.membersList[1]
        assertEquals(1002L, secondMember.uid)
        assertEquals("bob", secondMember.username)
        assertEquals("鲍勃", secondMember.displayName)
        assertEquals("http://a.com/2.png", secondMember.avatarUrl)
        assertEquals("member", secondMember.role)
    }

    @Test
    fun nonMemberAccessShouldThrowNotMember() = runTest {
        coEvery { conversationService.getGroupMembers(any(), any()) } throws ConversationException(BizCode.NOT_MEMBER)

        val req = GroupMembersReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    @Test
    fun emptyConversationShouldReturnEmptyList() = runTest {
        val emptyResp = GroupMembersResp.getDefaultInstance()
        coEvery { conversationService.getGroupMembers(any(), any()) } returns emptyResp

        val req = GroupMembersReq.newBuilder()
            .setConversationId("conv-empty")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(0, resp.membersCount)
    }

    @Test
    fun localDateTimeMappingShouldReturnEpochMillis() = runTest {
        val joinedTime = LocalDateTime.of(2024, 1, 15, 10, 30, 0)
        val expectedMillis = joinedTime.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

        val member = GroupMember.newBuilder()
            .setUid(1001L)
            .setUsername("testuser")
            .setDisplayName("测试用户")
            .setAvatarUrl("http://a.com/3.png")
            .setRole("owner")
            .setJoinedAt(expectedMillis)
            .build()
        val mockResp = GroupMembersResp.newBuilder().addMembers(member).build()
        coEvery { conversationService.getGroupMembers(any(), any()) } returns mockResp

        val req = GroupMembersReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(1, resp.membersCount)
        assertEquals(expectedMillis, resp.membersList[0].joinedAt)
    }
}
