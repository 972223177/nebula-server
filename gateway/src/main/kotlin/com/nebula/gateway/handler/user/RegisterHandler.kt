package com.nebula.gateway.handler.user

import com.nebula.chat.user.RegisterReq
import com.nebula.chat.user.RegisterResp
import com.nebula.common.BizCode
import com.nebula.common.exception.BizException
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.handler.Handler
import com.nebula.service.user.UserService
import java.util.UUID

/**
 * 用户注册 Handler — method = "user/register"（D-01, D-02, AUTH-01, CQ-13）。
 *
 * 职责：
 * - 委托 UserService 处理注册逻辑（参数校验、密码哈希、ID 生成、持久化）
 * - 生成 Session Token（CQ-13：注册即登录，无需客户端二次调用 login）
 * - 返回 RegisterResp（含 uid 和 token）
 *
 * 注意：Session 的注册和 StreamObserver 绑定由 ChatService.handleRegisterSuccess 完成，
 * 本 Handler 仅生成 token 并返回，不直接操作 SessionRegistry。
 *
 * @param userService 用户业务服务
 */
class RegisterHandler(
    private val sensitiveWordService: SensitiveWordService,
    private val userService: UserService
) : Handler<RegisterReq, RegisterResp> {

    override val method: String = "user/register"

    override suspend fun handle(req: RegisterReq): RegisterResp {
        // 敏感词检测：昵称含敏感词则拒绝注册，直接报接口错误（CONTENT_VIOLATION）。
        // 昵称是公开展示文本，采用拒绝策略（与 chat/send 的脱敏策略不同）。
        // 注册即登录，此处在生成 token 之前检测，避免脏昵称入库。
        if (req.nickname.isNotBlank() && sensitiveWordService.contains(req.nickname)) {
            throw BizException(BizCode.CONTENT_VIOLATION, "昵称包含敏感内容")
        }

        val uid = userService.register(req)
        // CQ-13: 注册即登录 — 生成 Session Token，由 ChatService 完成 Session 绑定
        val token = UUID.randomUUID().toString()
        return RegisterResp.newBuilder()
            .setUid(uid)
            .setToken(token)
            .build()
    }
}
