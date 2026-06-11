package com.nebula.server.config

import com.nebula.common.config.ApplicationConfig
import com.nebula.common.config.DatabaseConfig
import com.nebula.common.config.ServerConfig
import com.nebula.common.config.SnowflakeConfig
import com.nebula.common.config.SslConfig
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File

object ConfigLoader {

    private val log = KotlinLogging.logger {}

    fun load(configPath: String = "config/application.conf"): ApplicationConfig {
        val env = System.getenv("ENV") ?: "dev"
        log.info { "Loading configuration from $configPath (env: $env)" }

        val fileConfig = ConfigFactory.parseFile(File(configPath))
        val resolvedConfig = ConfigFactory.systemProperties()
            .withFallback(fileConfig)
            .withFallback(ConfigFactory.defaultReference())
            .resolve()

        return parseConfig(resolvedConfig, env)
    }

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
            ssl = SslConfig(
                enabled = config.getBoolean("ssl.enabled"),
                certChainPath = config.getString("ssl.cert-chain-path"),
                privateKeyPath = config.getString("ssl.private-key-path")
            )
        )
    }
}
