package com.nebula.service.conversation

import com.nebula.chat.conversation.*

/**
 * 会话查询 / 列表 / 删除 / 私聊子域契约（D-02, D-05, D-10, D-19, D-81/H21）。
 *
 * 由 [ConversationQueryService] 实现，供 [ConversationService] Facade 经 Kotlin 类委托（`by`）聚合。
 * 方法签名与 [ConversationQueryService] 实现严格一致。
 */
interface ConversationQueryOperations {

    /** 查询用户的会话列表（游标分页） */
    suspend fun listConversations(userId: Long, cursor: Long, limit: Int): ConvListResp

    /** 流式分页获取所有未解散会话（序列号恢复批次） */
    suspend fun getActiveConversationsBatch(offset: Int): List<Pair<String, Int>>

    /** 获取所有未解散会话（序列号恢复全量） */
    suspend fun getAllActiveConversations(): List<Pair<String, Int>>

    /** 根据会话 ID 查询会话信息，不存在时返回 null */
    suspend fun getConversation(conversationId: String): ConversationInfo?

    /** 查询会话的所有成员列表 */
    suspend fun getConversationMembers(conversationId: String): List<ConversationMemberInfo>

    /** 查询用户参与的存活群组列表 */
    suspend fun listGroupConversations(userId: Long, cursor: Long, limit: Int): GroupListResp

    /** 从列表软删除会话（仅删当前用户 member 记录） */
    suspend fun deleteConversation(userId: Long, convId: String)

    /** 按会话类型分支的删除行为（业务编排） */
    suspend fun deleteConversationByType(userId: Long, convId: String): DeleteConversationAction

    /** 创建或恢复到好友的私聊会话 */
    suspend fun createPrivateConversation(userId: Long, targetUid: Long): String
}
