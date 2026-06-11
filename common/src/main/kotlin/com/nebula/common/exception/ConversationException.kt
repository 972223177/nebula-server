package com.nebula.common.exception

import com.nebula.common.BizCode

/**
 * 会话域业务异常，标识会话生命周期管理（创建、加入、关闭等）触发的错误。
 */
class ConversationException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)
