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

    /** 日志记录器 */
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

        val appConfig = parseConfig(resolvedConfig, env)
        validateConfig(appConfig)
        return appConfig
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
                leakDetectionThreshold = config.getLong("database.leak-detection-threshold"),
                sslEnabled = if (config.hasPath("database.ssl")) config.getBoolean("database.ssl") else false
            ),
            redis = RedisConfig(
                host = config.getString("redis.host"),
                port = config.getInt("redis.port"),
                password = if (config.hasPath("redis.password")) config.getString("redis.password") else "",
                ssl = if (config.hasPath("redis.ssl")) config.getBoolean("redis.ssl") else false
            ),
            ssl = SslConfig(
                enabled = config.getBoolean("ssl.enabled"),
                certChainPath = config.getString("ssl.cert-chain-path"),
                privateKeyPath = config.getString("ssl.private-key-path")
            )
        )
    }

    /**
     * 验证配置值的有效范围（CQ-09）。
     *
     * 在应用启动早期阶段执行校验，避免无效配置导致运行时错误。
     * 端口范围遵循 IANA 标准：系统端口 0-1023 保留，动态端口 49152-65535 可选。
     *
     * @param config 已解析的 ApplicationConfig 实例
     * @throws IllegalArgumentException 当配置值超出有效范围时抛出
     */
    private fun validateConfig(config: ApplicationConfig) {
        // 端口范围校验（CQ-09: 防止使用特权端口或无效端口）
        require(config.server.port in 1024..65535) {
            "server.port 必须在 1024-65535 范围内，当前值: ${config.server.port}"
        }
        require(config.database.port in 1..65535) {
            "database.port 必须在 1-65535 范围内，当前值: ${config.database.port}"
        }
        require(config.redis.port in 1..65535) {
            "redis.port 必须在 1-65535 范围内，当前值: ${config.redis.port}"
        }

        // 连接池大小校验（CQ-09: 过大浪费资源，过小无法处理并发）
        require(config.database.poolSize in 1..100) {
            "database.pool-size 必须在 1-100 范围内，当前值: ${config.database.poolSize}"
        }
        require(config.database.minIdle in 0..config.database.poolSize) {
            "database.min-idle(${config.database.minIdle}) 不能超过 pool-size(${config.database.poolSize})"
        }

        log.info { "配置校验通过 — server.port=${config.server.port}, db.poolSize=${config.database.poolSize}, redis.port=${config.redis.port}" }
    }
}
