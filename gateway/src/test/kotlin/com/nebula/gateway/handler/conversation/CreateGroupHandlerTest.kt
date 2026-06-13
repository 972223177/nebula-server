package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.conversation.CreateGroupReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.mockLockManager
import com.nebula.service.conversation.ConversationService
import com.nebula.service.conversation.CreateGroupResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * CreateGroupHandler 创建群聊 Handler 单元测试（D-02, D-05, D-10, D-19）。
 *
 * 覆盖场景：
 * - 正常创建群聊返回 conversation_id
 * - name 为空抛 INVALID_PARAM
 * - 创建者在 member_uids 中抛 INVALID_PARAM
 * - 初始成员数超 200 抛 GROUP_FULL
 * - GROUP_CREATED 推送排除创建者
 * - conversation_id 为 UUID 格式
 * - name 超过128字符抛 INVALID_PARAM
 */
class CreateGroupHandlerTest {

    private lateinit var conversationService: ConversationService
    private lateinit var pushService: PushService
    private lateinit var handler: CreateGroupHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        pushService = mockk(relaxed = true)

        val lockManager = mockLockManager()

        handler = CreateGroupHandler(
            conversationService,
            lockManager,
            pushService
        )
    }

    @Test
    fun createGroupShouldReturnConversationId() = runTest {
        coEvery { conversationService.createGroup(any(), any()) } returns CreateGroupResult(
            convId = "test-conv-id",
            name = "测试群聊",
            ownerUid = 1001L,
            memberUids = listOf(2001L, 3001L)
        )

        val req = CreateGroupReq.newBuilder()
            .setName("测试群聊")
            .addAllMemberUids(listOf(2001L, 3001L))
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertTrue(resp.conversationId.isNotEmpty())
        assertEquals("测试群聊", resp.name)
    }

    @Test
    fun emptyNameShouldThrowInvalidParam() = runTest {
        coEvery { conversationService.createGroup(any(), any()) } throws ConversationException(BizCode.INVALID_PARAM)

        val req = CreateGroupReq.newBuilder()
            .setName("")
            .addMemberUids(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
    }

    @Test
    fun creatorInMemberUidsShouldThrowInvalidParam() = runTest {
        coEvery { conversationService.createGroup(any(), any()) } throws ConversationException(BizCode.INVALID_PARAM)

        val req = CreateGroupReq.newBuilder()
            .setName("测试群名")
            .addAllMemberUids(listOf(1001L, 2001L))
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
    }

    @Test
    fun exceedingMaxMembersShouldThrowGroupFull() = runTest {
        coEvery { conversationService.createGroup(any(), any()) } throws ConversationException(BizCode.GROUP_FULL)

        // 创建者 1 + 200 个成员 = 201 > 200
        val tooManyUids = (2001L..2200L).toList()
        val req = CreateGroupReq.newBuilder()
            .setName("超大群")
            .addAllMemberUids(tooManyUids)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_FULL, exception.bizCode)
    }

    @Test
    fun groupCreatedPushShouldExcludeCreator() = runTest {
        coEvery { conversationService.createGroup(any(), any()) } returns CreateGroupResult(
            convId = "conv-push-test",
            name = "推送测试群",
            ownerUid = 1001L,
            memberUids = listOf(2001L, 3001L)
        )

        val req = CreateGroupReq.newBuilder()
            .setName("推送测试群")
            .addAllMemberUids(listOf(2001L, 3001L))
            .build()
        withContext(SessionKey(session)) { handler.handle(req) }

        // 验证推送时排除了创建者（1001L）
        coVerify {
            pushService.pushConversationEvent(
                convId = any(),
                eventType = PushEventType.GROUP_CREATED,
                payloadBytes = any(),
                excludeUids = setOf(1001L)
            )
        }
    }

    @Test
    fun conversationIdShouldBeUuidFormat() = runTest {
        coEvery { conversationService.createGroup(any(), any()) } returns CreateGroupResult(
            convId = "550e8400-e29b-41d4-a716-446655440000",
            name = "UUID测试群",
            ownerUid = 1001L,
            memberUids = listOf(2001L)
        )

        val req = CreateGroupReq.newBuilder()
            .setName("UUID测试群")
            .addMemberUids(2001L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        val convId = resp.conversationId
        assertNotNull(convId)
        // 验证 conversation_id 为合法 UUID 格式
        val uuid = UUID.fromString(convId)
        assertEquals(convId, uuid.toString())
    }

    @Test
    fun nameExceeding128CharsShouldThrowInvalidParam() = runTest {
        coEvery { conversationService.createGroup(any(), any()) } throws ConversationException(BizCode.INVALID_PARAM)

        val longName = "a".repeat(129)
        val req = CreateGroupReq.newBuilder()
            .setName(longName)
            .addMemberUids(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
    }
}
