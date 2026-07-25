package com.nebula.gateway.handler.delivery
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.Response
import com.nebula.chat.message.DeliveryAckPayload
import com.nebula.common.BizCode
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.conversation.ConversationConstants
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext

/**
 * 交付回执上报 Handler — method = "message/delivery_ack"（D-71）。
 *
 * 接收者客户端收到消息后，发送此请求告知服务端"消息已到达接收方设备"。
 * 服务端负责：
 * 1. 更新投递跟踪状态为 delivered
 * 2. 推送 DELIVERY_ACK 给原发送者，告知消息已送达
 *
 * @param deliveryTrackingService 投递三态跟踪服务
 * @param pushService 推送服务
 * @param conversationService 会话成员查询服务
 */
class DeliveryAckHandler(
    private val deliveryTrackingService: DeliveryTrackingService,
    private val pushService: PushService,
    private val conversationService: ConversationService
) : Handler<DeliveryAckPayload, Response> {

    override val method: String = MethodNames.Message.DELIVERY_ACK

    companion object {
        private val logger = KotlinLogging.logger {}
    }

    override suspend fun handle(req: DeliveryAckPayload): Response {
        val session = currentCoroutineContext().requireSession()
        val msgId = req.msgId
        val convId = req.conversationId
        val receiverUid = session.userId

        // Step 1: 标记投递状态为 delivered（sent → delivered）
        deliveryTrackingService.markDelivered(msgId, receiverUid)

        // Step 2: 推送 DELIVERY_ACK 给原发送者
        pushDeliveryAckToSender(convId, msgId, receiverUid)

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMethod(method)
            .build()
    }

    /**
     * 通过会话成员列表找出原发送者，推送交付回执。
     *
     * 私聊场景下，发送者是与接收者不同的另一成员。
     * 群聊不推送交付回执（消息维度太细，群消息以已读回执为准）。
     */
    private suspend fun pushDeliveryAckToSender(convId: String, msgId: Long, receiverUid: Long) {
        try {
            val conversation = conversationService.getConversation(convId)
            if (conversation == null) {
                logger.warn { "交付回执推送跳过：会话不存在 convId=$convId" }
                return
            }

            // 仅私聊推送交付回执
            if (conversation.type != ConversationConstants.CONV_TYPE_PRIVATE) {
                logger.debug { "群聊跳过交付回执推送 convId=$convId, msgId=$msgId" }
                return
            }

            // 通过成员列表找出发送者（与接收者不同的成员）
            val members = conversationService.getConversationMembers(convId)
            val senderUid = members.firstOrNull { it.userId != receiverUid }?.userId
            if (senderUid != null) {
                pushService.pushDeliveryAck(senderUid, msgId, convId)
            } else {
                logger.debug { "交付回执推送跳过：会话不完整 convId=$convId, receiverUid=$receiverUid" }
            }
        } catch (e: Exception) {
            logger.error(e) { "交付回执推送失败 convId=$convId, msgId=$msgId, receiverUid=$receiverUid" }
        }
    }
}
