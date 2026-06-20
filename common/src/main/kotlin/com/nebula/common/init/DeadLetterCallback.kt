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
}
