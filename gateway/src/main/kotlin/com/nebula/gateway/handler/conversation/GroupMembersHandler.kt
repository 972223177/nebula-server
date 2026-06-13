package com.nebula.gateway.handler.conversation

import com.nebula.chat.conversation.GroupMembersReq
import com.nebula.chat.conversation.GroupMembersResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 群成员列表 Handler — method = "conversation/group_members"（D-06）。
 *
 * 委托 ConversationService 查询群成员列表。
 *
 * @param conversationService 会话业务服务
 */
class GroupMembersHandler(
    private val conversationService: ConversationService
) : Handler<GroupMembersReq, GroupMembersResp> {

    override val method: String = "conversation/group_members"

    override suspend fun handle(req: GroupMembersReq): GroupMembersResp {
        val session = currentCoroutineContext().requireSession()
        return conversationService.getGroupMembers(req, session.userId)
    }
}
