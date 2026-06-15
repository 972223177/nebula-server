package com.nebula.gateway.push

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
import com.nebula.chat.Message
import com.nebula.chat.PushEventType
import com.nebula.chat.conversation.GroupCreatedPayload
import com.nebula.chat.conversation.GroupDissolvedPayload
import com.nebula.chat.conversation.GroupUpdatedPayload
import com.nebula.chat.conversation.MemberJoinedPayload
import com.nebula.chat.conversation.MemberKickedPayload
import com.nebula.chat.conversation.MemberLeftPayload
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.message.DeliveryAckPayload
import com.nebula.chat.message.ReadReceiptPayload
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.repository.ConversationMemberRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 消息推送服务（D-11, D-12, D-15）。
 *
 * 职责：
 * - pushMessage：向会话成员推送 ChatMessage Envelope（D-09 排除发送者）
 * - pushReadReceipt：向发送者推送 ReadReceiptPayload Envelope
 *
 * 推送策略：
 * - 通过 UserStreamRegistry 查找在线设备，对每个设备调用 StreamObserver.onNext（D-02 多设备）
 * - 单个 observer 推送异常时 try-catch 保护，不影响其他 observer（D-05 容错）
 * - 不自行判断推送权限，由调用方（SendMessageHandler/ReadReportHandler）保证仅推送给验证过的成员
 *
 * @param userStreamRegistry 用户 StreamObserver 注册中心
 * @param conversationMemberRepository 会话成员查询接口
 * @param deliveryTrackingService 投递三态跟踪服务（D-70 ~ D-72）
 */
class PushService(
    private val userStreamRegistry: UserStreamRegistry,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val deliveryTrackingService: DeliveryTrackingService
) {
    /**
     * 向会话成员推送 ChatMessage 消息（D-09, D-11, D-12）。
     *
     * 流程：
     * 1. 通过 conversationMemberRepository 查询会话成员列表
     * 2. 过滤掉 excludeUid 对应的发送者（D-09）
     * 3. 逐个遍历在线成员，构建 PUSH Envelope 并投递
     *
     * TODO(REVIEW-MEDIUM-4): findByConversationId 是 blocking JPA 调用，
     * 应考虑使用 withContext(Dispatchers.IO) 包裹以避免阻塞协程线程。
     *
     * @param convId 会话 ID
     * @param chatMessage 待推送的 ChatMessage
     * @param excludeUid 排除的发送者 userId，不向自己推送
     */
    suspend fun pushMessage(convId: String, chatMessage: ChatMessage, excludeUid: Long) {
        // D-84/M18: 包裹 withContext(Dispatchers.IO) 避免阻塞协程线程
        val members = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationId(convId)
        }
        val targets = members.filter { it.userId != excludeUid }

        for (member in targets) {
            val observers = userStreamRegistry.getStreams(member.userId)
            for (observer in observers) {
                try {
                    val envelope = Envelope.newBuilder()
                        .setDirection(Direction.PUSH)
                        .setRequestId("")
                        .setMessage(Message.newBuilder()
                            .setEventType(PushEventType.CHAT_MESSAGE)
                            .setContent("")
                            .setPayload(chatMessage.toByteString())
                            .build())
                        .build()
                    observer.onNext(envelope)
                    // D-70: 推送成功后标记为 sent 状态
                    deliveryTrackingService.markSent(chatMessage.msgId, member.userId)
                } catch (e: Exception) {
                    // D-05 容错：单个 observer 推送异常不影响其他 observer
                    logger.error(e) { "Failed to push CHAT_MESSAGE to userId=${member.userId}" }
                    userStreamRegistry.removeStream(member.userId, observer)
                }
            }
        }
    }

    /**
     * 向发送者推送已读回执 Envelope（D-15）。
     *
     * 非 suspend 函数 — 操作仅涉及内存（UserStreamRegistry.getStreams 返回快照列表），无 I/O。
     * 注意：getStreams 返回的快照可能在迭代和 onNext 之间有过期流，通过 per-observer try-catch 处理。
     *
     * @param senderUid 发送者 userId
     * @param payload 已读回执 payload
     */
    fun pushReadReceipt(senderUid: Long, payload: ReadReceiptPayload) {
        val observers = userStreamRegistry.getStreams(senderUid)
        for (observer in observers) {
            try {
                val envelope = Envelope.newBuilder()
                    .setDirection(Direction.PUSH)
                    .setRequestId("")
                    .setMessage(Message.newBuilder()
                        .setEventType(PushEventType.READ_RECEIPT)
                        .setContent("")
                        .setPayload(payload.toByteString())
                        .build())
                    .build()
                observer.onNext(envelope)
            } catch (e: Exception) {
                // D-05 容错：单个 observer 推送异常不影响其他 observer
                logger.error(e) { "Failed to push READ_RECEIPT to senderUid=$senderUid" }
                userStreamRegistry.removeStream(senderUid, observer)
            }
        }
    }

    /**
     * 向发送者推送交付回执（DeliveryAck）（D-71）。
     *
     * 收到接收者客户端回执后调用，告知发送者消息已送达接收方设备。
     * 非 suspend 函数 — 操作仅涉及内存（UserStreamRegistry.getStreams 返回快照列表），无 I/O。
     *
     * @param senderUid 发送者 userId
     * @param msgId 消息 ID
     * @param convId 会话 ID
     */
    fun pushDeliveryAck(senderUid: Long, msgId: Long, convId: String) {
        val observers = userStreamRegistry.getStreams(senderUid)
        val payload = DeliveryAckPayload.newBuilder()
            .setMsgId(msgId)
            .setConversationId(convId)
            .build()
        for (observer in observers) {
            try {
                val envelope = Envelope.newBuilder()
                    .setDirection(Direction.PUSH)
                    .setRequestId("")
                    .setMessage(Message.newBuilder()
                        .setEventType(PushEventType.DELIVERY_ACK)
                        .setContent("")
                        .setPayload(payload.toByteString())
                        .build())
                    .build()
                observer.onNext(envelope)
            } catch (e: Exception) {
                // D-05 容错：单个 observer 推送异常不影响其他 observer
                logger.error(e) { "Failed to push DELIVERY_ACK to senderUid=$senderUid, msgId=$msgId" }
                userStreamRegistry.removeStream(senderUid, observer)
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * 向指定成员列表推送 ChatMessage（M29: 复用批量查询结果，避免二次 DB 查询）。
     *
     * 与 [pushMessage] 的区别：本方法接受预查询的成员 userId 列表，而非通过 conversationMemberRepository 再次查询。
     *
     * @param targetUids 目标用户 ID 列表（已过滤 excludeUid）
     * @param chatMessage 待推送的 ChatMessage
     */
    fun pushMessageToMembers(targetUids: List<Long>, chatMessage: ChatMessage) {
        for (uid in targetUids) {
            val observers = userStreamRegistry.getStreams(uid)
            for (observer in observers) {
                try {
                    val envelope = Envelope.newBuilder()
                        .setDirection(Direction.PUSH)
                        .setRequestId("")
                        .setMessage(Message.newBuilder()
                            .setEventType(PushEventType.CHAT_MESSAGE)
                            .setContent("")
                            .setPayload(chatMessage.toByteString())
                            .build())
                        .build()
                    observer.onNext(envelope)
                    deliveryTrackingService.markSent(chatMessage.msgId, uid)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to push CHAT_MESSAGE to userId=$uid" }
                    userStreamRegistry.removeStream(uid, observer)
                }
            }
        }
    }

    /**
     * 向会话所有成员推送会话事件（D-11, D-18）。
     *
     * 遍历会话成员列表（排除 excludeUids），为每个成员的所有在线设备构建 PUSH Envelope。
     * 单个 observer 推送异常时 try-catch 保护，不影响其他 observer（D-05 容错）。
     *
     * @param convId 会话 ID
     * @param eventType PushEventType 事件类型（如 GROUP_CREATED、MEMBER_JOINED 等）
     * @param payloadBytes 序列化后的 Payload 字节
     * @param excludeUids 排除的用户 ID 列表（如事件发起者），默认空
     */
    suspend fun pushConversationEvent(
        convId: String,
        eventType: PushEventType,
        payloadBytes: com.google.protobuf.ByteString,
        excludeUids: Set<Long> = emptySet()
    ) {
        // D-84/M18: 包裹 withContext(Dispatchers.IO) 避免阻塞协程线程
        val members = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationId(convId)
        }
        val targets = members.filter { it.userId !in excludeUids }

        for (member in targets) {
            val observers = userStreamRegistry.getStreams(member.userId)
            for (observer in observers) {
                try {
                    val envelope = Envelope.newBuilder()
                        .setDirection(Direction.PUSH)
                        .setRequestId("")
                        .setMessage(Message.newBuilder()
                            .setEventType(eventType)
                            .setContent("")
                            .setPayload(payloadBytes)
                            .build())
                        .build()
                    observer.onNext(envelope)
                } catch (e: Exception) {
                    // D-05 容错：单个 observer 推送异常不影响其他 observer
                    logger.error(e) { "Failed to push $eventType to userId=${member.userId}" }
                    userStreamRegistry.removeStream(member.userId, observer)
                }
            }
        }
    }

    /**
     * 向指定用户推送单独事件（D-14 踢人时推送给被踢者本人）。
     *
     * 与 pushConversationEvent 不同，此方法精确推送到指定 userId 的所有在线设备，
     * 用于需要区分推送目标的场景（如 MEMBER_KICKED 仅推送给被踢者）。
     *
     * @param targetUid 目标用户 ID
     * @param eventType PushEventType 事件类型
     * @param payloadBytes 序列化后的 Payload 字节
     */
    fun pushEventToUser(
        targetUid: Long,
        eventType: PushEventType,
        payloadBytes: com.google.protobuf.ByteString
    ) {
        val observers = userStreamRegistry.getStreams(targetUid)
        for (observer in observers) {
            try {
                val envelope = Envelope.newBuilder()
                    .setDirection(Direction.PUSH)
                    .setRequestId("")
                    .setMessage(Message.newBuilder()
                        .setEventType(eventType)
                        .setContent("")
                        .setPayload(payloadBytes)
                        .build())
                    .build()
                observer.onNext(envelope)
            } catch (e: Exception) {
                // D-05 容错：单个 observer 推送异常不影响其他 observer
                logger.error(e) { "Failed to push $eventType to userId=$targetUid" }
                userStreamRegistry.removeStream(targetUid, observer)
            }
        }
    }
}
