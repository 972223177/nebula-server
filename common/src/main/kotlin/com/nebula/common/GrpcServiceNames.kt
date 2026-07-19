package com.nebula.common

/**
 * gRPC 服务名常量集中管理 — 所有模块通过此入口获取服务名。
 *
 * 值来自 proto 定义（package + service name），集中定义避免各模块内联硬编码。
 */
object GrpcServiceNames {
    /** 核心聊天双向流服务 */
    const val CHAT_SERVICE = "nebula.chat.ChatService"
}
