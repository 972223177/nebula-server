package com.nebula.common.exception

import com.nebula.common.BizCode

/**
 * 好友域业务异常，标识好友关系操作（添加、删除、查询等）触发的错误。
 */
class FriendException(
    bizCode: BizCode,
    msg: String = bizCode.msg
) : BizException(bizCode, msg)
