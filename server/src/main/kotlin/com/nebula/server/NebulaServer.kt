package com.nebula.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.datasource.HikariDataSourceProvider
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.server.config.ConfigLoader
import com.nebula.server.server.ChatServer

fun main() {
    val env = System.getenv("ENV") ?: "dev"

    // Step 1: 设置 logback 配置文件（必须在其他初始化之前）
    System.setProperty("logback.configurationFile", "logback-$env.xml")

    // Step 2: 加载配置
    val config: ApplicationConfig = ConfigLoader.load()

    // Step 3: 初始化 Snowflake ID 生成器（Phase 5 正式使用）
    val idGenerator = SnowflakeIdGenerator(
        workerId = config.snowflake.workerId,
        epoch = config.snowflake.epoch
    )

    // Step 4: 初始化数据库连接池（Phase 3 正式使用）
    val dataSourceProvider = HikariDataSourceProvider(config.database)

    // Step 5: 启动 gRPC 服务
    val chatServer = ChatServer(config)
    chatServer.start()

    // Step 6: 阻塞等待关闭
    chatServer.blockUntilShutdown()
}
