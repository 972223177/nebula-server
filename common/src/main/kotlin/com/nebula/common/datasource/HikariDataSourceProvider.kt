package com.nebula.common.datasource

import com.nebula.common.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

/**
 * HikariCP 连接池数据源提供者。
 *
 * 读取 [DatabaseConfig] 并使用 HikariCP 配置连接池参数。
 * 利用 Kotlin [by lazy] 实现延迟单例初始化，仅在首次调用 [getDataSource] 时构建连接池。
 */
class HikariDataSourceProvider(
    private val config: DatabaseConfig
) : DataSourceProvider {

    /**
     * Hikari 数据源实例，延迟初始化。
     *
     * 使用 [by lazy] 保证连接池仅被构建一次，且线程安全。
     * 配置项包括连接串、用户名/密码、池大小、超时、泄漏检测等，并对 MySQL 做预处理语句缓存优化。
     */
    private val hikariDataSource: HikariDataSource by lazy {
        HikariConfig().apply {
            // 构建 JDBC 连接 URL
            jdbcUrl = buildJdbcUrl()
            username = config.username
            password = config.password

            // 连接池大小配置
            maximumPoolSize = config.poolSize
            minimumIdle = config.minIdle

            // 超时与存活配置
            connectionTimeout = config.connectionTimeout
            idleTimeout = config.idleTimeout
            maxLifetime = config.maxLifetime

            // 连接泄漏检测阈值（超出则日志警告）
            leakDetectionThreshold = config.leakDetectionThreshold

            // ---- MySQL 预处理语句性能优化 ----
            // 开启客户端预处理语句缓存，减少 SQL 解析开销
            addDataSourceProperty("cachePrepStmts", "true")
            // 缓存 250 条预处理语句
            addDataSourceProperty("prepStmtCacheSize", "250")
            // 单条 SQL 缓存上限 2048 字符
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            // 使用服务端预处理语句，减少 SQL 重复编译
            addDataSourceProperty("useServerPrepStmts", "true")
            // 批量写入时自动合并为多值 INSERT，提升写入吞吐
            addDataSourceProperty("rewriteBatchedStatements", "true")
        }.let(::HikariDataSource)
    }

    override fun getDataSource(): DataSource = hikariDataSource

    /**
     * 构建 MySQL JDBC 连接 URL。
     *
     * 包含 SSL 优先、UTF-8 编码和 UTC 时区等必要参数。
     */
    private fun buildJdbcUrl(): String {
        return "jdbc:mysql://${config.host}:${config.port}/${config.database}" +
                "?sslMode=PREFERRED" +
                "&useUnicode=true" +
                "&characterEncoding=utf8mb4" +
                "&serverTimezone=UTC"
    }
}
