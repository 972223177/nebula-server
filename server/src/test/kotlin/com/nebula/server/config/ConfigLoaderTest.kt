package com.nebula.server.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [ConfigLoader.load] 的单元测试。
 *
 * 测试策略：
 * - 使用临时文件写入 HOCON 内容，通过 [ConfigLoader.load] 公开 API 间接覆盖
 *   private 方法 [ConfigLoader.parseConfig] 和 [ConfigLoader.validateConfig] 的所有分支
 * - 端口范围校验覆盖边界值（最小有效值、最大有效值、刚好越界值）
 * - 每个测试方法独立创建临时配置文件，避免测试间状态污染
 */
class ConfigLoaderTest {

    /**
     * 写入 HOCON 配置内容到指定文件。
     *
     * @param file 目标文件
     * @param content HOCON 格式配置文本
     */
    private fun writeHocon(file: File, content: String) {
        file.writeText(content)
    }

    /**
     * 构建一份所有字段均合法的完整 HOCON 配置模板。
     *
     * 各测试方法可通过字符串替换覆盖特定字段，减少重复代码。
     */
    private fun validHocon(): String = """
        server.port = 8080
        snowflake.worker-id = 1
        snowflake.epoch = 1700000000000
        database.host = "localhost"
        database.port = 3306
        database.database = "nebula"
        database.username = "root"
        database.password = "test"
        database.pool-size = 10
        database.min-idle = 2
        database.connection-timeout = 30000
        database.idle-timeout = 600000
        database.max-lifetime = 1800000
        database.leak-detection-threshold = 60000
        redis.host = "localhost"
        redis.port = 6379
        ssl.enabled = false
        ssl.cert-chain-path = "/path/to/cert"
        ssl.private-key-path = "/path/to/key"
    """.trimIndent()

    // ─── 1. 正常配置加载 ──────────────────────────────────────────────────────

    @Test
    fun normalConfigLoadsSuccessfully(@TempDir tempDir: Path) {
        // Given: 一份完整的合法 HOCON 配置
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, validHocon())

        // When: 加载配置
        val config = ConfigLoader.load(configFile.absolutePath)

        // Then: 所有字段值匹配
        assertNotNull(config.env, "env 不应为 null")

        // Server
        assertEquals(8080, config.server.port)

        // Snowflake
        assertEquals(1L, config.snowflake.workerId)
        assertEquals(1700000000000L, config.snowflake.epoch)

        // Database
        assertEquals("localhost", config.database.host)
        assertEquals(3306, config.database.port)
        assertEquals("nebula", config.database.database)
        assertEquals("root", config.database.username)
        assertEquals("test", config.database.password)
        assertEquals(10, config.database.poolSize)
        assertEquals(2, config.database.minIdle)
        assertEquals(30000L, config.database.connectionTimeout)
        assertEquals(600000L, config.database.idleTimeout)
        assertEquals(1800000L, config.database.maxLifetime)
        assertEquals(60000L, config.database.leakDetectionThreshold)
        assertFalse(config.database.sslEnabled, "database.ssl 默认 false")

        // Redis
        assertEquals("localhost", config.redis.host)
        assertEquals(6379, config.redis.port)
        assertEquals("", config.redis.password, "未配置 password 时默认为空字符串")
        assertFalse(config.redis.ssl, "未配置 redis.ssl 时默认 false")

        // SSL
        assertFalse(config.ssl.enabled)
        assertEquals("/path/to/cert", config.ssl.certChainPath)
        assertEquals("/path/to/key", config.ssl.privateKeyPath)
    }

    // ─── 2. server.port 范围校验 ──────────────────────────────────────────────

    @Test
    fun serverPortBelow1024ThrowsException(@TempDir tempDir: Path) {
        // Given: server.port = 1023（刚好越界，1024 是有效最小值）
        val hocon = validHocon().replace("server.port = 8080", "server.port = 1023")
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When & Then
        val ex = assertThrows<IllegalArgumentException> {
            ConfigLoader.load(configFile.absolutePath)
        }
        assertTrue(
            ex.message?.contains("server.port") == true,
            "异常消息应包含「server.port」，实际: ${ex.message}"
        )
    }

    @Test
    fun serverPortAbove65535ThrowsException(@TempDir tempDir: Path) {
        // Given: server.port = 65536（刚好越界）
        val hocon = validHocon().replace("server.port = 8080", "server.port = 65536")
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When & Then
        val ex = assertThrows<IllegalArgumentException> {
            ConfigLoader.load(configFile.absolutePath)
        }
        assertTrue(
            ex.message?.contains("server.port") == true,
            "异常消息应包含「server.port」，实际: ${ex.message}"
        )
    }

    @Test
    fun serverPortAtBoundary1024LoadsSuccessfully(@TempDir tempDir: Path) {
        // Given: server.port = 1024（合法最小值边界）
        val hocon = validHocon().replace("server.port = 8080", "server.port = 1024")
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When
        val config = ConfigLoader.load(configFile.absolutePath)

        // Then: 加载成功，端口为用户指定值
        assertEquals(1024, config.server.port)
    }

    // ─── 3. database.port 范围校验 ────────────────────────────────────────────

    @Test
    fun databasePortZeroThrowsException(@TempDir tempDir: Path) {
        // Given: database.port = 0（无效端口，范围 1-65535）
        val hocon = validHocon().replace("database.port = 3306", "database.port = 0")
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When & Then
        val ex = assertThrows<IllegalArgumentException> {
            ConfigLoader.load(configFile.absolutePath)
        }
        assertTrue(
            ex.message?.contains("database.port") == true,
            "异常消息应包含「database.port」，实际: ${ex.message}"
        )
    }

    @Test
    fun databasePortAbove65535ThrowsException(@TempDir tempDir: Path) {
        // Given: database.port = 65536（刚好越界）
        val hocon = validHocon().replace("database.port = 3306", "database.port = 65536")
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When & Then
        val ex = assertThrows<IllegalArgumentException> {
            ConfigLoader.load(configFile.absolutePath)
        }
        assertTrue(
            ex.message?.contains("database.port") == true,
            "异常消息应包含「database.port」，实际: ${ex.message}"
        )
    }

    // ─── 4. pool-size 范围校验 ────────────────────────────────────────────────

    @Test
    fun poolSizeZeroThrowsException(@TempDir tempDir: Path) {
        // Given: database.pool-size = 0（无效，范围 1-100）
        val hocon = validHocon().replace("database.pool-size = 10", "database.pool-size = 0")
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When & Then
        val ex = assertThrows<IllegalArgumentException> {
            ConfigLoader.load(configFile.absolutePath)
        }
        assertTrue(
            ex.message?.contains("pool-size") == true,
            "异常消息应包含「pool-size」，实际: ${ex.message}"
        )
    }

    @Test
    fun poolSizeAbove100ThrowsException(@TempDir tempDir: Path) {
        // Given: database.pool-size = 101（刚好越界）
        val hocon = validHocon().replace("database.pool-size = 10", "database.pool-size = 101")
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When & Then
        val ex = assertThrows<IllegalArgumentException> {
            ConfigLoader.load(configFile.absolutePath)
        }
        assertTrue(
            ex.message?.contains("pool-size") == true,
            "异常消息应包含「pool-size」，实际: ${ex.message}"
        )
    }

    // ─── 5. minIdle > poolSize ────────────────────────────────────────────────

    @Test
    fun minIdleExceedsPoolSizeThrowsException(@TempDir tempDir: Path) {
        // Given: pool-size = 5, min-idle = 10（minIdle 不能超过 poolSize）
        val hocon = validHocon()
            .replace("database.pool-size = 10", "database.pool-size = 5")
            .replace("database.min-idle = 2", "database.min-idle = 10")
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When & Then
        val ex = assertThrows<IllegalArgumentException> {
            ConfigLoader.load(configFile.absolutePath)
        }
        assertTrue(
            ex.message?.contains("min-idle") == true,
            "异常消息应包含「min-idle」，实际: ${ex.message}"
        )
    }

    // ─── 6. SSL 配置默认值 ────────────────────────────────────────────────────

    @Test
    fun sslDisabledConfigFieldsCorrect(@TempDir tempDir: Path) {
        // Given: SSL 禁用配置
        val hocon = validHocon()
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When
        val config = ConfigLoader.load(configFile.absolutePath)

        // Then: SSL 配置正确传递
        assertFalse(config.ssl.enabled, "ssl.enabled 应为 false")
        assertEquals("/path/to/cert", config.ssl.certChainPath)
        assertEquals("/path/to/key", config.ssl.privateKeyPath)
    }

    @Test
    fun databaseAndRedisOptionalDefaultsCorrect(@TempDir tempDir: Path) {
        // Given: 不包含 database.ssl 和 redis.password、redis.ssl 的配置
        val hocon = """
            server.port = 8080
            snowflake.worker-id = 1
            snowflake.epoch = 1700000000000
            database.host = "localhost"
            database.port = 3306
            database.database = "nebula"
            database.username = "root"
            database.password = "test"
            database.pool-size = 10
            database.min-idle = 2
            database.connection-timeout = 30000
            database.idle-timeout = 600000
            database.max-lifetime = 1800000
            database.leak-detection-threshold = 60000
            redis.host = "localhost"
            redis.port = 6379
            ssl.enabled = false
            ssl.cert-chain-path = "/path/to/cert"
            ssl.private-key-path = "/path/to/key"
        """.trimIndent()
        val configFile = tempDir.resolve("application.conf").toFile()
        writeHocon(configFile, hocon)

        // When
        val config = ConfigLoader.load(configFile.absolutePath)

        // Then: 可选字段取默认值
        assertFalse(config.database.sslEnabled, "未配置 database.ssl 时默认 false")
        assertEquals("", config.redis.password, "未配置 redis.password 时默认空字符串")
        assertFalse(config.redis.ssl, "未配置 redis.ssl 时默认 false")
    }
}
