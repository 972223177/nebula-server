package com.nebula.repository.config

import com.nebula.common.datasource.DataSourceProvider
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.hibernate.cfg.AvailableSettings
import org.hibernate.cfg.Configuration
import javax.sql.DataSource

/**
 * JPA + Flyway 引导配置（纯 Hibernate，移除 Spring 依赖）。
 *
 * Flyway 迁移在 EntityManagerFactory 创建前执行（D-02）。
 * Hibernate 以 validate 模式启动，校验实体与表结构一致（D-01）。
 *
 * 事务由 Service 层管理（D-09）：通过 [com.nebula.repository.dao.JpaTxRunner] 提供。
 *
 * 构造说明：使用 Hibernate 原生 [Configuration] API 代替 Spring 的
 * `LocalContainerEntityManagerFactoryBean`（方案 A：去除 Spring Data JPA / Spring ORM）。
 */
class JpaConfig(
    private val dataSourceProvider: DataSourceProvider
) {
    /** EntityManagerFactory（延迟初始化，首次访问时执行 Flyway 迁移） */
    val entityManagerFactory: EntityManagerFactory by lazy {
        val dataSource = dataSourceProvider.getDataSource()
        runFlywayMigrations(dataSource)
        buildEntityManagerFactory(dataSource)
    }

    /**
     * 构建 EntityManagerFactory — 通过 Hibernate 原生 Configuration API。
     *
     * @param dataSource 已执行 Flyway 迁移的数据源
     * @return 已配置的 [EntityManagerFactory]
     */
    private fun buildEntityManagerFactory(dataSource: DataSource): EntityManagerFactory {
        val configuration = Configuration()
        // 连接池由 DataSourceProvider 提供（HikariCP）
        // 直接使用 "hibernate.connection.datasource" 而非 AvailableSettings.DATASOURCE，
        // 后者在 Hibernate 6.6 已被弃用并计划移除（参见 HHH-17800）
        configuration.properties["hibernate.connection.datasource"] = dataSource
        // 校验实体与表结构一致（D-01）
        configuration.properties[AvailableSettings.HBM2DDL_AUTO] = "validate"
        // JDBC 批量大小
        configuration.properties[AvailableSettings.STATEMENT_BATCH_SIZE] = "30"
        // 批量插入/更新排序优化
        configuration.properties[AvailableSettings.ORDER_INSERTS] = "true"
        configuration.properties[AvailableSettings.ORDER_UPDATES] = "true"
        // 命名策略：Hibernate 6 推荐的 CamelCase → snake_case 转换
        configuration.properties[AvailableSettings.PHYSICAL_NAMING_STRATEGY] =
            "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
        // 关闭 SQL 打印
        configuration.properties[AvailableSettings.SHOW_SQL] = "false"

        // 扫描 com.nebula.repository.entity 包下所有 @Entity 类
        configuration.addPackage("com.nebula.repository.entity")
        val entityClassNames = listOf(
            "com.nebula.repository.entity.UserEntity",
            "com.nebula.repository.entity.ConversationEntity",
            "com.nebula.repository.entity.ConversationMemberEntity",
            "com.nebula.repository.entity.MessageEntity",
            "com.nebula.repository.entity.FriendRequestEntity",
            "com.nebula.repository.entity.FriendshipEntity",
            "com.nebula.repository.entity.DeadLetterEntity"
        )
        entityClassNames.forEach { className ->
            try {
                configuration.addAnnotatedClass(Class.forName(className))
            } catch (e: ClassNotFoundException) {
                throw IllegalStateException("Failed to load entity class: $className", e)
            }
        }

        return configuration.buildSessionFactory()
    }
}

/**
 * 执行 Flyway 数据库迁移。
 *
 * @param dataSource 数据源，由 DataSourceProvider 提供
 */
private fun runFlywayMigrations(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate()
}
