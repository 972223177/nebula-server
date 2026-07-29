package com.nebula.service.chat

/**
 * 消息业务聚合服务（Facade，2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 经 Kotlin 类委托（`by`）聚合到 [MessageServiceImpl]（实现 [MessageOperations]），
 * 编译器自动生成转发，无手工转发样板。Handler 经 `get<MessageService>()` 获取本实例，零改动。
 *
 * 业务编排逻辑全部在 [MessageServiceImpl]；本类只负责暴露契约，不含事务 / 推送 / 落库。
 */
class MessageService(impl: MessageServiceImpl) : MessageOperations by impl
