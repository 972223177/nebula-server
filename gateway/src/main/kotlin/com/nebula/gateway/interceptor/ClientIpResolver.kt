package com.nebula.gateway.interceptor

import io.grpc.Metadata
import java.net.InetSocketAddress
import java.net.SocketAddress

/**
 * 客户端真实 IP 解析器（D-XX）—— 从 gRPC 传输层与可信代理头解析不可伪造的客户端 IP。
 *
 * 设计动机：
 * - 业务层旧实现从业务 `Request.metadataMap` 读取 `x-client-ip` / `x-forwarded-for`，
 *   而该 metadata 由客户端 App 自填，可被任意伪造，地理定位与限流 key 均不可信。
 * - 正确来源是 gRPC 传输层 `ServerCall` 的 `TRANSPORT_ATTR_REMOTE_ADDR`（Netty 设置，客户端无法伪造）。
 *
 * 拓扑处理（关键）：
 * - 直连（对端不在可信代理网段）：直接使用传输层真实 socket IP，忽略客户端自填的代理头。
 * - 前置可信反向代理 / LB（对端落在可信网段）：传输层 IP 是代理本身，真实客户端 IP 仅在代理
 *   写入的 `x-forwarded-for` 中，取首个条目（RFC 7239：左起第一个为原始客户端）。
 *
 * 本类为纯函数，无副作用、不依赖 Koin，便于单元测试。
 */
object ClientIpResolver {

    /** x-forwarded-for 的 gRPC Metadata Key（gRPC 自动以小写存储）。 */
    private val X_FORWARDED_FOR_KEY: Metadata.Key<String> =
        Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER)

    /**
     * 解析真实客户端 IP。
     *
     * @param remoteAddr gRPC `ServerCall.attributes[TRANSPORT_ATTR_REMOTE_ADDR]`，传输层对端地址
     * @param headers gRPC 入站 Metadata（代理写入的 `x-forwarded-for` 在此，而非业务 metadata）
     * @param trustedProxies 可信代理网段 CIDR 列表（空=永不信任代理头）
     * @return 真实客户端 IP 字符串；无法解析时返回 "unknown"
     */
    fun resolve(remoteAddr: SocketAddress?, headers: Metadata, trustedProxies: List<String>): String {
        val peerIp = (remoteAddr as? InetSocketAddress)?.hostString
            ?: remoteAddr?.toString()
            ?: "unknown"

        val trusted = IpCidr.parseList(trustedProxies)
        return if (trusted.any { it.contains(peerIp) }) {
            // 对端是可信代理：取 x-forwarded-for 首个条目（原始客户端），缺失则回退到代理 IP
            headers[X_FORWARDED_FOR_KEY]
                ?.split(',')
                ?.firstOrNull()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: peerIp
        } else {
            // 直连或不可信对端：直接使用传输层真实 socket IP，忽略客户端伪造的代理头
            peerIp
        }
    }
}

/**
 * IPv4 CIDR 网段匹配器（D-XX）。
 *
 * 支持：
 * - 单 IP：`"192.168.1.1"`（等价于 `/32`）
 * - CIDR：`"10.0.0.0/8"`、`"192.168.0.0/16"`
 *
 * 仅处理 IPv4；IPv6 或非 CIDR 格式在 [parseList] 中以安全降级（跳过）处理，不影响其它条目。
 *
 * @property cidr 原始 CIDR 字符串
 */
data class IpCidr(private val cidr: String) {

    private val parsed = parse(cidr)
    private val address: Int get() = parsed.first
    private val mask: Int get() = parsed.second

    /** 判断给定 IPv4 字符串是否落在该网段内。 */
    fun contains(ip: String): Boolean {
        val ipInt = ipToInt(ip) ?: return false
        return (ipInt and mask) == (address and mask)
    }

    companion object {
        /** 安全解析 CIDR 列表：跳过非法条目，不影响其它条目。 */
        fun parseList(list: List<String>): List<IpCidr> =
            list.mapNotNull { runCatching { IpCidr(it) }.getOrNull() }

        /** 将 "a.b.c.d" 解析为 32 位有符号 Int（高位在前）。非法返回 null。 */
        private fun ipToInt(ip: String): Int? {
            val parts = ip.split('.')
            if (parts.size != 4) return null
            var result = 0
            for (part in parts) {
                val v = part.toIntOrNull() ?: return null
                if (v < 0 || v > 255) return null
                result = (result shl 8) or v
            }
            return result
        }

        /** 解析 CIDR 为（网络地址 Int, 掩码 Int）。失败抛 [IllegalArgumentException]。 */
        private fun parse(cidr: String): Pair<Int, Int> {
            val (ipPart, prefixPart) = if (cidr.contains('/')) {
                val segs = cidr.split('/')
                segs[0] to segs[1].toIntOrNull()
            } else {
                cidr to null
            }
            val ipInt = ipToInt(ipPart) ?: throw IllegalArgumentException("非法 IPv4 地址: $ipPart")
            val prefix = prefixPart ?: 32
            require(prefix in 0..32) { "非法 CIDR 前缀长度: $prefix" }
            val mask = if (prefix == 0) 0 else (0xFFFFFFFF shl (32 - prefix)).toInt()
            return ipInt to mask
        }
    }
}

/**
 * 判断给定字符串是否为「可公网路由的单播 IPv4 地址」。
 *
 * 仅公网 IPv4 单播地址才可能被高德 IP 定位 API 解析；其余情形一律返回 false，由调用方
 * 本地短路降级，避免对注定查不到的 IP 发起无意义的上游配额消耗：
 * - IPv6 / 非法格式：无法按 IPv4 定位
 * - 私有网段：10.0.0.0/8、172.16.0.0/12、192.168.0.0/16
 * - 回环：127.0.0.0/8；链路本地：169.254.0.0/16
 * - CGNAT 共享地址：100.64.0.0/10
 * - 多播：224.0.0.0/4；保留：0.0.0.0/8、240.0.0.0/4
 *
 * 纯函数，不依赖 DNS 解析（避免对非法字符串误触发主机名查询），便于单元测试。
 */
fun isPublicUnicastIpv4(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    val b = IntArray(4) { parts[it].toIntOrNull() ?: return false }
    if (b.any { it < 0 || it > 255 }) return false
    val (a, c) = b[0] to b[1]
    if (a == 0) return false                          // 0.0.0.0/8 本网络
    if (a == 10) return false                         // 10.0.0.0/8 私有
    if (a == 127) return false                        // 127.0.0.0/8 回环
    if (a == 169 && c == 254) return false            // 169.254.0.0/16 链路本地
    if (a == 172 && c in 16..31) return false         // 172.16.0.0/12 私有
    if (a == 192 && c == 168) return false            // 192.168.0.0/16 私有
    if (a == 100 && c in 64..127) return false        // 100.64.0.0/10 CGNAT
    if (a in 224..239) return false                   // 224.0.0.0/4 多播
    if (a >= 240) return false                        // 240.0.0.0/4 保留
    return true
}
