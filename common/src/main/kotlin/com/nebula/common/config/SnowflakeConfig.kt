package com.nebula.common.config

/**
 * 雪花算法（Snowflake）ID 生成器配置。
 *
 * 通过 workerId 保证多节点 ID 不冲突，epoch 用于缩短时间戳位数以延长 ID 可用年限。
 * 详见 [SnowflakeIdGenerator] 的位分配说明。
 */
data class SnowflakeConfig(
    /** 机器标识（0-1023），分布式环境下每台实例必须唯一，否则产生 ID 冲突 */
    val workerId: Long,
    /** 自定义纪元起点（毫秒级时间戳），通过前移基准时间延长 41 bit 时间戳的使用年限 */
    val epoch: Long
)
