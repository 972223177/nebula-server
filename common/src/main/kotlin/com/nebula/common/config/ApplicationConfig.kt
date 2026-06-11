package com.nebula.common.config

data class ApplicationConfig(
    val env: String,
    val server: ServerConfig,
    val snowflake: SnowflakeConfig,
    val database: DatabaseConfig,
    val ssl: SslConfig
)
