package com.nebula.common.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [SslConfig.buildSslContext] 的单元测试。
 *
 * 测试策略：
 * - 前置条件校验（文件存在性、可读性）通过临时文件 + setReadable(false) 模拟
 * - 成功构建 SslContext 的完整路径标记为集成测试（需真实 PEM 证书/私钥）
 * - 使用 JUnit5 @TempDir 确保临时文件自动清理
 */
class SslConfigTest {

    // ─── 1. enabled=false 时跳过 SSL 构建 ──────────────────────────────────────

    @Test
    fun whenDisabledReturnsNull() {
        // Given: 禁用 SSL，路径不存在的文件（但由于 enabled=false 不会被访问）
        val config = SslConfig(
            enabled = false,
            certChainPath = "/nonexistent/cert.pem",
            privateKeyPath = "/nonexistent/key.pem"
        )

        // When: 构建 SSL Context
        val result = config.buildSslContext()

        // Then: 返回 null，不尝试读取文件
        assertNull(result, "禁用 SSL 时应返回 null")
    }

    // ─── 2. 证书链文件存在性校验 ──────────────────────────────────────────────

    @Test
    fun certChainFileNotExistsThrowsException() {
        // Given: 启用 SSL，但证书链文件路径指向不存在的文件
        val config = SslConfig(
            enabled = true,
            certChainPath = "/tmp/nonexistent-cert-${System.nanoTime()}.pem",
            privateKeyPath = "/tmp/nonexistent-key.pem"
        )

        // When & Then: 抛出 IllegalArgumentException
        val ex = assertThrows<IllegalArgumentException> {
            config.buildSslContext()
        }
        assertTrue(
            ex.message?.contains("证书链文件不存在") == true,
            "异常消息应包含「证书链文件不存在」，实际: ${ex.message}"
        )
    }

    // ─── 3. 证书链文件可读性校验 ──────────────────────────────────────────────

    @Test
    fun certChainFileUnreadableThrowsException(@TempDir tempDir: Path) {
        // Given: 创建证书链临时文件并设为不可读
        val certFile = tempDir.resolve("unreadable-cert.pem").toFile()
        certFile.writeText("fake certificate content")
        val setReadableResult = certFile.setReadable(false)
        // 注意：在某些系统（如 root 用户）下 setReadable(false) 可能失败，
        // 此时该测试无法覆盖"文件不可读"分支，但"文件不存在"已覆盖前置校验路径
        if (!setReadableResult) {
            return // 跳过：当前环境不支持文件权限控制
        }

        val config = SslConfig(
            enabled = true,
            certChainPath = certFile.absolutePath,
            privateKeyPath = "/tmp/nonexistent-key.pem"
        )

        try {
            // When & Then: 在访问私钥文件前就因证书不可读而失败
            val ex = assertThrows<IllegalArgumentException> {
                config.buildSslContext()
            }
            assertTrue(
                ex.message?.contains("证书链文件不可读") == true,
                "异常消息应包含「证书链文件不可读」，实际: ${ex.message}"
            )
        } finally {
            // 恢复可读权限以便 @TempDir 清理
            certFile.setReadable(true)
        }
    }

    // ─── 4. 私钥文件存在性校验 ────────────────────────────────────────────────

    @Test
    fun privateKeyFileNotExistsThrowsException(@TempDir tempDir: Path) {
        // Given: 证书链文件存在且可读，但私钥文件不存在
        val certFile = tempDir.resolve("valid-cert.pem").toFile()
        certFile.writeText("fake certificate content")

        val config = SslConfig(
            enabled = true,
            certChainPath = certFile.absolutePath,
            privateKeyPath = "/tmp/nonexistent-key-${System.nanoTime()}.pem"
        )

        // When & Then: 在校验证书链通过后，校验私钥存在性时失败
        val ex = assertThrows<IllegalArgumentException> {
            config.buildSslContext()
        }
        assertTrue(
            ex.message?.contains("私钥文件不存在") == true,
            "异常消息应包含「私钥文件不存在」，实际: ${ex.message}"
        )
    }

    // ─── 5. 私钥文件可读性校验 ────────────────────────────────────────────────

    @Test
    fun privateKeyFileUnreadableThrowsException(@TempDir tempDir: Path) {
        // Given: 证书链文件存在且可读，私钥文件存在但不可读
        val certFile = tempDir.resolve("valid-cert.pem").toFile()
        certFile.writeText("fake certificate content")

        val keyFile = tempDir.resolve("unreadable-key.pem").toFile()
        keyFile.writeText("fake private key content")
        val setReadableResult = keyFile.setReadable(false)
        if (!setReadableResult) {
            return // 跳过：当前环境不支持文件权限控制
        }

        val config = SslConfig(
            enabled = true,
            certChainPath = certFile.absolutePath,
            privateKeyPath = keyFile.absolutePath
        )

        try {
            // When & Then: 证书链通过校验，私钥可读性校验失败
            val ex = assertThrows<IllegalArgumentException> {
                config.buildSslContext()
            }
            assertTrue(
                ex.message?.contains("私钥文件不可读") == true,
                "异常消息应包含「私钥文件不可读」，实际: ${ex.message}"
            )
        } finally {
            keyFile.setReadable(true)
        }
    }

    // ─── 6. 成功构建 SslContext（集成测试）────────────────────────────────────

    // TODO: 需要真实 PEM 格式证书和私钥文件，适合在集成测试阶段使用
    // @Test
    // fun `所有文件正常时返回非空 SslContext`() {
    //     // Given: 使用测试资源中的 PEM 证书和私钥
    //     // When: 调用 buildSslContext()
    //     // Then: 返回非 null 的 SslContext 实例，且协议为 TLSv1.2/TLSv1.3
    // }
}
