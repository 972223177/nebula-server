package com.nebula.gateway.handler.user

import com.nebula.chat.Response
import com.nebula.chat.user.LogoutReq
import com.nebula.common.BizCode
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.session.EvictionReason
import com.nebula.gateway.session.SessionRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.currentCoroutineContext

/**
 * 退出登录 Handler — method = "user/logout"（AUTH-06）。
 *
 * 职责：
 * - 从协程上下文取出当前 Session，调用 [SessionRegistry.unregister] 主动下线
 *   （L1 本地缓存 + L2 Redis + 设备类型映射全清，token 彻底失效）
 * - unregister 触发 eviction 回调并以 [EvictionReason.LOGOUT] 关闭本连接 gRPC 流；
 *   主动登出不推送 DISCONNECT（被驱逐的就是发起登出的连接自身，客户端已主动退出），
 *   避免客户端把登出误判为「被其他设备踢下线」
 *
 * 设计决策（AUTH-06）：
 * - 复用系统已有的「同设备互踢」eviction 机制来关闭连接，不新增连接管理代码
 * - 不在 Handler 内直接改 observer 字段或调用 onCompleted，避免与 cleanupConnection 重复清理产生竞态
 * - 鉴权由 AuthInterceptor 保证（user/logout 不在白名单），因此能进到本 Handler 的一定是已登录 Session
 *
 * 此接口响应不包含业务数据（无 result 字段），仅通过 Response.code 和 Response.msg 表示操作结果。
 */
class LogoutHandler(
    private val sessionRegistry: SessionRegistry
) : Handler<LogoutReq, Response> {

    override val method: String = "user/logout"

    override suspend fun handle(req: LogoutReq): Response {
        val session = currentCoroutineContext().requireSession()
        val userId = session.userId
        val token = session.token

        // AUTH-06: 主动注销当前 Session，token 立即失效；以 LOGOUT 原因触发 eviction 仅关闭本连接，不推送 DISCONNECT
        sessionRegistry.unregister(token, EvictionReason.LOGOUT)

        logger.info { "用户退出登录: userId=$userId, token=${token.take(8)}..." }
        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
