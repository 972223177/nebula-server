package com.nebula.server.config

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.config.DatabaseConfig
import com.nebula.common.config.RedisConfig
import com.nebula.common.config.ServerConfig
import com.nebula.common.config.SnowflakeConfig
import com.nebula.common.config.SslConfig
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

/**
 * 配置加载器 — 使用 Typesafe Config (HOCON) 加载并解析应用配置。
 *
 * 职责：
 * - 从文件系统读取 application.conf
 * - 按优先级合并配置源：系统属性 > 文件配置 > 默认引用
 * - 将扁平化 HOCON 配置映射为类型安全的 ApplicationConfig 数据类
 *
 * 设计决策引用：
 * - D-02: 单文件 + 环境变量覆盖模式
 * - D-03: Typesafe Config 作为配置框架
 * - D-04: 嵌套 data class 表达配置结构
 * - Pitfall 5: 使用显式文件路径，避免 classpath 歧义
 */
object ConfigLoader {

    private val log = KotlinLogging.logger {}

    /**
     * 加载并解析 HOCON 配置。
     *
     * @param configPath 配置文件的文件系统路径，默认 "config/application.conf"
     * @return 解析后的 ApplicationConfig 实例
     *
     * 配置解析链（优先级从高到低）：
     * 1. 系统属性 (System.getProperties) — 用于运维/K8s 环境覆盖，如 `-Dserver.port=8080`
     * 2. 文件配置 (config/application.conf) — 项目级默认配置
     * 3. 默认引用 (reference.conf) — Typesafe Config 库和依赖的默认值
     */
    fun load(configPath: String = "config/application.conf"): ApplicationConfig {
        val env = System.getenv("ENV") ?: "dev"
        log.info { "Loading configuration from $configPath (env: $env)" }

        // 系统属性优先级最高，可覆盖文件配置（用于 K8s ConfigMap 注入 -D 参数）
        val fileConfig = ConfigFactory.parseFile(File(configPath))
        val resolvedConfig = ConfigFactory.systemProperties()
            .withFallback(fileConfig)                          // 第二优先级: 文件配置
            .withFallback(ConfigFactory.defaultReference())    // 第三优先级: 库默认值
            .resolve()

        return parseConfig(resolvedConfig, env)
    }

    /**
     * 将 Typesafe Config 对象转换为类型安全的 ApplicationConfig。
     *
     * @param config 已 resolved 的 Typesafe Config 对象
     * @param env 当前环境名称（dev/prod），透传到 ApplicationConfig
     * @return 类型安全的 ApplicationConfig 实例
     *
     * 注意：HOCON 配置键使用连字符（如 `worker-id`），
     * Kotlin 字段使用驼峰命名（如 `workerId`），由 Typesafe Config 自动转换。
     */
    private fun parseConfig(config: Config, env: String): ApplicationConfig {
        return ApplicationConfig(
            env = env,
            server = ServerConfig(
                port = config.getInt("server.port")
            ),
            snowflake = SnowflakeConfig(
                workerId = config.getLong("snowflake.worker-id"),
                epoch = config.getLong("snowflake.epoch")
            ),
            database = DatabaseConfig(
                host = config.getString("database.host"),
                port = config.getInt("database.port"),
                database = config.getString("database.database"),
                username = config.getString("database.username"),
                password = config.getString("database.password"),
                poolSize = config.getInt("database.pool-size"),
                minIdle = config.getInt("database.min-idle"),
                connectionTimeout = config.getLong("database.connection-timeout"),
                idleTimeout = config.getLong("database.idle-timeout"),
                maxLifetime = config.getLong("database.max-lifetime"),
                leakDetectionThreshold = config.getLong("database.leak-detection-threshold")
            ),
            redis = RedisConfig(
                host = config.getString("redis.host"),
                port = config.getInt("redis.port")
            ),
            ssl = SslConfig(
                enabled = config.getBoolean("ssl.enabled"),
                certChainPath = config.getString("ssl.cert-chain-path"),
                privateKeyPath = config.getString("ssl.private-key-path")
            )
        )
    }
}
