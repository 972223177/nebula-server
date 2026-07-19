package com.nebula.gateway.push

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
import com.nebula.chat.Message
import com.nebula.chat.PushEventType
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
 * - pushMessageToMembers：向指定成员列表推送 ChatMessage Envelope（M29 复用批量查询结果）
 * - pushConversationEvent：向会话成员推送会话事件（群创建、成员变更等）
 * - pushReadReceipt：向发送者推送已读回执
 * - pushDeliveryAck：向发送者推送交付回执（由 DeliveryAckHandler 触发）
 * - pushEventToUser：向指定用户推送通用事件
 * - pushToAll：向所有在线客户端广播事件
 *
 * 推送策略：
 * - 通过 [UserStreamRegistry] 查找在线设备，通过 [DeliverableStreamObserver.deliver] 串行化投递（D-02 多设备, G-03）
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
        val envelope = buildPushEnvelope(PushEventType.READ_RECEIPT, payload.toByteString())
        val observers = userStreamRegistry.getStreams(senderUid)
        for (observer in observers) {
            try {
                deliverEnvelope(observer, envelope)
            } catch (e: Exception) {
                logger.error(e) { "Failed to push READ_RECEIPT to senderUid=$senderUid" }
                userStreamRegistry.removeStream(senderUid, observer)
            }
        }
    }

    /**
     * 预留方法 — 向发送者推送交付回执（DeliveryAck）（D-71）。
     *
     * ⚠️ 当前无人调用。配套的 DeliveryRecvHandler（处理客户端 DeliveryAck 上报请求）
     * 尚未实现（见 [com.nebula.gateway.delivery.DeliveryHandlerCollector]）。
     * 待 10-04 phase 完成 Handler 后，ReadReportHandler/入站回执处理器将调用此方法。
     *
     * @param senderUid 发送者 userId
     * @param msgId 消息 ID
     * @param convId 会话 ID
     */
    fun pushDeliveryAck(senderUid: Long, msgId: Long, convId: String) {
        val payload = DeliveryAckPayload.newBuilder()
            .setMsgId(msgId).setConversationId(convId).build()
        val envelope = buildPushEnvelope(PushEventType.DELIVERY_ACK, payload.toByteString())
        val observers = userStreamRegistry.getStreams(senderUid)
        for (observer in observers) {
            try {
                deliverEnvelope(observer, envelope)
            } catch (e: Exception) {
                logger.error(e) { "Failed to push DELIVERY_ACK to senderUid=$senderUid, msgId=$msgId" }
                userStreamRegistry.removeStream(senderUid, observer)
            }
        }
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    /**
     * 安全投递 Envelope — 通过 safe-cast 委托给 [DeliverableStreamObserver.deliver]（内含 Mutex 串行化）。
     *
     * 原始 observer 为 gRPC 生成的 StreamObserver，ChatStreamObserver 实现了 DeliverableStreamObserver，
     * 其 deliver() 内部通过 sendMutex.withLock 保证 onNext 串行化（G-03）。
     * 若 observer 不是 DeliverableStreamObserver（极端异常），直接裸调 onNext。
     */
    private fun deliverEnvelope(observer: StreamObserver<Envelope>, envelope: Envelope) {
        (observer as? DeliverableStreamObserver)?.deliver(envelope) ?: observer.onNext(envelope)
    }

    /**
     * L2: 构建 PUSH 方向 Envelope 的工厂方法。
     *
     * 消除 5 个推送方法中重复的 Envelope 构建代码。
     *
     * @param eventType 推送事件类型
     * @param payloadBytes 序列化后的 payload
     * @param content 可选内容文本，默认空
     */
    private fun buildPushEnvelope(
        eventType: PushEventType,
        payloadBytes: com.google.protobuf.ByteString,
        content: String = ""
    ): Envelope {
        return Envelope.newBuilder()
            .setDirection(Direction.PUSH)
            .setRequestId("")
            .setMessage(Message.newBuilder()
                .setEventType(eventType)
                .setContent(content)
                .setPayload(payloadBytes)
                .build())
            .build()
    }

    /**
     * 向指定成员列表推送 ChatMessage（M29: 复用批量查询结果，避免二次 DB 查询）。
     *
     * 调用方（如 SendMessageHandler 批量发送场景）已提前查询成员列表，
     * 本方法接收预查询的 targetUids，不再经 conversationService 重复查询。
     * 推送成功后调用 [DeliveryTrackingService.batchMarkSent] 记录投递状态。
     *
     * @param targetUids 目标用户 ID 列表（已过滤发送者自身）
     * @param chatMessage 待推送的 ChatMessage
     */
    suspend fun pushMessageToMembers(targetUids: List<Long>, chatMessage: ChatMessage) {
        val envelope = buildPushEnvelope(PushEventType.CHAT_MESSAGE, chatMessage.toByteString())
        val sentUids = mutableListOf<Long>()
        for (uid in targetUids) {
            val observers = userStreamRegistry.getStreams(uid)
            var sent = false
            for (observer in observers) {
                try {
                    deliverEnvelope(observer, envelope)
                    sent = true
                } catch (e: Exception) {
                    logger.error(e) { "Failed to push CHAT_MESSAGE to userId=$uid" }
                    userStreamRegistry.removeStream(uid, observer)
                }
            }
            if (sent) sentUids.add(uid)
        }
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

        val envelope = buildPushEnvelope(eventType, payloadBytes)
        for (member in targets) {
            val observers = userStreamRegistry.getStreams(member.userId)
            for (observer in observers) {
                try {
                    deliverEnvelope(observer, envelope)
                } catch (e: Exception) {
                    logger.error(e) { "Failed to push $eventType to userId=${member.userId}" }
                    userStreamRegistry.removeStream(member.userId, observer)
                }
            }
        }
    }

    /**
     * 向指定用户的所有在线设备推送事件（D-01）。
     *
     * 通过 [UserStreamRegistry.getStreams] 获取目标用户的所有在线设备，
     * 逐流投递，单流异常时 try-catch 保护。
     *
     * @param targetUid 目标用户 ID
     * @param eventType 推送事件类型
     * @param payloadBytes 序列化后的 Payload 字节
     */
    fun pushEventToUser(
        targetUid: Long,
        eventType: PushEventType,
        payloadBytes: com.google.protobuf.ByteString
    ) {
        val envelope = buildPushEnvelope(eventType, payloadBytes)
        val observers = userStreamRegistry.getStreams(targetUid)
        for (observer in observers) {
            try {
                deliverEnvelope(observer, envelope)
            } catch (e: Exception) {
                logger.error(e) { "Failed to push $eventType to userId=$targetUid" }
                userStreamRegistry.removeStream(targetUid, observer)
            }
        }
    }

    /**
     * 向所有在线客户端广播推送事件（如敏感词库重载完成通知）。
     *
     * 遍历 [UserStreamRegistry.getAllStreams] 返回的全体在线流，逐流串行化投递。
     * 单流异常时 try-catch 保护，不影响其他流。
     *
     * @param eventType 推送事件类型
     * @param payloadBytes 序列化后的 Payload 字节
     */
    fun pushToAll(
        eventType: PushEventType,
        payloadBytes: com.google.protobuf.ByteString
    ) {
        val envelope = buildPushEnvelope(eventType, payloadBytes)
        for (observer in userStreamRegistry.getAllStreams()) {
            try {
                deliverEnvelope(observer, envelope)
            } catch (e: Exception) {
                logger.error(e) { "Failed to push $eventType to a stream during broadcast" }
            }
        }
    }
}
