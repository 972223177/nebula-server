package com.nebula.common.exception

import com.nebula.common.BizCode

/**
 * 业务异常基类，所有领域异常均继承此类。
 *
 * 通过 [BizCode] 枚举统一管理错误码和错误消息，上层拦截器可据此进行统一格式化响应。
 * 继承链：BizException <- 领域异常 <- RuntimeException
 */
open class BizException(
    val bizCode: BizCode,
    override val message: String = bizCode.msg
) : RuntimeException(message)
