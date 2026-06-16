package com.nebula.common.init

/**
 * 死信回调接口 — 消息异步刷盘失败时创建死信记录的回调。
 *
 * 放置于 common 模块，供 repository 模块（定义回调点）和
 * service 模块（实现回调逻辑）共享，避免模块间循环依赖（D-28）。
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
    fun onMessageFailed(
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
