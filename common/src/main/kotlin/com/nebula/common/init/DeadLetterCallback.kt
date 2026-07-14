package com.nebula.common.init

/**
 * 死信桥接回调接口（D-28 跨模块桥接）。
 *
 * 当消息持久化失败时由 repository 模块调用，由 service 层实现并注册到 Koin 容器。
 * 函数声明为 suspend，调用方需在协程上下文中执行。
 */
interface DeadLetterCallback {
    /**
     * 消息持久化失败时调用。
     *
     * @param convId 会话 ID
     * @param senderUid 发送者 UID
     * @param msgType 消息类型
     * @param content 消息内容
     * @param payload 消息载荷
     * @param clientMsgId 客户端消息 ID
     * @param clientTs 客户端时间戳
     * @param reason 失败原因
     */
    suspend fun onMessageFailed(
        convId: String,
        senderUid: Long,
        msgType: Int,
        content: String,
        payload: ByteArray?,
        clientMsgId: String?,
        clientTs: Long,
        reason: String
    )

    /**
     * 无法解析的毒消息回调（D-03 修复：原先静默 XACK 丢弃，无痕丢失）。
     *
     * 当 Redis Stream 条目缺少关键字段（conversationId / senderUid / messageType /
     * content / clientTs / serverTs 之一缺失或格式非法）导致无法反序列化为
     * [com.nebula.repository.entity.MessageEntity] 时调用。
     *
     * 因关键字段缺失，无法可靠还原为完整消息，故不再重试（重试必败），而是将原始
     * body 落地到死信表并标记为 [com.nebula.service.admin.DeadLetterService.STATUS_PERMANENT_FAILED]，
     * 以便人工排查数据损坏来源，同时 XACK 释放 pending 避免 Stream 无限堆积。
     *
     * @param rawBody 原始 Stream 条目 body（Map，字段可能缺失或非法）
     * @param reason 无法解析的原因描述
     */
    suspend fun onUnparseableMessage(
        rawBody: Map<String, String>,
        reason: String
    )
}
