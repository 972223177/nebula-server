package com.nebula.gateway.handler.user

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.Handler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * UserHandlerCollector 单元测试。
 *
 * 验证 registerAll() 正确注册所有 8 个用户业务 Handler。
 */
class UserHandlerCollectorTest {

    @Test
    fun `registerAll registers all user handlers`() = runTest {
        val registry = HandlerRegistry()
        val collector = UserHandlerCollector(
            loginHandler = mockk { every { method } returns "user/login" },
            registerHandler = mockk { every { method } returns "user/register" },
            searchUserHandler = mockk { every { method } returns "user/search" },
            getProfileHandler = mockk { every { method } returns "user/getProfile" },
            batchGetUserHandler = mockk { every { method } returns "user/batchGet" },
            batchGetStatusHandler = mockk { every { method } returns "user/batchGetStatus" },
            setPrivacyHandler = mockk { every { method } returns "user/setPrivacy" },
            getPrivacyHandler = mockk { every { method } returns "user/getPrivacy" }
        )

        collector.registerAll(registry)

        assertNotNull(registry.get("user/login"), "user/login 应已注册")
        assertNotNull(registry.get("user/register"), "user/register 应已注册")
        assertNotNull(registry.get("user/search"), "user/search 应已注册")
        assertNotNull(registry.get("user/getProfile"), "user/getProfile 应已注册")
        assertNotNull(registry.get("user/batchGet"), "user/batchGet 应已注册")
        assertNotNull(registry.get("user/batchGetStatus"), "user/batchGetStatus 应已注册")
        assertNotNull(registry.get("user/setPrivacy"), "user/setPrivacy 应已注册")
        assertNotNull(registry.get("user/getPrivacy"), "user/getPrivacy 应已注册")
    }
}
