package com.nebula.repository.config

import com.nebula.common.datasource.DataSourceProvider
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.hibernate.cfg.AvailableSettings
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.support.TransactionTemplate
import javax.sql.DataSource

/**
 * JPA + Flyway 引导配置。
 *
 * Flyway 迁移在 EntityManagerFactory 创建前执行（D-02）。
 * Hibernate 以 validate 模式启动，校验实体与表结构一致（D-01）。
 * 事务由 Service 层管理（D-09）。
 */
class JpaConfig(
    private val dataSourceProvider: DataSourceProvider
) {
    /** EntityManagerFactory（延迟初始化，首次访问时执行 Flyway 迁移） */
    val entityManagerFactory: EntityManagerFactory by lazy {
        val dataSource = dataSourceProvider.getDataSource()
        runFlywayMigrations(dataSource)

        val emfBean = LocalContainerEntityManagerFactoryBean().apply {
            setDataSource(dataSource)
            setPackagesToScan("com.nebula.repository.entity")
            jpaVendorAdapter = HibernateJpaVendorAdapter().apply {
                setShowSql(false)
                setGenerateDdl(false) // Flyway 负责 DDL（D-02）
                setDatabasePlatform("org.hibernate.dialect.MySQLDialect")
            }
            jpaPropertyMap = mapOf(
                AvailableSettings.HBM2DDL_AUTO to "validate",       // 校验实体与表结构一致
                AvailableSettings.STATEMENT_BATCH_SIZE to "30",     // JDBC 批量大小
                AvailableSettings.ORDER_INSERTS to "true",           // 批量插入排序优化
                AvailableSettings.ORDER_UPDATES to "true",            // 批量更新排序优化
                AvailableSettings.PHYSICAL_NAMING_STRATEGY to
                        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
            )
        }
        emfBean.afterPropertiesSet()
        emfBean.getObject()!!
    }

    /** 获取 Spring Data JPA Repository 代理 */
    fun <T> getRepository(repositoryInterface: Class<T>): T {
        val em = entityManagerFactory.createEntityManager()
        val factory = JpaRepositoryFactory(em)
        return factory.getRepository(repositoryInterface)
    }

    /**
     * 创建编程式事务模板（D-19 事务策略）。
     *
     * 基于 entityManagerFactory 创建 JpaTransactionManager，
     * 用于编写多表事务（如创建群聊：插入 ConversationEntity + 批量插入 ConversationMemberEntity）。
     * TransactionTemplate 通过 Koin DI 注入，在 Handler 中配合 ConversationLockManager 用于保证原子性。
     *
     * @return TransactionTemplate 实例
     */
    fun transactionTemplate(): TransactionTemplate {
        val transactionManager = JpaTransactionManager(entityManagerFactory)
        transactionManager.afterPropertiesSet()
        return TransactionTemplate(transactionManager)
    }
}

/** 执行 Flyway 数据库迁移 */
private fun runFlywayMigrations(dataSource: DataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .load()
        .migrate()
}
