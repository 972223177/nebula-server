package com.nebula.gateway.push

import com.nebula.chat.Direction
import com.nebula.chat.Envelope
import com.nebula.chat.Message
import com.nebula.chat.PushEventType
import com.nebula.chat.message.ChatMessage
import com.nebula.chat.message.ReadReceiptPayload
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.repository.ConversationMemberRepository
import io.github.oshai.kotlinlogging.KotlinLogging

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
 */
class PushService(
    private val userStreamRegistry: UserStreamRegistry,
    private val conversationMemberRepository: ConversationMemberRepository
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
        val members = conversationMemberRepository.findByConversationId(convId)
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

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
