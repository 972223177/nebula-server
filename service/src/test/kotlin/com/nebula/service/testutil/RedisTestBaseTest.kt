package com.nebula.service.testutil

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * [RedisTestBase] 的验证测试 — 确认 Testcontainers Redis 容器可正常连接并执行基本操作。
 *
 * 验证内容：
 * - SET/GET 基本读写操作正常
 * - 通过 [RedisTestBase.getCommands] 获取的协程命令接口可用
 */
class RedisTestBaseTest : RedisTestBase() {

    @Test
    fun setAndGetShouldWorkCorrectly() = runTest {
        // Given: 通过基类获取 Redis 协程命令接口
        val commands = getCommands()

        // When: 执行 SET/GET 操作
        commands.set("test:key", "hello-redis")
        val value = commands.get("test:key")

        // Then: GET 应返回 SET 的值
        assertEquals("hello-redis", value, "SET 后 GET 应返回相同的值")
    }
}
