package com.nebula.service.admin

import com.nebula.common.init.DeadLetterCallback

/**
 * 死信聚合服务（Facade，2026-07-29 字面 Facade 改造，仿 `conversation/ConversationService`）。
 *
 * 经 Kotlin 类委托（`by`）聚合到 [DeadLetterServiceImpl]：
 * - [DeadLetterOperations]：业务契约（Handler / Invoker 依赖）
 * - [DeadLetterCallback]：跨层桥接回调（ServerBootstrap 经 `get<DeadLetterCallback>()` 注入）
 *
 * 编译器自动生成两接口的转发，无手工转发样板。Handler 经 `get<DeadLetterService>()` 获取，零改动；
 * 桥接回调经 `get<DeadLetterCallback>()` 解析到本 Facade，同样零改动。
 */
class DeadLetterService(impl: DeadLetterServiceImpl) :
    DeadLetterOperations by impl,
    DeadLetterCallback by impl
