package com.nebula.common.exception

import com.nebula.common.BizCode

class FriendException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)
