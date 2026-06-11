package com.nebula.common.exception

import com.nebula.common.BizCode

/**
 * 用户域业务异常，标识用户模块（注册、登录、资料等）触发的错误。
 */
class UserException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)
