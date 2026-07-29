package com.nebula.service.sequence

/**
 * 会话消息序列号聚合服务（Facade，2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 经 Kotlin 类委托（`by`）聚合到 [SeqServiceImpl]（实现 [SeqOperations]），
 * 编译器自动生成转发，无手工转发样板。Handler 经 `get<SeqService>()` 获取，零改动。
 */
class SeqService(impl: SeqServiceImpl) : SeqOperations by impl
