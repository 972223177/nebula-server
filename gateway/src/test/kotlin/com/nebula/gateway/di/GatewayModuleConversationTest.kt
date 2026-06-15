package com.nebula.gateway.di

import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.conversation.CreateGroupHandler
import com.nebula.gateway.handler.conversation.EditGroupHandler
import com.nebula.gateway.handler.conversation.GroupMembersHandler
import com.nebula.gateway.handler.conversation.InviteMemberHandler
import com.nebula.gateway.handler.conversation.KickMemberHandler
import com.nebula.gateway.handler.conversation.LeaveGroupHandler
import com.nebula.gateway.handler.conversation.ListConversationsHandler
import com.nebula.gateway.push.PushService
import com.nebula.service.conversation.ConversationService
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
 * Conversation Handler 注册测试（D-32）。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GatewayModuleConversationTest : HandlerRegistryTestBase() {

    /** Service 层 mock */
    private val pushService = mockk<PushService>()
    private val conversationService = mockk<ConversationService>()

    private fun buildHandlerModule() = module {
        single { pushService }
        single { conversationService }

        single { ConversationLockManager() }
        single { ListConversationsHandler(conversationService) }
        single { GroupMembersHandler(conversationService) }
        single { EditGroupHandler(conversationService, pushService) }
        single { CreateGroupHandler(conversationService, get(), get(), pushService) }
        single { InviteMemberHandler(conversationService, get(), get(), pushService, get()) }
        single { LeaveGroupHandler(conversationService, get(), get(), pushService, get()) }
        single { KickMemberHandler(conversationService, get(), get(), pushService, get()) }
    }

    @Test
    fun `ConversationHandlerCollector 注册全部 7 个 conversation handler`() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()

        val collector = com.nebula.gateway.handler.conversation.ConversationHandlerCollector(
            GlobalContext.get().get<ListConversationsHandler>(),
            GlobalContext.get().get<GroupMembersHandler>(),
            GlobalContext.get().get<EditGroupHandler>(),
            GlobalContext.get().get<CreateGroupHandler>(),
            GlobalContext.get().get<InviteMemberHandler>(),
            GlobalContext.get().get<LeaveGroupHandler>(),
            GlobalContext.get().get<KickMemberHandler>()
        )
        collector.registerAll(registry)

        assertNotNull(registry.get("conversation/list"))
        assertNotNull(registry.get("conversation/group_members"))
        assertNotNull(registry.get("conversation/edit_group_info"))
        assertNotNull(registry.get("conversation/create_group"))
        assertNotNull(registry.get("conversation/invite_member"))
        assertNotNull(registry.get("conversation/leave_group"))
        assertNotNull(registry.get("conversation/kick_member"))
    }
}
