package com.nebula.gateway.push

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
import com.nebula.chat.PushEventType
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.message.ReadReceiptPayload
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.service.conversation.ConversationService
import com.nebula.service.conversation.ConversationMemberInfo
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * PushService 单元测试。
 *
 * 覆盖场景：
 * - pushMessage 排除 excludeUid
 * - pushMessage 对在线成员投递 CHAT_MESSAGE Envelope
 * - 单个 observer.onNext 异常不影响其他 observer（D-05 容错）
 * - pushReadReceipt 投递 READ_RECEIPT Envelope
 * - pushReadReceipt 异常容错
 * - pushConversationEvent 向会话成员推送事件（排除 excludeUids）
 * - pushEventToUser 向指定用户推送单独事件
 */
class PushServiceTest {

    private lateinit var userStreamRegistry: UserStreamRegistry
    private lateinit var conversationService: ConversationService
    private lateinit var deliveryTrackingService: DeliveryTrackingService
    private lateinit var pushService: PushService

    private val convId = "conv-001"
    private val senderUid = 1001L
    private val receiverUid = 1002L
    private val otherUid = 1003L

    @BeforeEach
    fun setUp() {
        userStreamRegistry = mockk<UserStreamRegistry>(relaxed = true)
        conversationService = mockk<ConversationService>(relaxed = true)
        deliveryTrackingService = mockk<DeliveryTrackingService>(relaxed = true)
        pushService = PushService(userStreamRegistry, conversationService, deliveryTrackingService)
    }

    @Test
    fun pushMessageExcludesSenderFromPushTargets() = runTest {
        val chatMessage = mockk<ChatMessage>(relaxed = true)
        val members = listOf(
            ConversationMemberInfo(userId = senderUid, role = "member"),
            ConversationMemberInfo(userId = receiverUid, role = "member")
        )
        coEvery { conversationService.getConversationMembers(convId) } returns members
        every { userStreamRegistry.getStreams(senderUid) } returns emptyList()
        every { userStreamRegistry.getStreams(receiverUid) } returns emptyList()

        pushService.pushMessage(convId, chatMessage, senderUid)

        // 验证 getStreams 没有被发送者调用（已排除）
        verify(exactly = 0) { userStreamRegistry.getStreams(senderUid) }
        // 但 receiverUid 被调用了
        verify { userStreamRegistry.getStreams(receiverUid) }
    }

    @Test
    fun pushMessageSendsCHAT_MESSAGEEnvelopeToOnlineMembers() = runTest {
        val chatMessage = mockk<ChatMessage>(relaxed = true)
        val observer = mockk<StreamObserver<Envelope>>(relaxed = true)
        val members = listOf(
            ConversationMemberInfo(userId = receiverUid, role = "member")
        )
        coEvery { conversationService.getConversationMembers(convId) } returns members
        every { userStreamRegistry.getStreams(receiverUid) } returns listOf(observer)

        pushService.pushMessage(convId, chatMessage, senderUid)

        // 捕获 Envelope 并验证 direction 和 pushEventType
        val envelopeSlot = slot<Envelope>()
        verify(exactly = 1) { observer.onNext(capture(envelopeSlot)) }
        val envelope = envelopeSlot.captured
        assertEquals(Direction.PUSH, envelope.direction, "direction 应为 PUSH")
        assertEquals(PushEventType.CHAT_MESSAGE, envelope.message.eventType, "pushEventType 应为 CHAT_MESSAGE")
        assertNotNull(envelope.message.payload, "payload 不应为空")
    }

    @Test
    fun pushMessageSingleObserverExceptionDoesNotAffectOthers() = runTest {
        val chatMessage = mockk<ChatMessage>(relaxed = true)
        val observer1 = mockk<StreamObserver<Envelope>>(relaxed = true)
        val observer2 = mockk<StreamObserver<Envelope>>(relaxed = true)
        val members = listOf(
            ConversationMemberInfo(userId = receiverUid, role = "member")
        )
        coEvery { conversationService.getConversationMembers(convId) } returns members
        every { userStreamRegistry.getStreams(receiverUid) } returns listOf(observer1, observer2)
        // observer1 首次 onNext 抛异常
        every { observer1.onNext(any()) } throws RuntimeException("push failed")

        pushService.pushMessage(convId, chatMessage, senderUid)

        // observer1 调用一次后抛出异常，进入 catch 分支
        verify(exactly = 1) { observer1.onNext(any()) }
        // observer2 仍然收到投递
        verify(exactly = 1) { observer2.onNext(any()) }
    }

    @Test
    fun pushReadReceiptSendsREAD_RECEIPTEnvelope() {
        val payload = mockk<ReadReceiptPayload>(relaxed = true)
        val observer = mockk<StreamObserver<Envelope>>(relaxed = true)
        every { userStreamRegistry.getStreams(senderUid) } returns listOf(observer)

        pushService.pushReadReceipt(senderUid, payload)

        // 捕获 Envelope 并验证 pushEventType
        val envelopeSlot = slot<Envelope>()
        verify(exactly = 1) { observer.onNext(capture(envelopeSlot)) }
        val envelope = envelopeSlot.captured
        assertEquals(Direction.PUSH, envelope.direction, "direction 应为 PUSH")
        assertEquals(PushEventType.READ_RECEIPT, envelope.message.eventType, "pushEventType 应为 READ_RECEIPT")
    }

    @Test
    fun pushReadReceiptHandlesExceptionGracefully() {
        val payload = mockk<ReadReceiptPayload>(relaxed = true)
        val observer1 = mockk<StreamObserver<Envelope>>(relaxed = true)
        val observer2 = mockk<StreamObserver<Envelope>>(relaxed = true)
        every { userStreamRegistry.getStreams(senderUid) } returns listOf(observer1, observer2)
        every { observer1.onNext(any()) } throws RuntimeException("push failed")

        pushService.pushReadReceipt(senderUid, payload)

        // observer2 仍然收到投递
        verify(exactly = 1) { observer2.onNext(any()) }
    }

    @Test
    fun pushMessageNoOnlineMembersDoesNothing() = runTest {
        val chatMessage = mockk<ChatMessage>(relaxed = true)
        val members = listOf(
            ConversationMemberInfo(userId = receiverUid, role = "member")
        )
        coEvery { conversationService.getConversationMembers(convId) } returns members
        every { userStreamRegistry.getStreams(receiverUid) } returns emptyList()

        pushService.pushMessage(convId, chatMessage, senderUid)
        // 没有 observer，getStreams 被调用但 onNext 从未被调用
        verify(exactly = 1) { userStreamRegistry.getStreams(receiverUid) }
    }

    // ========== Phase 7: pushConversationEvent + pushEventToUser ==========

    @Test
    fun pushConversationEventExcludesSpecifiedUidsFromPushTargets() = runTest {
        val observer = mockk<StreamObserver<Envelope>>(relaxed = true)
        val members = listOf(
            ConversationMemberInfo(userId = receiverUid, role = "member"),
            ConversationMemberInfo(userId = otherUid, role = "member")
        )
        coEvery { conversationService.getConversationMembers(convId) } returns members
        every { userStreamRegistry.getStreams(receiverUid) } returns listOf(observer)
        every { userStreamRegistry.getStreams(otherUid) } returns emptyList()

        // 排除 otherUid，receiverUid 应收到推送
        pushService.pushConversationEvent(
            convId = convId,
            eventType = PushEventType.GROUP_CREATED,
            payloadBytes = ByteString.EMPTY,
            excludeUids = setOf(otherUid)
        )

        // receiverUid 未在排除列表，应收到推送，且验证 Envelope 内容
        val envelopeSlot = slot<Envelope>()
        verify(exactly = 1) { observer.onNext(capture(envelopeSlot)) }
        assertEquals(
            PushEventType.GROUP_CREATED,
            envelopeSlot.captured.message.eventType,
            "pushEventType 应为 GROUP_CREATED"
        )
    }

    @Test
    fun pushConversationEventExcludesAllUidsWithEmptySetAsDefault() = runTest {
        val observer = mockk<StreamObserver<Envelope>>(relaxed = true)
        val members = listOf(
            ConversationMemberInfo(userId = receiverUid, role = "member")
        )
        coEvery { conversationService.getConversationMembers(convId) } returns members
        every { userStreamRegistry.getStreams(receiverUid) } returns listOf(observer)

        // 默认 excludeUids = emptySet，所有成员均收到推送
        pushService.pushConversationEvent(
            convId = convId,
            eventType = PushEventType.GROUP_UPDATED,
            payloadBytes = ByteString.EMPTY
        )

        verify(exactly = 1) { observer.onNext(any()) }
    }

    @Test
    fun pushConversationEventHandlesObserverExceptionGracefully() = runTest {
        val observer1 = mockk<StreamObserver<Envelope>>(relaxed = true)
        val observer2 = mockk<StreamObserver<Envelope>>(relaxed = true)
        val members = listOf(
            ConversationMemberInfo(userId = receiverUid, role = "member")
        )
        coEvery { conversationService.getConversationMembers(convId) } returns members
        every { userStreamRegistry.getStreams(receiverUid) } returns listOf(observer1, observer2)
        // observer1 异常
        every { observer1.onNext(any()) } throws RuntimeException("push failed")

        pushService.pushConversationEvent(
            convId = convId,
            eventType = PushEventType.GROUP_CREATED,
            payloadBytes = ByteString.EMPTY
        )

        // observer1 调用一次后异常，catch 后移除失败流
        verify(exactly = 1) { observer1.onNext(any()) }
        // observer2 仍正常接收
        verify(exactly = 1) { observer2.onNext(any()) }
    }

    @Test
    fun pushEventToUserSendsEventToSpecifiedUser() {
        val observer = mockk<StreamObserver<Envelope>>(relaxed = true)
        every { userStreamRegistry.getStreams(receiverUid) } returns listOf(observer)

        pushService.pushEventToUser(
            targetUid = receiverUid,
            eventType = PushEventType.MEMBER_KICKED,
            payloadBytes = ByteString.EMPTY
        )

        // 捕获 Envelope 并验证 eventType 和 payload
        val envelopeSlot = slot<Envelope>()
        verify(exactly = 1) { observer.onNext(capture(envelopeSlot)) }
        val envelope = envelopeSlot.captured
        assertEquals(PushEventType.MEMBER_KICKED, envelope.message.eventType, "pushEventType 应为 MEMBER_KICKED")
        assertEquals(ByteString.EMPTY, envelope.message.payload, "payload 应为传入的 ByteString.EMPTY")
    }

    @Test
    fun pushEventToUserHandlesExceptionGracefully() {
        val observer1 = mockk<StreamObserver<Envelope>>(relaxed = true)
        val observer2 = mockk<StreamObserver<Envelope>>(relaxed = true)
        every { userStreamRegistry.getStreams(senderUid) } returns listOf(observer1, observer2)
        every { observer1.onNext(any()) } throws RuntimeException("push failed")

        pushService.pushEventToUser(
            targetUid = senderUid,
            eventType = PushEventType.MEMBER_KICKED,
            payloadBytes = ByteString.EMPTY
        )

        // observer2 仍正常接收
        verify(exactly = 1) { observer2.onNext(any()) }
    }
}
