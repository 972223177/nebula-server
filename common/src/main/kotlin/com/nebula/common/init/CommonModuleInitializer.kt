package com.nebula.common.init

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.datasource.DataSourceProvider
import com.nebula.common.datasource.HikariDataSourceProvider
import com.nebula.common.idgen.SnowflakeIdGenerator
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.context.GlobalContext

/**
 * Common 模块初始化器。
 *
 * 负责创建基础设施组件，无模块依赖。
 * 初始化产物：
 * - [SnowflakeIdGenerator] — 分布式 ID 生成器
 * - [DataSourceProvider] — HikariCP 数据库连接池
 */
class CommonModuleInitializer : ModuleInitializer, KoinComponent {

    override val name = "common"
    override val dependencies = emptyList<String>()

    override fun init() {
        val config = get<ApplicationConfig>()
        val koin = GlobalContext.get()

        // 创建 Snowflake ID 生成器
        val idGenerator = SnowflakeIdGenerator(
            workerId = config.snowflake.workerId,
            epoch = config.snowflake.epoch
        )

        // 创建 HikariCP 数据库连接池
        val dataSourceProvider: DataSourceProvider = HikariDataSourceProvider(config.database)

        // 注册产物到 Koin 容器
        koin.declare(idGenerator)
        koin.declare(dataSourceProvider)
    }
}
