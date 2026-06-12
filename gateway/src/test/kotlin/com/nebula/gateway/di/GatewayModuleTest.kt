package com.nebula.gateway.di

import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.PingHandler
import com.nebula.repository.redis.SessionRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.dsl.module
import org.koin.test.KoinTest
import org.koin.test.get
import org.koin.test.inject
import org.koin.test.junit5.KoinTestExtension
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * GatewayModule Koin 模块装配测试（D-23, D-24）。
 *
 * 使用 KoinTestExtension 加载 frameworkModule + handlerModule，
 * 验证所有组件可被 Koin 正确解析和装配。
 * SessionRepository 使用 MockK mock 对象，因为其依赖 Redis 连接。
 */
class GatewayModuleTest : KoinTest {

    /** Mock SessionRepository — SessionRegistry 依赖此实例 */
    private val sessionRepo = mockk<SessionRepository>()

    /** Koin 测试扩展 — 加载 frameworkModule 和 handlerModule + mock SessionRepository */
    @JvmField
    @RegisterExtension
    val koinTestExtension = KoinTestExtension.create {
        modules(
            frameworkModule,
            handlerModule,
            module {
                single { sessionRepo }
            }
        )
    }

    /** Koin 注入的组件 */
    private val registry: HandlerRegistry by inject()
    private val pingHandler: PingHandler by inject()

    @Test
    fun `frameworkModule resolves HandlerRegistry`() {
        val handlerRegistry = get<HandlerRegistry>()
        assertNotNull(handlerRegistry)
    }

    @Test
    fun `frameworkModule resolves dependencies required by Dispatcher`() {
        // Dispatcher 不在 Koin 模块中注册，但可通过 get() 手动构建所需的依赖
        val handlerRegistry = get<HandlerRegistry>()
        val protoCodec = get<ProtoCodec>()
        assertNotNull(handlerRegistry)
        assertNotNull(protoCodec)
    }

    @Test
    fun `handlerModule resolves PingHandler`() {
        assertNotNull(pingHandler)
    }

    @Test
    fun `registerHandlers registers ping handler`() = runTest {
        val registry = get<HandlerRegistry>()
        val codec = get<ProtoCodec>()
        registerHandlers(registry, codec, pingHandler)

        val entry = registry.get("system/ping")
        assertNotNull(entry)
        assertSame(pingHandler, entry.handler)
    }
}
