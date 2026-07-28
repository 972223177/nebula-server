package com.nebula.gateway.interceptor

import com.nebula.common.net.isPublicUnicastIpv4
import io.grpc.Metadata
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ClientIpResolver 单元测试 —— 验证"传输层真实 IP"解析逻辑（防伪 + 可信代理）。
 */
class ClientIpResolverTest {

    private fun metadataWithXff(xff: String?): Metadata {
        val m = Metadata()
        if (xff != null) {
            m.put(Metadata.Key.of("x-forwarded-for", Metadata.ASCII_STRING_MARSHALLER), xff)
        }
        return m
    }

    @Test
    fun directConnectionUsesTransportIp() {
        val addr = InetSocketAddress("203.0.113.9", 54321)
        assertEquals("203.0.113.9", ClientIpResolver.resolve(addr, metadataWithXff("1.2.3.4"), emptyList()))
    }

    @Test
    fun directConnectionIgnoresClientSpoofedXff() {
        // 客户端自填 x-forwarded-for，但不在可信网段 → 忽略，使用传输层真实 IP
        val addr = InetSocketAddress("203.0.113.9", 54321)
        assertEquals("203.0.113.9", ClientIpResolver.resolve(addr, metadataWithXff("9.9.9.9"), emptyList()))
    }

    @Test
    fun trustedProxyUsesXffFirstEntry() {
        val addr = InetSocketAddress("10.0.0.1", 1234) // 属于 10.0.0.0/8
        assertEquals(
            "198.51.100.7",
            ClientIpResolver.resolve(addr, metadataWithXff("198.51.100.7, 10.0.0.1"), listOf("10.0.0.0/8"))
        )
    }

    @Test
    fun trustedProxyMissingXffFallsBackToProxyIp() {
        val addr = InetSocketAddress("10.0.0.1", 1234)
        assertEquals("10.0.0.1", ClientIpResolver.resolve(addr, metadataWithXff(null), listOf("10.0.0.0/8")))
    }

    @Test
    fun untrustedPeerStillUsesTransportIpNotXff() {
        // 192.168.x 对端但不在 trustedProxies → 不使用 XFF（防伪造）
        val addr = InetSocketAddress("192.168.1.5", 1234)
        assertEquals("192.168.1.5", ClientIpResolver.resolve(addr, metadataWithXff("8.8.8.8"), emptyList()))
    }

    @Test
    fun nullAddressReturnsUnknown() {
        assertEquals("unknown", ClientIpResolver.resolve(null, metadataWithXff(null), emptyList()))
    }

    @Test
    fun ipCidrMatchesWithinRange() {
        assertTrue(IpCidr("10.0.0.0/8").contains("10.1.2.3"))
        assertTrue(IpCidr("192.168.0.0/16").contains("192.168.55.1"))
        assertFalse(IpCidr("192.168.0.0/16").contains("192.169.0.1"))
        assertTrue(IpCidr("203.0.113.9").contains("203.0.113.9")) // 单 IP 等价于 /32
    }

    @Test
    fun isPublicUnicastIpv4AcceptsPublicAddresses() {
        // 公网单播 IPv4（含公网 DNS 解析服务器与文档公网段）
        assertTrue(isPublicUnicastIpv4("8.8.8.8"))
        assertTrue(isPublicUnicastIpv4("1.1.1.1"))
        assertTrue(isPublicUnicastIpv4("203.0.113.7"))
        assertTrue(isPublicUnicastIpv4("198.51.100.7"))
        assertTrue(isPublicUnicastIpv4("223.255.255.254")) // 公网段上限前
    }

    @Test
    fun isPublicUnicastIpv4RejectsPrivateAndReserved() {
        // 私有网段
        assertFalse(isPublicUnicastIpv4("10.0.0.1"))
        assertFalse(isPublicUnicastIpv4("172.16.0.1"))
        assertFalse(isPublicUnicastIpv4("172.31.255.255"))
        assertFalse(isPublicUnicastIpv4("192.168.1.5"))
        // 回环 / 链路本地 / CGNAT
        assertFalse(isPublicUnicastIpv4("127.0.0.1"))
        assertFalse(isPublicUnicastIpv4("169.254.0.1"))
        assertFalse(isPublicUnicastIpv4("100.64.0.1"))
        assertFalse(isPublicUnicastIpv4("100.127.255.254"))
        // 本网络 / 多播 / 保留
        assertFalse(isPublicUnicastIpv4("0.0.0.0"))
        assertFalse(isPublicUnicastIpv4("224.0.0.1"))
        assertFalse(isPublicUnicastIpv4("240.0.0.1"))
    }

    @Test
    fun isPublicUnicastIpv4RejectsInvalidFormats() {
        // 非法或不可按 IPv4 定位的格式
        assertFalse(isPublicUnicastIpv4("1.2.3"))           // 不足 4 段
        assertFalse(isPublicUnicastIpv4("1.2.3.4.5"))       // 超 4 段
        assertFalse(isPublicUnicastIpv4("256.1.1.1"))       // 段越界
        assertFalse(isPublicUnicastIpv4("abc"))             // 非数字
        assertFalse(isPublicUnicastIpv4("::1"))             // IPv6
        assertFalse(isPublicUnicastIpv4(""))                // 空串
    }
}
