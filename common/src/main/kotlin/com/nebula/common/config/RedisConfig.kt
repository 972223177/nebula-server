package com.nebula.common.config

/**
 * Redis 服务器连接配置。
 *
 * Phase 3 引入，供 Lettuce 客户端初始化使用。
 * Phase 11 扩展：添加 password 和 ssl 支持（D-77）。
 */
data class RedisConfig(
    /** Redis 服务器主机地址 */
    val host: String = "127.0.0.1",
    /** Redis 服务器端口 */
    val port: Int = 6379,
    /** Redis 认证密码（D-77），空字符串表示无密码 */
    val password: String = "",
    /** 是否启用 Redis TLS 连接（D-77） */
    val ssl: Boolean = false
)
