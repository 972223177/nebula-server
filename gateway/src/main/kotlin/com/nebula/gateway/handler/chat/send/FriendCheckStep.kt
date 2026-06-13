package com.nebula.gateway.handler.chat.send

import com.nebula.common.BizCode
import com.nebula.gateway.handler.conversation.ConversationConstants.CONV_TYPE_PRIVATE
import com.nebula.gateway.handler.friend.FriendAddHandler
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.FriendshipRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 私聊好友关系校验 Step（D-56）。
 *
 * 在 message/send 的 Step 链中，对私聊会话执行好友关系校验：
 * - 非私聊会话 → 直接通过
 * - 私聊会话 + 非好友 → 抛出 SendMessageException(NOT_FRIEND)
 *
 * 从私聊会话 ID 解析对方 UID 避免额外 DB 查询。
 * 会话 ID 格式：`private:smaller:larger`（与 FriendAddHandler.buildPrivateConvId 一致）。
 *
 * @param friendshipRepository 好友关系仓库
 * @param conversationRepository 会话仓库
 */
class FriendCheckStep(
    private val friendshipRepository: FriendshipRepository,
    private val conversationRepository: ConversationRepository
) : SendMessageStep {

    override suspend fun execute(context: SendContext): Boolean {
        // 获取会话信息
        val conv = withContext(Dispatchers.IO) {
            conversationRepository.findById(context.req.conversationId).orElse(null)
        } ?: return true  // 会话不存在，由后续 Step 处理

        // 非私聊会话，直接通过
        if (conv.type != CONV_TYPE_PRIVATE) {
            return true
        }

        // 从会话 ID 解析对方 UID
        val senderUid = context.senderUid
        val (smaller, larger) = parsePrivateConvId(context.req.conversationId)
            ?: return true  // ID 格式异常，由后续逻辑处理

        // 查询好友关系
        val friendship = withContext(Dispatchers.IO) {
            friendshipRepository.findByUserIdAndFriendId(smaller, larger)
        }

        // 非好友或已删除 → 拒绝发送
        if (friendship == null || friendship.deleted == 1) {
            throw SendMessageException(BizCode.NOT_FRIEND)
        }

        return true
    }

    companion object {
        /**
         * 解析私聊会话 ID，格式 `private:smaller:larger`。
         *
         * @return Pair(smaller, larger) 或 null（格式不匹配）
         */
        fun parsePrivateConvId(convId: String): Pair<Long, Long>? {
            val parts = convId.split(":")
            if (parts.size != 3 || parts[0] != "private") return null
            val smaller = parts[1].toLongOrNull() ?: return null
            val larger = parts[2].toLongOrNull() ?: return null
            return Pair(smaller, larger)
        }
    }
}
