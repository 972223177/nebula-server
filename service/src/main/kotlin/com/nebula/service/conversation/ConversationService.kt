package com.nebula.service.conversation

/**
 * 会话业务聚合服务（D-02, D-05, D-10, D-19）。
 *
 * 采用 **接口隔离 + Kotlin 类委托（`by`）** 实现 GoF Facade：
 * 本类通过 `by` 委托给 [GroupOperations] 与 [ConversationQueryOperations] 两个子域接口
 * （分别由 [GroupService] 与 [ConversationQueryService] 实现），编译器自动生成转发，
 * 故无手工转发样板，保留原单体 `ConversationService` 的全部公开方法签名。
 *
 * Handler 层经 `get<ConversationService>()` 获取本实例，零改动。
 * 跨子域协调（若有）应在 Handler 层完成，禁止本类内让一个子服务调用另一个子服务的写方法，
 * 以保留零循环依赖。
 */
class ConversationService(
    groupService: GroupService,
    conversationQueryService: ConversationQueryService
) : GroupOperations by groupService,
    ConversationQueryOperations by conversationQueryService
