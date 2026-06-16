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
 *
 * @param config 数据库连接池配置，包含连接串、池大小、超时等参数
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
            // 统一拼接 JDBC URL 以保证所有连接使用相同的 SSL、编码和时区设置
            jdbcUrl = buildJdbcUrl()
            username = config.username
            password = config.password

            // 控制并发连接上限与预热连接数，防止数据库连接槽位耗尽导致服务不可用
            maximumPoolSize = config.poolSize
            minimumIdle = config.minIdle

            // 设置获取超时和连接存活周期，快速失败而非无限阻塞，定期回收陈旧连接
            connectionTimeout = config.connectionTimeout
            idleTimeout = config.idleTimeout
            maxLifetime = config.maxLifetime

            // 设置泄漏检测阈值以快速定位未归还的 Connection，避免连接池耗尽
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

    /**
     * 获取 HikariCP 数据源实例，首次调用触发连接池延迟初始化。
     *
     * [HikariDataSource] 由 [by lazy] 延迟创建，仅在首次调用时实例化并配置连接池参数。
     *
     * @return 已初始化的 [HikariDataSource] 实例
     */
    override fun getDataSource(): DataSource = hikariDataSource

    /**
     * 构建 MySQL JDBC 连接 URL。
     *
     * 统一拼接 JDBC URL 以保证所有连接使用相同的 SSL、编码和时区设置，避免多环境连接行为不一致。
     * SSL 模式根据配置动态选择（D-77）：生产环境 VERIFY_CA，开发环境 DISABLED。
     *
     * @return MySQL JDBC 连接 URL 字符串，包含 SSL/编码/时区参数
     */
    private fun buildJdbcUrl(): String {
        // D-77: sslEnabled 为 true 时使用 VERIFY_CA（严格要求证书校验），否则禁用 SSL
        val sslMode = if (config.sslEnabled) "VERIFY_CA" else "DISABLED"
        return "jdbc:mysql://${config.host}:${config.port}/${config.database}" +
                "?sslMode=$sslMode" +
                "&useUnicode=true" +
                "&characterEncoding=UTF-8" +
                "&serverTimezone=UTC" +
                // MySQL 8+ 默认 caching_sha2_password，需允许 JDBC 驱动从服务器获取公钥进行认证
                "&allowPublicKeyRetrieval=true"
    }
}
