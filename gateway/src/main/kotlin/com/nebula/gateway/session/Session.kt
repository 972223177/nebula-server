package com.nebula.gateway.session

/**
 * Session 会话数据模型。
 *
 * 由 AuthInterceptor 在认证通过后创建，通过 CoroutineContext 隐式传递给 Handler。
 * 字段对应设计文档 4.2 Token 方案中的 Session 数据结构（D-16）。
 *
 * @param userId 用户 ID，唯一标识用户身份
 * @param token 会话 Token，随机 UUID 字符串，用于 Session 查找和验证
 * @param deviceType 客户端设备类型，如 "android"、"ios"、"web"
 * @param deviceId 客户端设备唯一标识，用于同端多设备互踢（AUTH-03）
 * @param connectionId gRPC 连接唯一标识，对应 StreamObserver 关联
 */
data class Session(
    val userId: Long,
    val token: String,
    val deviceType: String,
    val deviceId: String,
    val connectionId: String
)
