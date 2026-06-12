package com.nebula.gateway.dispatcher

import com.nebula.gateway.handler.Handler
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * HandlerRegistry 单元测试。
 *
 * 覆盖：
 * - 注册和查询 Handler（正常路径）
 * - 重复注册抛出异常（Pitfall 4: putIfAbsent 防重复）
 */
class HandlerRegistryTest {

    @Test
    fun `register and lookup handler`() = runTest {
        val registry = HandlerRegistry()
        val handler = mockk<Handler<*, *>> {
            every { method } returns "test.method"
        }
        val entry = HandlerEntry(
            handler = handler,
            reqClass = Unit::class,
            respClass = Unit::class,
            parseFrom = { ByteArray(0) },
            toByteArray = { ByteArray(0) }
        )

        registry.register(entry)

        val found = registry.get("test.method")
        assertNotNull(found)
        assertEquals(handler, found.handler)
    }

    @Test
    fun `duplicate registration throws`() = runTest {
        val registry = HandlerRegistry()
        val handler1 = mockk<Handler<*, *>> {
            every { method } returns "test.method"
        }
        val entry1 = HandlerEntry(
            handler = handler1,
            reqClass = Unit::class,
            respClass = Unit::class,
            parseFrom = { ByteArray(0) },
            toByteArray = { ByteArray(0) }
        )

        registry.register(entry1)

        val handler2 = mockk<Handler<*, *>> {
            every { method } returns "test.method"
        }
        val entry2 = HandlerEntry(
            handler = handler2,
            reqClass = Unit::class,
            respClass = Unit::class,
            parseFrom = { ByteArray(0) },
            toByteArray = { ByteArray(0) }
        )

        assertThrows<IllegalStateException> {
            registry.register(entry2)
        }
    }
}
