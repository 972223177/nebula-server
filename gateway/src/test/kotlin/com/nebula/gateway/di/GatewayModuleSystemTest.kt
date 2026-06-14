package com.nebula.gateway.di

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.handler.system.SystemHandlerCollector
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.koin.test.get
import kotlin.test.assertNotNull

/**
 * System Handler 注册测试（D-32）。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GatewayModuleSystemTest : HandlerRegistryTestBase() {

    private fun buildHandlerModule() = module {
        single { PingHandler() }
    }

    @Test
    fun `SystemHandlerCollector 注册 ping handler`() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()

        val pingHandler = GlobalContext.get().get<PingHandler>()
        val collector = SystemHandlerCollector(pingHandler)
        collector.registerAll(registry)

        assertNotNull(registry.get("system/ping"))
    }
}
