package com.nebula.gateway.handler.conversation

import com.nebula.gateway.dispatcher.HandlerRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertNotNull

/**
 * ConversationHandlerCollector 单元测试。
 *
 * 验证 registerAll() 正确注册所有 7 个群组业务 Handler。
 */
class ConversationHandlerCollectorTest {

    @Test
    fun registerAllShouldRegisterConversationHandlers() = runTest {
        val registry = HandlerRegistry()
        val collector = ConversationHandlerCollector(
            listConversationsHandler = mockk { every { method } returns "conversation/list" },
            groupMembersHandler = mockk { every { method } returns "conversation/group_members" },
            groupListHandler = mockk { every { method } returns "conversation/group_list" },
            editGroupHandler = mockk { every { method } returns "conversation/edit_group_info" },
            createGroupHandler = mockk { every { method } returns "conversation/create_group" },
            inviteMemberHandler = mockk { every { method } returns "conversation/invite_member" },
            leaveGroupHandler = mockk { every { method } returns "conversation/leave_group" },
            kickMemberHandler = mockk { every { method } returns "conversation/kick_member" },
            deleteConversationHandler = mockk { every { method } returns "conversation/delete" },
            createPrivateConversationHandler = mockk { every { method } returns "conversation/create_private" }
        )

        collector.registerAll(registry)

        assertNotNull(registry.get("conversation/list"), "conversation/list 应已注册")
        assertNotNull(registry.get("conversation/group_members"), "conversation/group_members 应已注册")
        assertNotNull(registry.get("conversation/group_list"), "conversation/group_list 应已注册")
        assertNotNull(registry.get("conversation/edit_group_info"), "conversation/edit_group_info 应已注册")
        assertNotNull(registry.get("conversation/create_group"), "conversation/create_group 应已注册")
        assertNotNull(registry.get("conversation/invite_member"), "conversation/invite_member 应已注册")
        assertNotNull(registry.get("conversation/leave_group"), "conversation/leave_group 应已注册")
        assertNotNull(registry.get("conversation/kick_member"), "conversation/kick_member 应已注册")
        assertNotNull(registry.get("conversation/delete"), "conversation/delete 应已注册")
        assertNotNull(registry.get("conversation/create_private"), "conversation/create_private 应已注册")
    }
}
