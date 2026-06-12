package com.nebula.gateway.handler.chat.send

import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * 消息域业务异常，标识消息发送、拉取等操作触发的错误。
 *
 * 继承 [BizException]，可由 ExceptionInterceptor 统一捕获并转换为 gRPC 错误响应。
 * 与 ConversationException 采用相同的继承模式。
 */
class SendMessageException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg) {

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
