package com.nebula.service.conversation

import com.nebula.chat.conversation.ConversationBrief
import com.nebula.common.enum.ConversationType
import com.nebula.common.util.toEpochMillis
import com.nebula.repository.entity.ConversationEntity

/**
 * 会话 Entity → Brief 响应共享 mapper（2026-07 抽取）。
 *
 * 之前 ConversationService 和 MessageService 各自内联构造 ConversationBrief，
 * 存在「双源真相」风险——任何字段变更必须同步修改两处。
 * 抽到 service 包内的扩展函数（不引入新的模块依赖，service ↔ service 内部互通），
 * 任何 ConversationBrief 字段变更只需改本文件一处。
 *
 * 设计决策：
 * - 用扩展函数而非工具类：Kotlin 调用方更自然（entity.toConversationBrief(...)），零样板
 * - 不放 repository 模块：避免 service 跨包访问 repository 内部实现细节
 * - 不放 common 模块：proto 类（ConversationBrief）属于业务语义，不该在 common 暴露
 *
 * ## 字段集（必须保持稳定）
 *
 * - `conversationId`：会话 ID（必填）
 * - `type`：私聊 "private"，群聊 "group"（来自 [ConversationType] 枚举）
 * - `name`：私聊由调用方传 [displayName]（对方昵称/用户名），群聊由调用方传 entity.name
 * - `avatarUrl`：会话头像
 * - `lastMessageId/Preview/Ts`：最后一条消息元信息
 * - `lastUpdatedAt`：从 entity.updatedAt 转 epoch 毫秒
 * - `lastReadMsgId`：当前用户在该会话的已读水位线
 *
 * 任何 ConversationBrief 字段变更必须同步更新本文件 + 搜索引用。
 *
 * @param displayName 私聊场景下的对方显示名（群聊场景传 entity.name 即可）
 * @param lastReadMessageId 当前用户的 lastReadMessageId，0 表示从未读
 * @return 完整构造的 ConversationBrief
 * @throws NoSuchElementException 当 [ConversationType.fromCode] 找不到对应 code（数据损坏）
 */
fun ConversationEntity.toConversationBrief(
    displayName: String,
    lastReadMessageId: Long = 0L
): ConversationBrief {
    return ConversationBrief.newBuilder()
        .setConversationId(requireNotNull(id) { "会话ID不能为null" })
        .setType(ConversationType.fromCode(type).name.lowercase())
        .setName(displayName)
        .setAvatarUrl(avatar)
        .setLastMessageId(lastMessageId)
        .setLastMessagePreview(lastMessagePreview)
        .setLastMessageTs(lastMessageTs)
        .setLastUpdatedAt(updatedAt?.toEpochMillis() ?: 0L)
        .setLastReadMsgId(lastReadMessageId)
        .build()
}
