package com.nebula.service.conversation

import com.nebula.chat.group.GroupMemberRole

/**
 * 会话领域共享常量（与 SQL DDL / proto 定义一致）。
 *
 * 原单体 [ConversationService] 按子域拆为 [GroupService] 与 [ConversationQueryService] 后，
 * 这些跨子域复用的常量集中此处，避免两份拷贝漂移（D-02/D-05/D-17/D-19 等）。
 */

/** 私聊会话类型（CQ-12: 1=私聊，与 SQL DDL 一致） */
internal const val CONV_TYPE_PRIVATE = 1

/** 群聊会话类型 */
internal const val CONV_TYPE_GROUP = 2

/**
 * 群主角色标识（DB 存储值，由枚举名推导，与 [GroupMemberRole] / [toGroupMemberRole] 单一事实源一致）。
 */
internal val ROLE_OWNER = GroupMemberRole.OWNER.name.lowercase()

/** 群成员角色标识（由枚举名推导） */
internal val ROLE_MEMBER = GroupMemberRole.MEMBER.name.lowercase()

/** 字符串 → 群成员角色枚举，无法识别（含历史脏数据）回落 [GroupMemberRole.GROUP_MEMBER_ROLE_UNKNOWN]。 */
fun String.toGroupMemberRole(): GroupMemberRole =
    GroupMemberRole.entries.firstOrNull { it.name.lowercase() == this } ?: GroupMemberRole.GROUP_MEMBER_ROLE_UNKNOWN

/** 群组已解散状态值（D-17: status=1） */
internal const val STATUS_DISSOLVED = 1

/** 会话列表单页最大条数 */
internal const val MAX_LIST_LIMIT = 50
