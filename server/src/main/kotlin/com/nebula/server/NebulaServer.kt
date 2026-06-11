package com.nebula.server

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.datasource.HikariDataSourceProvider
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.server.config.ConfigLoader
import com.nebula.server.server.ChatServer

/**
 * Nebula 服务端应用入口。
 *
 * 启动顺序遵循 D-18 设计决策：日志配置必须早于所有其他初始化步骤，
 * 以确保 ConfigLoader 加载过程中的任何日志输出都能被正确捕获。
 *
 * 启动顺序:
 * 1. 设置 logback 配置文件（必须在其他初始化之前，D-18）
 * 2. 通过 ConfigLoader 加载 HOCON 配置
 * 3. 初始化 SnowflakeIdGenerator（Phase 5 消息 ID 生成正式使用）
 * 4. 初始化 HikariCP 数据库连接池（Phase 3 数据持久化正式使用）
 * 5. 启动 gRPC ChatServer
 * 6. 阻塞在 awaitTermination() 上，等待进程关闭信号
 */
fun main() {
    val env = System.getenv("ENV") ?: "dev"

    // D-18: logback 配置必须在 ConfigLoader 之前设置，否则加载配置期间的日志将丢失
    System.setProperty("logback.configurationFile", "logback-$env.xml")

    // Step 2: 加载配置 — 使用 Typesafe Config 解析 HOCON 文件
    val config: ApplicationConfig = ConfigLoader.load()

    // Step 3: 初始化 Snowflake ID 生成器 — Phase 5 正式使用
    // 雪花算法生成 64 位唯一 ID，位分配: 41 位时间戳 | 10 位 Worker ID | 12 位序列号
    val idGenerator = SnowflakeIdGenerator(
        workerId = config.snowflake.workerId,
        epoch = config.snowflake.epoch
    )

    // Step 4: 初始化数据库连接池 — Phase 3 正式使用
    // HikariCP 连接池通过 HikariDataSourceProvider 封装，屏蔽直接依赖
    val dataSourceProvider = HikariDataSourceProvider(config.database)

    // Step 5: 启动 gRPC 服务 — 包含 SSL/TLS、keepalive、流控配置
    val chatServer = ChatServer(config)
    chatServer.start()

    // Step 6: 阻塞等待关闭 — JVM 进程在此等待，直到收到 SIGTERM/Shutdown 信号
    chatServer.blockUntilShutdown()
}
