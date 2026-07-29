package com.nebula.service.user

import com.nebula.chat.user.FriendApprovalMode
import com.nebula.chat.user.GetPrivacyResp

/**
 * 用户隐私设置响应 mapper（2026-07-29 从 [com.nebula.service.user.UserPrivacyServiceImpl] 内联构造迁入，仿 `conversation/ConversationMappers.kt`）。
 *
 * `getPrivacySettings` 的响应由隐藏标志 + 好友申请通过模式枚举拼装，内联 `newBuilder()` 易产生双源真相，
 * 抽到此处集中维护。
 *
 * @param hideOnlineStatus 是否隐藏在线状态
 * @param approvalModeNumber 好友申请通过模式枚举数字值（[FriendApprovalMode]）
 * @return 拼装好的 GetPrivacyResp
 */
fun buildGetPrivacyResp(hideOnlineStatus: Boolean, approvalModeNumber: Int): GetPrivacyResp =
    GetPrivacyResp.newBuilder()
        .setHideOnlineStatus(hideOnlineStatus)
        .setFriendApproval(FriendApprovalMode.forNumber(approvalModeNumber))
        .build()
