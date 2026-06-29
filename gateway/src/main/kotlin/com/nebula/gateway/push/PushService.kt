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
import com.nebula.gateway.session.DeliverableStreamObserver
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.service.conversation.ConversationService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.grpc.stub.StreamObserver
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
 * - 通过 UserStreamRegistry 查找在线设备，对每个设备通过 deliver() 串行化投递（D-02 多设备, G-03 修复）
 * - 单个 observer 推送异常时 try-catch 保护，不影响其他 observer（D-05 容错）
 * - 不自行判断推送权限，由调用方（SendMessageHandler/ReadReportHandler）保证仅推送给验证过的成员
 *
 * @param userStreamRegistry 用户 StreamObserver 注册中心
 * @param conversationService 会话成员查询服务
 * @param deliveryTrackingService 投递三态跟踪服务（D-70 ~ D-72）
 */
class PushService(
    private val userStreamRegistry: UserStreamRegistry,
    private val conversationService: ConversationService,
    private val deliveryTrackingService: DeliveryTrackingService
) {
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
                deliverEnvelope(observer, envelope)
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
                deliverEnvelope(observer, envelope)
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
     * 通过 Mutex 串行化投递 Envelope（G-03/C-01 修复）。
     *
     * 优先走 [DeliverableStreamObserver.deliver]（内部通过 Mutex 串行化 onNext），
     * 降级为裸 onNext() 以兼容测试 mock 或其他非 DeliverableStreamObserver 实现。
     *
     * @param observer 目标 StreamObserver
     * @param envelope 待投递的 Envelope
     */
    private fun deliverEnvelope(observer: StreamObserver<Envelope>, envelope: Envelope) {
        (observer as? DeliverableStreamObserver)?.deliver(envelope) ?: observer.onNext(envelope)
    }

    /**
     * 向指定成员列表推送 ChatMessage（M29: 复用批量查询结果，避免二次 DB 查询）。
     *
     * 与 [pushMessage] 的区别：本方法接受预查询的成员 userId 列表，而非通过 conversationService 再次查询。
     *
     * @param targetUids 目标用户 ID 列表（已过滤 excludeUid）
     * @param chatMessage 待推送的 ChatMessage
     */
    suspend fun pushMessageToMembers(targetUids: List<Long>, chatMessage: ChatMessage) {
        // R-10: 收集成功投递的 uid，循环结束后批量标记 sent 状态
        val sentUids = mutableListOf<Long>()
        for (uid in targetUids) {
            val observers = userStreamRegistry.getStreams(uid)
            var sent = false
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
                    deliverEnvelope(observer, envelope)
                    sent = true
                } catch (e: Exception) {
                    logger.error(e) { "Failed to push CHAT_MESSAGE to userId=$uid" }
                    userStreamRegistry.removeStream(uid, observer)
                }
            }
            if (sent) sentUids.add(uid)
        }
        // R-10: 批量标记投递状态，N 次 Redis 往返合并为 1 次 HMSET
        if (sentUids.isNotEmpty()) {
            deliveryTrackingService.batchMarkSent(chatMessage.msgId, sentUids)
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
            conversationService.getConversationMembers(convId)
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
                    deliverEnvelope(observer, envelope)
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
                deliverEnvelope(observer, envelope)
            } catch (e: Exception) {
                // D-05 容错：单个 observer 推送异常不影响其他 observer
                logger.error(e) { "Failed to push $eventType to userId=$targetUid" }
                userStreamRegistry.removeStream(targetUid, observer)
            }
        }
    }
}
