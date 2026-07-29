package com.nebula.gateway.di

import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.MethodNames
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.assertNotNull

/**
 * GatewayModule Koin 模块装配测试（D-23, D-24）。
 *
 * 方案 A 重构（2026-06-20）：Service/Handler 不再依赖 Spring Data Repository 与 TransactionTemplate，
 * 改用 DAO + JpaTxRunner 注入。
 *
 * Handler 注册统一由 [AllHandlerCollector] 经 Koin `getAll<Handler<*,*>>()` 自动发现，
 * 测试仅需 `getAll<HandlerCollector>().forEach { it.registerAll(registry) }` 即可覆盖全部 Handler，
 * 新增 Handler 无需在此处逐组手动构造 Collector（见 [MethodNamesConsistencyTest] 的兜底守护）。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GatewayModuleTest {

    @AfterEach
    fun tearDown() {
        stopKoin()
    }

    /** 统一注册全部 Handler（替代原先逐组手动构造 Collector） */
    private fun registerAllHandlers() {
        val registry = GlobalContext.get().get<HandlerRegistry>()
        GlobalContext.get().getAll<HandlerCollector>().forEach { it.registerAll(registry) }
    }

    @Test
    fun frameworkModuleResolvesHandlerRegistry() {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        val handlerRegistry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(handlerRegistry)
    }

    @Test
    fun frameworkModuleResolvesProtoCodecAndDependencies() {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        val handlerRegistry = GlobalContext.get().get<HandlerRegistry>()
        val protoCodec = GlobalContext.get().get<ProtoCodec>()
        assertNotNull(handlerRegistry)
        assertNotNull(protoCodec)
    }

    @Test
    fun allHandlerCollectorsRegisterAllMethodsViaGetAll() = runTest {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        registerAllHandlers()

        val registry = GlobalContext.get().get<HandlerRegistry>()
        // System Handler
        assertNotNull(registry.get(MethodNames.System.PING))

        // Phase 5: User Handler
        assertNotNull(registry.get(MethodNames.User.LOGIN))
        assertNotNull(registry.get("user/logout"))
        assertNotNull(registry.get(MethodNames.User.REGISTER))
        assertNotNull(registry.get("user/search"))
        assertNotNull(registry.get("user/getProfile"))
        assertNotNull(registry.get("user/batchGet"))
        assertNotNull(registry.get("user/batchGetStatus"))
        assertNotNull(registry.get("user/setPrivacy"))
        assertNotNull(registry.get("user/getPrivacy"))

        // Phase 6: Chat & Message Handler
        assertNotNull(registry.get(MethodNames.Chat.SEND))
        assertNotNull(registry.get("message/pull"))
        assertNotNull(registry.get("message/read"))

        // Phase 7: Conversation Handler
        assertNotNull(registry.get("conversation/list"))
        assertNotNull(registry.get("conversation/group_members"))
        assertNotNull(registry.get("conversation/edit_group_info"))
        assertNotNull(registry.get("conversation/create_group"))
        assertNotNull(registry.get("conversation/invite_member"))
        assertNotNull(registry.get("conversation/leave_group"))
        assertNotNull(registry.get("conversation/kick_member"))

        // Phase 8: Friend Handler
        assertNotNull(registry.get("friend/reject"))
        assertNotNull(registry.get("friend/requests"))
        assertNotNull(registry.get("friend/list"))
        assertNotNull(registry.get("friend/delete"))
        assertNotNull(registry.get("friend/add"))
        assertNotNull(registry.get("friend/accept"))
    }

    // ===================== 领域专项验证测试 =====================

    @Test
    fun chatHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        registerAllHandlers()
        val registry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(registry.get(MethodNames.Chat.SEND))
        assertNotNull(registry.get("message/pull"))
        assertNotNull(registry.get("message/read"))
        assertNotNull(registry.get("message/seq"))
        assertNotNull(registry.get("message/delivery_ack"))
    }

    @Test
    fun conversationHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        registerAllHandlers()
        val registry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(registry.get("conversation/list"))
        assertNotNull(registry.get("conversation/group_members"))
        assertNotNull(registry.get("conversation/group_list"))
        assertNotNull(registry.get("conversation/edit_group_info"))
        assertNotNull(registry.get("conversation/create_group"))
        assertNotNull(registry.get("conversation/invite_member"))
        assertNotNull(registry.get("conversation/leave_group"))
        assertNotNull(registry.get("conversation/kick_member"))
        assertNotNull(registry.get("conversation/delete"))
        assertNotNull(registry.get("conversation/create_private"))
    }

    @Test
    fun friendHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        registerAllHandlers()
        val registry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(registry.get("friend/reject"))
        assertNotNull(registry.get("friend/requests"))
        assertNotNull(registry.get("friend/list"))
        assertNotNull(registry.get("friend/delete"))
        assertNotNull(registry.get("friend/add"))
        assertNotNull(registry.get("friend/accept"))
        assertNotNull(registry.get("friend/check"))
        assertNotNull(registry.get("friend/batchCheck"))
    }

    @Test
    fun systemHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        registerAllHandlers()
        val registry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(registry.get(MethodNames.System.PING))
    }

    @Test
    fun userHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        registerAllHandlers()
        val registry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(registry.get(MethodNames.User.LOGIN))
        assertNotNull(registry.get("user/logout"))
        assertNotNull(registry.get(MethodNames.User.REGISTER))
        assertNotNull(registry.get("user/search"))
        assertNotNull(registry.get("user/getProfile"))
        assertNotNull(registry.get("user/batchGet"))
        assertNotNull(registry.get("user/batchGetStatus"))
        assertNotNull(registry.get("user/setPrivacy"))
        assertNotNull(registry.get("user/getPrivacy"))
    }

    @Test
    fun sensitiveWordHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        registerAllHandlers()
        val registry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(registry.get("system/sensitive_word_download"))
        assertNotNull(registry.get("admin/sensitive_word_reload"))
    }

    @Test
    fun externalHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, GatewayTestModules.handlerModule(), GatewayTestModules.externalModule())
        }
        registerAllHandlers()
        val registry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(registry.get("external/query_weather"))
        assertNotNull(registry.get("external/web_search"))
        assertNotNull(registry.get("external/geo_ip"))
        assertNotNull(registry.get("external/list_services"))
        assertNotNull(registry.get("external/call_service"))
    }
}
