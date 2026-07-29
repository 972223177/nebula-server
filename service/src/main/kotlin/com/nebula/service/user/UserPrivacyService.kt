package com.nebula.service.user

/**
 * 用户隐私设置聚合服务（Facade，2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 经 Kotlin 类委托（`by`）聚合到 [UserPrivacyServiceImpl]（实现 [UserPrivacyOperations]），
 * 编译器自动生成转发，无手工转发样板。Handler 经 `get<UserPrivacyService>()` 获取，零改动。
 */
class UserPrivacyService(impl: UserPrivacyServiceImpl) : UserPrivacyOperations by impl
