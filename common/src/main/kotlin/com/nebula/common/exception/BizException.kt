package com.nebula.common.exception

import com.nebula.common.BizCode

open class BizException(
    val bizCode: BizCode,
    override val message: String = bizCode.msg
) : RuntimeException(message)
