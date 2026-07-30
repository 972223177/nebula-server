package com.nebula.gateway.handler.message
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.Response
import com.nebula.chat.message.ReadReceiptPayload
import com.nebula.chat.message.ReadReportReq
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.ConversationConstants
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.chat.MessageService
import com.nebula.service.conversation.ConversationService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext

/**
 * 已读报告 Handler — method = "message/read"（D-23 ~ D-28, D-71）。
 *
 * 职责：
 * - 委托 MessageService 处理已读报告业务逻辑（成员验证、更新已读进度、清零 unreadCount）
 * - 更新投递跟踪状态为 read（D-71 sent/delivered → read）
 * - 私聊场景推送已读回执给原发送者（gateway 层推送）
 *
 * 注：未读计数权威源是 DB [com.nebula.repository.entity.ConversationMemberEntity.unreadCount]
 * （由 MessageService 在已读上报与 fan-out 未读自增两路径统一维护），不再维护冗余的 Redis 未读键。
 *
 * @param messageService 消息业务服务
 * @param conversationService 会话业务服务（会话查询 + 成员查询）
 * @param pushService 推送服务
 * @param deliveryTrackingService 投递三态跟踪服务（D-71）
 */
class ReadReportHandler(
    private val messageService: MessageService,
    private val conversationService: ConversationService,
    private val pushService: PushService,
    private val deliveryTrackingService: DeliveryTrackingService,
) : Handler<ReadReportReq, Response> {

    override val method: String = MethodNames.Message.READ

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun handle(req: ReadReportReq): Response {
        val session = currentCoroutineContext().requireSession()

        // 委托 MessageService 处理业务逻辑（含未读清零：updateReadReceipt 将 unreadCount 置 0）
        messageService.readReport(req, session.userId)

        // D-71: 更新投递跟踪状态为 read（sent/delivered → read）
        deliveryTrackingService.markRead(req.lastReadMsgId, session.userId)

        // D-27: 获取会话并判断类型
        val conversation = conversationService.getConversation(req.conversationId) ?: throw ConversationException(BizCode.CONV_NOT_FOUND, "会话不存在")
        val isPrivate = conversation.type == ConversationConstants.CONV_TYPE_PRIVATE

        // D-23: 私聊场景推送已读回执给原发送者
        if (isPrivate) {
            pushReadReceiptToSender(req, session.userId)
        }

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMethod("message/read")
            .build()
    }

    /**
     * 在私聊场景下将已读回执推送给消息原发送者。
     *
     * 通过会话成员列表找出与 readerUid 不同的成员作为发送者，
     * 构建 ReadReceiptPayload 后委托 PushService 推送。
     *
     * @param req 已读报告请求
     * @param readerUid 阅读者用户 ID
     */
    private suspend fun pushReadReceiptToSender(req: ReadReportReq, readerUid: Long) {
        val members = conversationService.getConversationMembers(req.conversationId)
        val senderMember = members.firstOrNull { it.userId != readerUid }

        if (senderMember != null) {
            val payload = ReadReceiptPayload.newBuilder()
                .setConversationId(req.conversationId)
                .setReaderUid(readerUid)
                .setMsgId(req.lastReadMsgId)
                .build()
            pushService.pushReadReceipt(senderMember.userId, payload)
        } else {
            logger.debug { "私聊已读回执跳过：会话=${req.conversationId}，读者=$readerUid，未找到发送者" }
        }
    }
}
