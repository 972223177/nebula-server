package com.nebula.common.exception

import com.nebula.common.BizCode

/**
 * 聊天域业务异常，标识 Chat 模块（聊天会话、消息发送等）触发的错误。
 */
class ChatException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)
