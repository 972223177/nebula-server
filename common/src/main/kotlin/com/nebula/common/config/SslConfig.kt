package com.nebula.common.config

data class SslConfig(
    val enabled: Boolean,
    val certChainPath: String,
    val privateKeyPath: String
)
