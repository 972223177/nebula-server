package com.nebula.gateway.handler.chat.send

import com.nebula.chat.chat.SendMessageReq
import com.nebula.chat.message.ChatMessage
import com.nebula.common.BizCode

/**
 * Step 链共享上下文（D-13）。
 *
 * 在 Step 链执行过程中传递请求、中间计算结果和最终处理结果。
 * msgId 和 chatMessage 在 WriteStep 执行后才非空。
 *
 * @param req 原始发送消息请求（只读）
 * @param senderUid 发送者用户 ID（来自 Session）
 * @param conversationId 会话 ID，默认使用 req.conversationId
 * @param msgId Snowflake 生成的消息 ID（WriteStep 设置）
 * @param chatMessage 构建的 ChatMessage proto 对象（WriteStep 设置）
 * @param bizCode 业务处理结果码，默认 OK
 */
data class SendContext(
    val req: SendMessageReq,
    val senderUid: Long,
    var conversationId: String = req.conversationId,
    var msgId: Long? = null,
    var chatMessage: ChatMessage? = null,
    var bizCode: BizCode = BizCode.OK
)
