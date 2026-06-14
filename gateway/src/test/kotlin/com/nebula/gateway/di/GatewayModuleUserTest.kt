package com.nebula.gateway.di

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.user.BatchGetStatusHandler
import com.nebula.gateway.handler.user.BatchGetUserHandler
import com.nebula.gateway.handler.user.GetPrivacyHandler
import com.nebula.gateway.handler.user.GetProfileHandler
import com.nebula.gateway.handler.user.LoginHandler
import com.nebula.gateway.handler.user.RegisterHandler
import com.nebula.gateway.handler.user.SearchUserHandler
import com.nebula.gateway.handler.user.SetPrivacyHandler
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.user.UserService
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.test.get
import kotlin.test.assertNotNull

/**
 * User Handler 注册测试（D-32）。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GatewayModuleUserTest : HandlerRegistryTestBase() {

    /** Service 层 mock */
    private val userService = mockk<UserService>()
    private val userPrivacyService = mockk<UserPrivacyService>()

    private fun buildHandlerModule() = module {
        single { userService }
        single { userPrivacyService }

        single { LoginHandler(userService, get()) }
        single { RegisterHandler(userService) }
        single { SearchUserHandler(userService) }
        single { GetProfileHandler(userService) }
        single { BatchGetUserHandler(userService) }
        single { BatchGetStatusHandler(get(), get()) }
        single { SetPrivacyHandler(userPrivacyService, get(), get(), get()) }
        single { GetPrivacyHandler(userPrivacyService) }
    }

    @Test
    fun `UserHandlerCollector 注册全部 8 个 user handler`() = runTest {
        startKoin {
            modules(frameworkModule, PushTestModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()

        val collector = com.nebula.gateway.handler.user.UserHandlerCollector(
            GlobalContext.get().get<LoginHandler>(),
            GlobalContext.get().get<RegisterHandler>(),
            GlobalContext.get().get<SearchUserHandler>(),
            GlobalContext.get().get<GetProfileHandler>(),
            GlobalContext.get().get<BatchGetUserHandler>(),
            GlobalContext.get().get<BatchGetStatusHandler>(),
            GlobalContext.get().get<SetPrivacyHandler>(),
            GlobalContext.get().get<GetPrivacyHandler>()
        )
        collector.registerAll(registry)

        assertNotNull(registry.get("user/login"))
        assertNotNull(registry.get("user/register"))
        assertNotNull(registry.get("user/search"))
        assertNotNull(registry.get("user/getProfile"))
        assertNotNull(registry.get("user/batchGet"))
        assertNotNull(registry.get("user/batchGetStatus"))
        assertNotNull(registry.get("user/setPrivacy"))
        assertNotNull(registry.get("user/getPrivacy"))
    }
}
