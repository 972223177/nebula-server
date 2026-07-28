package com.nebula.common.config

/**
 * gRPC 服务器监听配置。
 *
 * 服务器基于 Netty 的 gRPC 实现，端口需避开系统保留区间并能被反向代理（如 Envoy）转发。
 */
data class ServerConfig(
    /** 服务器监听端口，生产环境建议通过环境变量覆盖默认值 */
    val port: Int,
    /**
     * 可信代理网段（CIDR，如 "10.0.0.0/8"、"192.168.0.0/16"）。
     *
     * 仅当对端（传输层 socket 对端）落在此列表内时，才信任其写入的 `x-forwarded-for`
     * 以取得真实客户端 IP；否则一律使用传输层真实 socket IP，忽略任何客户端自填的
     * `x-client-ip` / `x-forwarded-for`，防止直连客户端伪造 IP 篡改地理定位与限流。
     * 默认空列表 = 永不信任代理头，直接使用传输层 IP（绝大多数直连部署的最优解）。
     */
    val trustedProxies: List<String> = emptyList()
)
