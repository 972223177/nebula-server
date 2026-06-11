package com.nebula.common.exception

/**
 * 系统级异常：时钟回拨。
 *
 * 由雪花 ID 生成器在检测到系统时钟回拨时抛出，用于防止 ID 冲突。
 * 不继承 [BizException] 体系，属于基础设施层的非业务异常。
 */
class ClockBackwardsException(
    msg: String
) : RuntimeException(msg)
