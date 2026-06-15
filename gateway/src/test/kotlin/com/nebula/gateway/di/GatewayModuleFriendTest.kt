package com.nebula.gateway.di

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.friend.FriendAcceptHandler
import com.nebula.gateway.handler.friend.FriendAddHandler
import com.nebula.gateway.handler.friend.FriendDeleteHandler
import com.nebula.gateway.handler.friend.FriendListHandler
import com.nebula.gateway.handler.friend.FriendRejectHandler
import com.nebula.gateway.handler.friend.FriendRequestsHandler
import com.nebula.gateway.push.PushService
import com.nebula.service.friend.FriendService
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
 * Friend Handler 注册测试（D-32）。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GatewayModuleFriendTest : HandlerRegistryTestBase() {

    /** Service 层 mock */
    private val pushService = mockk<PushService>()
    private val friendService = mockk<FriendService>()

    private fun buildHandlerModule() = module {
        single { pushService }
        single { friendService }
        single { ConversationLockManager() }

        single { FriendRejectHandler(friendService) }
        single { FriendRequestsHandler(friendService) }
        single { FriendListHandler(friendService) }
        single { FriendDeleteHandler(friendService) }
        single { FriendAddHandler(friendService, pushService, get(), get(), get()) }
        single { FriendAcceptHandler(friendService, pushService, get(), get(), get()) }
    }

    @Test
    fun `FriendHandlerCollector 注册全部 6 个 friend handler`() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()

        val collector = com.nebula.gateway.handler.friend.FriendHandlerCollector(
            GlobalContext.get().get<FriendRejectHandler>(),
            GlobalContext.get().get<FriendRequestsHandler>(),
            GlobalContext.get().get<FriendListHandler>(),
            GlobalContext.get().get<FriendDeleteHandler>(),
            GlobalContext.get().get<FriendAddHandler>(),
            GlobalContext.get().get<FriendAcceptHandler>()
        )
        collector.registerAll(registry)

        assertNotNull(registry.get("friend/reject"))
        assertNotNull(registry.get("friend/requests"))
        assertNotNull(registry.get("friend/list"))
        assertNotNull(registry.get("friend/delete"))
        assertNotNull(registry.get("friend/add"))
        assertNotNull(registry.get("friend/accept"))
    }
}
