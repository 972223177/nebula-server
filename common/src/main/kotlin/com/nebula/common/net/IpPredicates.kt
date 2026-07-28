package com.nebula.common.net

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
 * 从 gateway.interceptor 迁移至 common，使 service 模块（GeoIpInvoker）可直接复用，
 * 不引入 gateway→service 反向依赖。
 */
fun isPublicUnicastIpv4(ip: String): Boolean {
    val parts = ip.split('.')
    if (parts.size != 4) return false
    val b = IntArray(4) { parts[it].toIntOrNull() ?: return false }
    if (b.any { it !in 0..255 }) return false
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
