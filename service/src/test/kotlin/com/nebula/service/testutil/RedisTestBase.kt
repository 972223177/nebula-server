package com.nebula.service.testutil

import io.lettuce.core.RedisClient
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.coroutines.RedisCoroutinesCommands
import io.lettuce.core.api.coroutines.RedisCoroutinesCommandsImpl
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Redis 集成测试基类 — 使用 Testcontainers 管理 Redis 7 Alpine 容器生命周期（D-14-01）。
 *
 * 继承 [DatabaseTestBase] 的测试模式（@Testcontainers + @TestInstance(PER_CLASS)），
 * 通过 GenericContainer 运行 redis:7-alpine 镜像，暴露 6379 端口。
 *
 * 子类继承后可直接调用 [getConnection] 获取 Lettuce 连接或 [getCommands] 获取协程命令接口。
 *
 * 注意：Testcontainers 依赖 Docker 环境，若 Docker 不可用测试将自动跳过。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class RedisTestBase {

    companion object {
        @Container
        private val redis: GenericContainer<Nothing> = GenericContainer<Nothing>("redis:7-alpine")
            .withExposedPorts(6379)

        private lateinit var connection: StatefulRedisConnection<String, String>
        private lateinit var redisCommands: RedisCoroutinesCommands<String, String>

        @JvmStatic
        @BeforeAll
        fun setupRedis() {
            val client = RedisClient.create(
                "redis://${redis.host}:${redis.firstMappedPort}"
            )
            connection = client.connect()
            redisCommands = RedisCoroutinesCommandsImpl(connection.reactive())
        }

        @JvmStatic
        @AfterAll
        fun tearDownRedis() {
            connection.close()
        }

        /** 获取 Lettuce Redis 同步连接。 */
        fun getConnection(): StatefulRedisConnection<String, String> = connection

        /** 获取 Lettuce Redis 协程命令接口。 */
        fun getCommands(): RedisCoroutinesCommands<String, String> = redisCommands

        /** 获取 Redis 容器映射端口号。 */
        fun getRedisPort(): Int = redis.firstMappedPort

        /** 获取 Redis 容器主机名。 */
        fun getRedisHost(): String = redis.host
    }
}
