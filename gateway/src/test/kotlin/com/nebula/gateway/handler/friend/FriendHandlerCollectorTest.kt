package com.nebula.gateway.handler.friend

import com.nebula.gateway.dispatcher.HandlerRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * FriendHandlerCollector 单元测试。
 *
 * 验证 registerAll() 正确注册所有 6 个好友业务 Handler。
 */
class FriendHandlerCollectorTest {

    @Test
    fun registerAllShouldRegisterAllFriendHandlers() = runTest {
        val registry = HandlerRegistry()
        val collector = FriendHandlerCollector(
            friendRejectHandler = mockk { every { method } returns "friend/reject" },
            friendRequestsHandler = mockk { every { method } returns "friend/requests" },
            friendListHandler = mockk { every { method } returns "friend/list" },
            friendDeleteHandler = mockk { every { method } returns "friend/delete" },
            friendAddHandler = mockk { every { method } returns "friend/add" },
            friendAcceptHandler = mockk { every { method } returns "friend/accept" }
        )

        collector.registerAll(registry)

        assertNotNull(registry.get("friend/reject"), "friend/reject 应已注册")
        assertNotNull(registry.get("friend/requests"), "friend/requests 应已注册")
        assertNotNull(registry.get("friend/list"), "friend/list 应已注册")
        assertNotNull(registry.get("friend/delete"), "friend/delete 应已注册")
        assertNotNull(registry.get("friend/add"), "friend/add 应已注册")
        assertNotNull(registry.get("friend/accept"), "friend/accept 应已注册")
    }
}
