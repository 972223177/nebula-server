package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.conversation.CreateGroupReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.transaction.support.TransactionTemplate
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

    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var lockManager: ConversationLockManager
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var pushService: PushService
    private lateinit var handler: CreateGroupHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        lockManager = mockk()
        transactionTemplate = mockk()
        pushService = mockk(relaxed = true)

        // Mock 锁管理器：直接执行代码块
        coEvery { lockManager.withLock(any(), any<suspend () -> kotlin.Any>()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[1] as suspend () -> kotlin.Any).invoke()
        }

        // Mock 事务模板：在事务内执行回调
        every { transactionTemplate.execute(any<org.springframework.transaction.support.TransactionCallback<Any?>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            (it.invocation.args[0] as org.springframework.transaction.support.TransactionCallback<Any?>)
                .doInTransaction(mockk(relaxed = true))
        }

        handler = CreateGroupHandler(
            conversationRepository,
            conversationMemberRepository,
            lockManager,
            transactionTemplate,
            pushService
        )
    }

    @Test
    fun `正常创建群聊返回conversationId`() = runTest {
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

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
    fun `name为空抛INVALID_PARAM`() = runTest {
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
    fun `创建者在memberUids中抛INVALID_PARAM`() = runTest {
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
    fun `初始成员数超200抛GROUP_FULL`() = runTest {
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
    fun `GROUP_CREATED推送排除创建者`() = runTest {
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

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
    fun `conversationId为UUID格式`() = runTest {
        every { conversationRepository.save(any<ConversationEntity>()) } answers { firstArg() }
        every { conversationMemberRepository.save(any<ConversationMemberEntity>()) } answers { firstArg() }

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
    fun `name超过128字符抛INVALID_PARAM`() = runTest {
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
