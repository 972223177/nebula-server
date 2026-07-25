package com.nebula.gateway.handler.conversation
import com.nebula.gateway.handler.MethodNames

import com.nebula.chat.conversation.GroupListReq
import com.nebula.chat.conversation.GroupListResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.service.conversation.ConversationService
import kotlinx.coroutines.currentCoroutineContext

/**
 * 群组列表 Handler — method = "conversation/group_list"。
 *
 * 仅返回用户参与的存活群组（type=群聊, status=正常），过滤私聊和已解散群。
 * 支持游标分页。
 *
 * @param conversationService 会话业务服务
 */
class GroupListHandler(
    private val conversationService: ConversationService
) : Handler<GroupListReq, GroupListResp> {

    override val method: String = MethodNames.Conversation.GROUP_LIST

    override suspend fun handle(req: GroupListReq): GroupListResp {
        val session = currentCoroutineContext().requireSession()
        return conversationService.listGroupConversations(session.userId, req.cursor, req.limit)
    }
}
