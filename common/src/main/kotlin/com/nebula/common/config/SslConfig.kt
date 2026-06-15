package com.nebula.common.config

import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext
import io.grpc.netty.shaded.io.netty.handler.ssl.SslProvider
import java.io.File

/**
 * gRPC 传输层安全（TLS/SSL）配置。
 *
 * 开发环境可禁用 SSL 以简化本地调试，生产环境必须启用并配置有效证书链与私钥。
 */
data class SslConfig(
    /** 是否启用 SSL，开发 / 测试环境可关闭；生产环境务必为 true */
    val enabled: Boolean,
    /** X.509 证书链文件路径（PEM 格式），包含服务器证书及中间 CA */
    val certChainPath: String,
    /** 私钥文件路径（PKCS#8 PEM 格式），不得加密以避免启动时需输入密码 */
    val privateKeyPath: String
)

/**
 * 根据配置构建 Netty 层 [SslContext] 实例。
 *
 * 使用 OpenSSL 提供器（性能优于 JDK 内置），仅启用 TLSv1.2 / TLSv1.3 及现代 ECDHE 套件，
 * 禁用老旧的不安全算法以通过安全审计。
 *
 * @return 若 [SslConfig.enabled] 为 false 则返回 null；否则返回已构建的 [SslContext]
 */
fun SslConfig.buildSslContext(): SslContext? {
    if (!enabled) return null

    // CQ-09: 证书文件预校验，启动阶段快速失败而非运行时 NPE
    val certChain = File(certChainPath)
    require(certChain.exists()) {
        "SSL 证书链文件不存在: $certChainPath"
    }
    require(certChain.canRead()) {
        "SSL 证书链文件不可读: $certChainPath"
    }

    val privateKey = File(privateKeyPath)
    require(privateKey.exists()) {
        "SSL 私钥文件不存在: $privateKeyPath"
    }
    require(privateKey.canRead()) {
        "SSL 私钥文件不可读: $privateKeyPath"
    }

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
