package com.nebula.common.exception

/**
 * 系统级异常：同一毫秒内序列号溢出。
 *
 * 由雪花 ID 生成器在单毫秒内分配的 ID 超过序列号上限时抛出。
 * 仅在内部使用，上层 waitNextMillis 逻辑会捕获此异常并通过等待下一毫秒自动恢复。
 */
class SequenceOverflowException(
    msg: String
) : RuntimeException(msg)
