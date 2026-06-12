package com.nebula.common.config

/**
 * 应用顶层配置聚合类，承载所有子模块配置，外部由 [ConfigFactory] 从 YAML/HOCON 文件反序列化注入。
 *
 * 将不同关注点的配置拆分为独立 data class，便于按模块演进与单元测试隔离。
 */
data class ApplicationConfig(
    /** 运行环境标识，如 dev / test / staging / prod，用于差异化行为和告警阈值 */
    val env: String,
    /** gRPC 服务器监听配置 */
    val server: ServerConfig,
    /** 分布式 ID 生成器雪花算法参数 */
    val snowflake: SnowflakeConfig,
    /** 关系型数据库（HikariCP）连接池配置 */
    val database: DatabaseConfig,
    /** Redis 缓存服务器配置 — Phase 3 引入 */
    val redis: RedisConfig,
    /** gRPC TLS/SSL 传输层安全配置 */
    val ssl: SslConfig
)
