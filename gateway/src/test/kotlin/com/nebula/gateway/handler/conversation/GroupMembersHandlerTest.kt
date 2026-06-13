package com.nebula.gateway.handler.conversation

import com.nebula.chat.conversation.GroupMembersReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.UserRepository
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
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var userRepository: UserRepository
    private lateinit var handler: GroupMembersHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        conversationMemberRepository = mockk()
        userRepository = mockk()
        handler = GroupMembersHandler(conversationService)
    }

    @Test
    fun `正常返回成员列表含用户名显示名头像角色加入时间`() = runTest {
        val now = LocalDateTime.now()

        // 请求者是会话成员
        val selfMember = ConversationMemberEntity("conv-001", 1001L).apply {
            role = "owner"
            joinedAt = now.minusDays(7)
        }
        val otherMember = ConversationMemberEntity("conv-001", 1002L).apply {
            role = "member"
            joinedAt = now.minusDays(3)
        }

        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns selfMember
        every {
            conversationMemberRepository.findByConversationId("conv-001")
        } returns listOf(selfMember, otherMember)
        every {
            userRepository.findAllById(listOf(1001L, 1002L))
        } returns listOf(
            UserEntity("alice", "hash1", "爱丽丝").apply { id = 1001L; avatar = "http://a.com/1.png" },
            UserEntity("bob", "hash2", "鲍勃").apply { id = 1002L; avatar = "http://a.com/2.png" }
        )

        val req = GroupMembersReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(2, resp.membersCount)

        // 第一个成员（selfMember, role=owner）
        val member0 = resp.membersList[0]
        assertEquals(1001L, member0.uid)
        assertEquals("alice", member0.username)
        assertEquals("爱丽丝", member0.displayName)
        assertEquals("http://a.com/1.png", member0.avatarUrl)
        assertEquals("owner", member0.role)
        assertEquals(
            now.minusDays(7).atZone(ZoneOffset.UTC).toInstant().toEpochMilli(),
            member0.joinedAt
        )

        // 第二个成员（otherMember, role=member）
        val member1 = resp.membersList[1]
        assertEquals(1002L, member1.uid)
        assertEquals("bob", member1.username)
        assertEquals("鲍勃", member1.displayName)
        assertEquals("http://a.com/2.png", member1.avatarUrl)
        assertEquals("member", member1.role)
        assertEquals(
            now.minusDays(3).atZone(ZoneOffset.UTC).toInstant().toEpochMilli(),
            member1.joinedAt
        )
    }

    @Test
    fun `非成员访问抛NOT_MEMBER`() = runTest {
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns null

        val req = GroupMembersReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    @Test
    fun `空会话0成员返回空列表`() = runTest {
        val selfMember = ConversationMemberEntity("conv-empty", 1001L)

        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-empty", 1001L)
        } returns selfMember
        every {
            conversationMemberRepository.findByConversationId("conv-empty")
        } returns emptyList()

        val req = GroupMembersReq.newBuilder()
            .setConversationId("conv-empty")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(0, resp.membersCount)
    }

    @Test
    fun `返回字段映射验证LocalDateTime转epochMillis`() = runTest {
        val joinedTime = LocalDateTime.of(2024, 1, 15, 10, 30, 0)
        val expectedMillis = joinedTime.atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

        val member = ConversationMemberEntity("conv-001", 1001L).apply {
            role = "owner"
            joinedAt = joinedTime
        }

        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns member
        every {
            conversationMemberRepository.findByConversationId("conv-001")
        } returns listOf(member)
        every {
            userRepository.findAllById(listOf(1001L))
        } returns listOf(
            UserEntity("testuser", "hash", "测试用户").apply { id = 1001L; avatar = "http://a.com/3.png" }
        )

        val req = GroupMembersReq.newBuilder()
            .setConversationId("conv-001")
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(1, resp.membersCount)
        assertEquals(expectedMillis, resp.membersList[0].joinedAt)
    }
}
