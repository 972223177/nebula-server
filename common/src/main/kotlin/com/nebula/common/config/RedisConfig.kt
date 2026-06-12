package com.nebula.common.config

/**
 * Redis 服务器连接配置。
 *
 * Phase 3 引入，供 Lettuce 客户端初始化使用。
 * 开发环境默认值指向 docker-compose 中的 nebula-redis 服务。
 */
data class RedisConfig(
    /** Redis 服务器主机地址 */
    val host: String = "127.0.0.1",
    /** Redis 服务器端口 */
    val port: Int = 6379
)
