package com.nebula.common.exception

import com.nebula.common.BizCode

class ConversationException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)
