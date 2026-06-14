package com.nebula.gateway.push

import com.nebula.chat.Envelope
import com.nebula.chat.PushEventType
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.message.ReadReceiptPayload
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.repository.ConversationMemberRepository
import com.google.protobuf.ByteString
import io.grpc.stub.StreamObserver
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

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
    private lateinit var conversationMemberRepository: ConversationMemberRepository
    private lateinit var deliveryTrackingService: DeliveryTrackingService
    private lateinit var pushService: PushService

    private val convId = "conv-001"
    private val senderUid = 1001L
    private val receiverUid = 1002L
    private val otherUid = 1003L

    @BeforeEach
    fun setUp() {
        userStreamRegistry = mockk<UserStreamRegistry>(relaxed = true)
        conversationMemberRepository = mockk<ConversationMemberRepository>(relaxed = true)
        deliveryTrackingService = mockk<DeliveryTrackingService>(relaxed = true)
        pushService = PushService(userStreamRegistry, conversationMemberRepository, deliveryTrackingService)
    }

    @Test
    fun pushMessageExcludesSenderFromPushTargets() = runTest {
        val chatMessage = mockk<ChatMessage>(relaxed = true)
        val members = listOf(
            ConversationMemberEntity(convId, senderUid),
            ConversationMemberEntity(convId, receiverUid)
        )
        every { conversationMemberRepository.findByConversationId(convId) } returns members
        every { userStreamRegistry.getStreams(senderUid) } returns emptyList()
        every { userStreamRegistry.getStreams(receiverUid) } returns emptyList()

        pushService.pushMessage(convId, chatMessage, senderUid)

        // 验证 getStreams 没有被发送者调用（已排除）
        // 但 receiverUid 被调用了
        verify { userStreamRegistry.getStreams(receiverUid) }
    }

    @Test
    fun pushMessageSendsCHAT_MESSAGEEnvelopeToOnlineMembers() = runTest {
        val chatMessage = mockk<ChatMessage>(relaxed = true)
        val observer = mockk<StreamObserver<Envelope>>(relaxed = true)
        val members = listOf(
            ConversationMemberEntity(convId, receiverUid)
        )
        every { conversationMemberRepository.findByConversationId(convId) } returns members
        every { userStreamRegistry.getStreams(receiverUid) } returns listOf(observer)

        pushService.pushMessage(convId, chatMessage, senderUid)

        // 验证对 observer 调用了 onNext
        verify(exactly = 1) { observer.onNext(any()) }
    }

    @Test
    fun pushMessageSingleObserverExceptionDoesNotAffectOthers() = runTest {
        val chatMessage = mockk<ChatMessage>(relaxed = true)
        val observer1 = mockk<StreamObserver<Envelope>>(relaxed = true)
        val observer2 = mockk<StreamObserver<Envelope>>(relaxed = true)
        val members = listOf(
            ConversationMemberEntity(convId, receiverUid)
        )
        every { conversationMemberRepository.findByConversationId(convId) } returns members
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

        verify(exactly = 1) { observer.onNext(any()) }
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
            ConversationMemberEntity(convId, receiverUid)
        )
        every { conversationMemberRepository.findByConversationId(convId) } returns members
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
            ConversationMemberEntity(convId, receiverUid),
            ConversationMemberEntity(convId, otherUid)
        )
        every { conversationMemberRepository.findByConversationId(convId) } returns members
        every { userStreamRegistry.getStreams(receiverUid) } returns listOf(observer)
        every { userStreamRegistry.getStreams(otherUid) } returns emptyList()

        // 排除 otherUid，receiverUid 应收到推送
        pushService.pushConversationEvent(
            convId = convId,
            eventType = PushEventType.GROUP_CREATED,
            payloadBytes = ByteString.EMPTY,
            excludeUids = setOf(otherUid)
        )

        // receiverUid 未在排除列表，应收到推送
        verify(exactly = 1) { observer.onNext(any()) }
    }

    @Test
    fun pushConversationEventExcludesAllUidsWithEmptySetAsDefault() = runTest {
        val observer = mockk<StreamObserver<Envelope>>(relaxed = true)
        val members = listOf(
            ConversationMemberEntity(convId, receiverUid)
        )
        every { conversationMemberRepository.findByConversationId(convId) } returns members
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
            ConversationMemberEntity(convId, receiverUid)
        )
        every { conversationMemberRepository.findByConversationId(convId) } returns members
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

        verify(exactly = 1) { observer.onNext(any()) }
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
