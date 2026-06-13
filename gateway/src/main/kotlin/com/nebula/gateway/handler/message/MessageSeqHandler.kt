package com.nebula.gateway.handler.message

import com.nebula.chat.message.MessageSeqReq
import com.nebula.chat.message.MessageSeqResp
import com.nebula.common.BizCode
import com.nebula.common.exception.MessageException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.sequence.SeqService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 会话序列号查询 Handler — method = "message/seq"（Phase 10）。
 *
 * 客户端在检测到序列号间隙时调用此 Handler 查询当前最新序列号，
 * 用于判断是否需要触发全量消息拉取。
 *
 * @param seqService 序列号服务
 */
class MessageSeqHandler(
    private val seqService: SeqService
) : Handler<MessageSeqReq, MessageSeqResp> {

    override val method: String = "message/seq"

    override suspend fun handle(req: MessageSeqReq): MessageSeqResp {
        val session = currentCoroutineContext().requireSession()

        // 参数校验：conversationId 不能为空
        if (req.conversationId.isBlank()) {
            throw MessageException(BizCode.INVALID_PARAM, "conversation_id 不能为空")
        }

        // 查询当前最新序列号
        val seq = seqService.currentSeq(req.conversationId, session.userId)

        return MessageSeqResp.newBuilder()
            .setSeq(seq)
            .build()
    }
}
