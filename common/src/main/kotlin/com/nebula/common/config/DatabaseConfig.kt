package com.nebula.common.config

/**
 * 关系型数据库连接池（HikariCP）配置。
 *
 * 各超时参数按毫秒为单位，在连接争用较高或网络不稳定时应适当调整。
 */
data class DatabaseConfig(
    /** 数据库主机地址，仅支持 IP 或可解析域名，不支持 JDBC URL 片段拼接 */
    val host: String,
    /** 数据库端口，MySQL 默认为 3306，PG 为 5432 */
    val port: Int,
    /** 要连接的数据库名称 / Schema */
    val database: String,
    /** 数据库认证用户名 */
    val username: String,
    /** 数据库认证密码，不得明文存储在版本控制中，建议通过环境变量注入 */
    val password: String,
    /** 连接池最大活跃连接数，避免耗尽数据库端连接槽位 */
    val poolSize: Int,
    /** 连接池保持空闲的最小连接数，应对突发流量预热 */
    val minIdle: Int,
    /** 获取连接的超时毫秒数（等不到即抛异常），防止调用方无限阻塞 */
    val connectionTimeout: Long,
    /** 空闲连接最大存活毫秒数，超过则逐出，减少陈旧连接占用 */
    val idleTimeout: Long,
    /** 连接最大存活毫秒数（不论是否活跃），强制周期性重建，避免长时间连接的资源泄漏 */
    val maxLifetime: Long,
    /** 连接泄漏检测阈值，连接借出超过此时长未归还则告警，用于排查未关闭的 Statement / Connection */
    val leakDetectionThreshold: Long
)
