package com.nebula.service.user

import com.nebula.chat.user.GetProfileResp
import com.nebula.chat.user.UserBrief
import com.nebula.repository.entity.UserEntity
import java.time.ZoneOffset

/**
 * 用户 Entity → Protobuf 共享 mapper（2026-07-29 从 [com.nebula.service.user.UserServiceImpl] 内联构造迁入，仿 `conversation/ConversationMappers.kt`）。
 *
 * `UserBrief`（搜索/批量结果）与 `GetProfileResp`（资料详情）的字段集高度同源，
 * 之前在 [com.nebula.service.user.UserServiceImpl] 两处内联 `newBuilder()`，存在双源真相风险。
 * 抽到此处集中维护，任何用户字段变更只需改本文件一处。
 *
 * 设计决策：用扩展函数（[UserEntity.toUserBrief] / [UserEntity.toGetProfileResp]）而非工具类，
 * 调用方更自然且零样板；不放 repository / common 模块（proto 类属业务语义）。
 */

/**
 * 用户 Entity → [UserBrief]（搜索 / 批量查询结果中的单条用户）。
 *
 * @return 完整构造的 UserBrief
 */
fun UserEntity.toUserBrief(): UserBrief =
    UserBrief.newBuilder()
        .setUid(requireNotNull(id) { "用户ID不能为null" })
        .setUsername(username)
        .setDisplayName(nickname)
        .setAvatarUrl(avatar)
        .setCreatedAt(createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
        .build()

/**
 * 用户 Entity → [GetProfileResp]（资料详情）。
 *
 * @return 完整构造的 GetProfileResp
 */
fun UserEntity.toGetProfileResp(): GetProfileResp =
    GetProfileResp.newBuilder()
        .setUid(requireNotNull(id) { "用户ID不能为null" })
        .setUsername(username)
        .setDisplayName(nickname)
        .setAvatarUrl(avatar)
        .setCreatedAt(createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
        .build()
