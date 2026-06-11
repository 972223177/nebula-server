package com.nebula.common.config

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider
import java.io.File

data class SslConfig(
    val enabled: Boolean,
    val certChainPath: String,
    val privateKeyPath: String
)

fun SslConfig.buildSslContext(): SslContext? {
    if (!enabled) return null
    val certChain = File(certChainPath)
    val privateKey = File(privateKeyPath)
    return GrpcSslContexts
        .forServer(certChain, privateKey)
        .sslProvider(SslProvider.OPENSSL)
        .protocols("TLSv1.2", "TLSv1.3")
        .ciphers(
            listOf(
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"
            )
        )
        .build()
}
