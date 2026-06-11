package com.nebula.common.exception

import com.nebula.common.BizCode

class ChatException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)
