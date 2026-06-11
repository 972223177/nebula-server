package com.nebula.common.datasource

import com.nebula.common.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource

class HikariDataSourceProvider(
    private val config: DatabaseConfig
) : DataSourceProvider {

    private val hikariDataSource: HikariDataSource by lazy {
        HikariConfig().apply {
            jdbcUrl = buildJdbcUrl()
            username = config.username
            password = config.password
            maximumPoolSize = config.poolSize
            minimumIdle = config.minIdle
            connectionTimeout = config.connectionTimeout
            idleTimeout = config.idleTimeout
            maxLifetime = config.maxLifetime
            leakDetectionThreshold = config.leakDetectionThreshold

            addDataSourceProperty("cachePrepStmts", "true")
            addDataSourceProperty("prepStmtCacheSize", "250")
            addDataSourceProperty("prepStmtCacheSqlLimit", "2048")
            addDataSourceProperty("useServerPrepStmts", "true")
            addDataSourceProperty("rewriteBatchedStatements", "true")
        }.let(::HikariDataSource)
    }

    override fun getDataSource(): DataSource = hikariDataSource

    private fun buildJdbcUrl(): String {
        return "jdbc:mysql://${config.host}:${config.port}/${config.database}" +
                "?sslMode=PREFERRED" +
                "&useUnicode=true" +
                "&characterEncoding=utf8mb4" +
                "&serverTimezone=UTC"
    }
}
