package com.nebula.service.conversation

import com.nebula.chat.conversation.*

/**
 * 群生命周期 + 成员管理子域契约（D-02, D-05, D-10, D-19）。
 *
 * 由 [GroupService] 实现，供 [ConversationService] Facade 经 Kotlin 类委托（`by`）聚合，
 * 使 Handler 层依赖接口契约而非具体实现（接口隔离原则）。方法签名与 [GroupService] 实现严格一致。
 */
interface GroupOperations {

    /** 创建群聊（D-02, D-05, D-10, D-19） */
    suspend fun createGroup(req: CreateGroupReq, ownerUid: Long): CreateGroupResult

    /** 邀请成员加入群聊 */
    suspend fun inviteMember(req: InviteMemberReq, operatorUid: Long): List<Long>

    /** 退出群聊 */
    suspend fun leaveGroup(req: LeaveGroupReq, userId: Long)

    /** 解散群组（群主操作） */
    suspend fun dissolveGroup(convId: String)

    /** 踢出成员 */
    suspend fun kickMember(req: KickMemberReq, operatorUid: Long): Long

    /** 编辑群信息 */
    suspend fun editGroupInfo(req: EditGroupReq, operatorUid: Long)

    /** 查询群成员列表 */
    suspend fun getGroupMembers(req: GroupMembersReq, userId: Long): GroupMembersResp

    /** 查询指定用户在会话中的成员角色，不存在时返回 null */
    suspend fun getMemberRole(conversationId: String, userId: Long): ConversationMemberInfo?

    /** 检查用户是否是指定会话的活跃成员 */
    suspend fun requireMemberActive(conversationId: String, userId: Long): Boolean
}
