package com.nebula.common.config

/**
 * gRPC 服务器监听配置。
 *
 * 服务器基于 Netty 的 gRPC 实现，端口需避开系统保留区间并能被反向代理（如 Envoy）转发。
 */
data class ServerConfig(
    /** 服务器监听端口，生产环境建议通过环境变量覆盖默认值 */
    val port: Int
)
