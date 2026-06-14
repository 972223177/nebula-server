package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.KickMemberReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.Session
import com.nebula.gateway.testutil.mockLockManager
import com.nebula.gateway.testutil.mockTransactionTemplate
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.service.conversation.ConversationService
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
 * KickMemberHandler 踢出成员 Handler 单元测试（D-04, D-14, D-19）。
 *
 * 覆盖场景：
 * - 正常踢人 → 软删除，推送 MEMBER_KICKED + MEMBER_LEFT
 * - 踢群主抛 GROUP_PERM_DENIED
 * - 踢自己抛 INVALID_PARAM
 * - 非群主踢人抛 GROUP_PERM_DENIED
 * - 被踢者非成员抛 NOT_MEMBER
 * - 群已解散抛 GROUP_DISSOLVED
 */
class KickMemberHandlerTest {

    private lateinit var conversationService: ConversationService
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var pushService: PushService
    private lateinit var handler: KickMemberHandler

    private val session = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

    @BeforeEach
    fun setUp() {
        conversationService = mockk()
        conversationRepository = mockk()
        conversationMemberRepository = mockk()
        pushService = mockk(relaxed = true)

        val lockManager = mockLockManager()
        val transactionTemplate = mockTransactionTemplate()

        handler = KickMemberHandler(
            conversationService,
            lockManager,
            transactionTemplate,
            pushService,
            conversationMemberRepository
        )
    }

    @Test
    fun kickMemberShouldSoftDeleteAndPushEvents() = runTest {
        coEvery { conversationService.kickMember(any(), any()) } returns 2001L

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val resp = withContext(SessionKey(session)) { handler.handle(req) }

        assertNotNull(resp)
        assertEquals(BizCode.OK.code, resp.code)

        // 验证推送 MEMBER_KICKED 给被踢者
        verify {
            pushService.pushEventToUser(
                targetUid = 2001L,
                eventType = PushEventType.MEMBER_KICKED,
                payloadBytes = any()
            )
        }

        // 验证推送 MEMBER_LEFT 给剩余成员
        coVerify {
            pushService.pushConversationEvent(
                convId = "conv-001",
                eventType = PushEventType.MEMBER_LEFT,
                payloadBytes = any(),
                excludeUids = setOf(2001L)
            )
        }
    }

    @Test
    fun kickOwnerShouldThrowGroupPermDenied() = runTest {
        coEvery { conversationService.kickMember(any(), any()) } throws ConversationException(BizCode.GROUP_PERM_DENIED)

        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "owner" }
        val ownerTargetMember = ConversationMemberEntity("conv-001", 2001L).apply { role = "owner" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 2001L)
        } returns ownerTargetMember

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_PERM_DENIED, exception.bizCode)
    }

    @Test
    fun kickSelfShouldThrowInvalidParam() = runTest {
        coEvery { conversationService.kickMember(any(), any()) } throws ConversationException(BizCode.INVALID_PARAM)

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(1001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.INVALID_PARAM, exception.bizCode)
    }

    @Test
    fun nonOwnerKickShouldThrowGroupPermDenied() = runTest {
        coEvery { conversationService.kickMember(any(), any()) } throws ConversationException(BizCode.GROUP_PERM_DENIED)

        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val member = ConversationMemberEntity("conv-001", 1001L).apply { role = "member" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns member

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_PERM_DENIED, exception.bizCode)
    }

    @Test
    fun targetNonMemberShouldThrowNotMember() = runTest {
        coEvery { conversationService.kickMember(any(), any()) } throws ConversationException(BizCode.NOT_MEMBER)

        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 0; memberCount = 5
        }
        val ownerMember = ConversationMemberEntity("conv-001", 1001L).apply { role = "owner" }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 1001L)
        } returns ownerMember
        every {
            conversationMemberRepository.findByConversationIdAndUserId("conv-001", 2001L)
        } returns null

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.NOT_MEMBER, exception.bizCode)
    }

    @Test
    fun kickConvNotFoundShouldThrowConvNotFound() = runTest {
        coEvery { conversationService.kickMember(any(), any()) } throws ConversationException(BizCode.CONV_NOT_FOUND)

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-missing")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.CONV_NOT_FOUND, exception.bizCode)
    }

    @Test
    fun kickDissolvedGroupShouldThrowGroupDissolved() = runTest {
        coEvery { conversationService.kickMember(any(), any()) } throws ConversationException(BizCode.GROUP_DISSOLVED)

        val convEntity = ConversationEntity(type = 2).apply {
            id = "conv-001"; status = 1; memberCount = 5
        }

        every { conversationRepository.findById("conv-001") } returns Optional.of(convEntity)

        val req = KickMemberReq.newBuilder()
            .setConversationId("conv-001")
            .setUid(2001L)
            .build()
        val exception = assertFailsWith<ConversationException> {
            withContext(SessionKey(session)) { handler.handle(req) }
        }

        assertEquals(BizCode.GROUP_DISSOLVED, exception.bizCode)
    }
}
