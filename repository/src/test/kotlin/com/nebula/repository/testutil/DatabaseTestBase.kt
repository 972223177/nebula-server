package com.nebula.repository.testutil

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import javax.sql.DataSource

/**
 * MySQL 集成测试基类。
 *
 * 使用 TestContainers 自动管理 MySQL 容器生命周期（D-02, D-03, D-06）。
 * - 启动 MySQL 8.0 容器，自动创建数据库
 * - 通过 Flyway 自动执行所有迁移脚本
 * - 提供 HikariCP DataSource 供子类使用
 *
 * 子类继承后只需调用 [getDataSource] 获取连接或自行构建 EntityManagerFactory。
 *
 * 注意：TestContainers 依赖 Docker 环境，若 Docker 不可用测试将自动跳过。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class DatabaseTestBase {

    companion object {
        @Container
        private val mysql: MySQLContainer<*> = MySQLContainer<Nothing>("mysql:8.0")
            .withDatabaseName("nebula_test")

        private lateinit var dataSource: DataSource

        /** Flyway 迁移路径（对应 src/main/resources/db/migration） */
        private const val MIGRATION_LOCATION = "classpath:db/migration"

        @JvmStatic
        @BeforeAll
        fun setupDatabase() {
            mysql.start()

            val config = HikariConfig().apply {
                jdbcUrl = mysql.jdbcUrl
                username = mysql.username
                password = mysql.password
                driverClassName = mysql.driverClassName
                maximumPoolSize = 5
                minimumIdle = 1
                connectionTimeout = 5000
            }
            dataSource = HikariDataSource(config)

            // 执行 Flyway 迁移
            Flyway.configure()
                .dataSource(dataSource)
                .locations(MIGRATION_LOCATION)
                .load()
                .migrate()
        }

        /**
         * 获取 HikariCP DataSource，连接至 TestContainers 管理的 MySQL 实例。
         * Flyway 迁移已在 setupDatabase 中自动执行。
         */
        fun getDataSource(): DataSource = dataSource

        /**
         * 获取 JDBC 连接 URL。
         */
        fun getJdbcUrl(): String = mysql.jdbcUrl
    }
}
