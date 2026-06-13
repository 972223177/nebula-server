package com.nebula.gateway.handler.conversation

import com.nebula.chat.conversation.GroupMembersReq
import com.nebula.chat.conversation.GroupMembersResp
import com.nebula.chat.group.GroupMember
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.time.ZoneOffset

/**
 * 群成员列表 Handler — method = "conversation/group_members"（D-06）。
 *
 * 全量返回会话所有成员信息（含 username/displayName/avatar/role/joinedAt）。
 * 请求者必须为会话成员，非成员抛 NOT_MEMBER。
 * 每个群最多 200 人（D-05），全量查询性能可接受（D-06 无分页决策）。
 *
 * @param conversationMemberRepository 会话成员数据仓库
 * @param userRepository 用户数据仓库（用于填充用户信息）
 */
class GroupMembersHandler(
    private val conversationMemberRepository: ConversationMemberRepository,
    private val userRepository: UserRepository
) : Handler<GroupMembersReq, GroupMembersResp> {

    override val method: String = "conversation/group_members"

    override suspend fun handle(req: GroupMembersReq): GroupMembersResp {
        val session = currentCoroutineContext().requireSession()

        // 验证请求者是会话成员
        val selfMember = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(req.conversationId, session.userId)
        } ?: throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员")

        // 全量查询成员列表
        val members = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationId(req.conversationId)
        }

        if (members.isEmpty()) {
            return GroupMembersResp.getDefaultInstance()
        }

        // 批量查询用户信息
        val userIds = members.map { it.userId }
        val userMap = withContext(Dispatchers.IO) {
            userRepository.findAllById(userIds)
        }.associateBy { it.id }

        val builder = GroupMembersResp.newBuilder()
        members.forEach { member ->
            val user = userMap[member.userId]
            builder.addMembers(GroupMember.newBuilder()
                .setUid(member.userId)
                .setUsername(user?.username ?: "")
                .setDisplayName(user?.nickname ?: "")
                .setAvatarUrl(user?.avatar ?: "")
                .setRole(member.role)
                .setJoinedAt(member.joinedAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
                .build())
        }
        return builder.build()
    }
}
