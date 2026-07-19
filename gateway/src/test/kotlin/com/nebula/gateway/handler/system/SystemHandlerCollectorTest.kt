package com.nebula.gateway.handler.system

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.PingHandler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * SystemHandlerCollector 单元测试。
 *
 * 验证 registerAll() 正确注册 system/ping Handler。
 */
class SystemHandlerCollectorTest {

    @Test
    fun registerAllShouldRegisterPingHandler() = runTest {
        val registry = HandlerRegistry()
        val collector = SystemHandlerCollector(PingHandler())

        collector.registerAll(registry)

        val entry = registry.get("system/ping")
        assertNotNull(entry, "system/ping 应已注册")
    }
}
