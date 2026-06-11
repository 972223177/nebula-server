package com.nebula.common.exception

import com.nebula.common.BizCode

/**
 * 消息域业务异常，标识消息处理（发送、撤回、已读等）触发的错误。
 */
class MessageException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)
