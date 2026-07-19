package com.nebula.common.redis

/**
 * Redis TTL / 超时常量集中管理 — 消除各 Repository 中重复的魔法数字。
 *
 * 所有模块统一引用此处的常量值，修改 TTL 只需改此一处（D-05）。
 */
object RedisTtl {

    /** Session / 设备类型映射 / 投递状态 / 消息去重等通用 7 天 TTL */
    const val SEVEN_DAYS = 7 * 24 * 3600L

    /** 在线状态 60s 短 TTL（D-14） */
    const val ONLINE_STATUS = 60L

    /** L2 Redis 操作超时时间（毫秒） */
    const val TIMEOUT_MS = 500L
}
