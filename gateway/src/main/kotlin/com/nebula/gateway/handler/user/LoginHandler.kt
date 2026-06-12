package com.nebula.gateway.handler.user

import com.nebula.chat.user.LoginReq
import com.nebula.chat.user.LoginResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.session.SessionRegistry
import com.nebula.repository.repository.UserRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.util.UUID

/**
 * 用户登录 Handler — method = "user/login"（D-04, D-05, AUTH-01）。
 *
 * 职责：
 * - 场景 1: Token 重连（AUTH-02）— 通过 SessionRegistry.validate() 验证 Token
 *   验证通过后**复用现有 Token**，不生成新 Token（Review 修复#2：避免 Session 孤儿化）
 * - 场景 2: 用户名+密码登录 — 通过 BCrypt 验证密码（cost 12, D-03），生成新 Token
 * - 登录成功后返回 LoginResp（含 token、uid、server_now、device_type、device_id）
 *
 * 设备信息传递（Review 修复#3）：
 * DeviceType/DeviceId 从 LoginReq 字段直接复制到 LoginResp。ChatService 从 LoginResp 读取，
 * 无需重新解析 Request.params，避免重复反序列化。
 *
 * 设计决策引用：
 * - D-04: LoginResp 的 deviceType/deviceId 从 LoginReq 复制
 * - D-05: Token 重连时复用现有 Token（Review 修复）
 * - D-03: BCrypt cost 12 密码哈希
 *
 * @param userRepository 用户数据仓库
 * @param sessionRegistry Session 注册中心（用于 Token 验证）
 */
open class LoginHandler(
    private val userRepository: UserRepository,
    private val sessionRegistry: SessionRegistry
) : Handler<LoginReq, LoginResp> {

    override val method: String = "user/login"

    /**
     * 处理登录请求。
     *
     * 两种登录场景：
     * 1. Token 重连（req.hasToken() 为 true）：验证 Token 有效性，有效则复用现有 Token
     * 2. 密码登录：验证用户名密码，通过后生成新 Token
     *
     * @param req 登录请求（含 username/password 或 token）
     * @return 登录响应（含 token、uid、device 信息）
     * @throws UserException 当用户不存在、密码错误或参数无效时
     */
    override suspend fun handle(req: LoginReq): LoginResp {
        // 场景 1: Token 重连（AUTH-02）
        if (req.hasToken()) {
            val token = req.token
            val existingSession = sessionRegistry.validate(token)
            if (existingSession != null) {
                // Review 修复#2：复用现有 Token，不生成新 Token
                // 避免 Session 孤儿化——旧 Session 在 Redis 中存活，新 Token 被生成，旧 Token 泄露
                val user = userRepository.findById(existingSession.userId)
                    ?: throw UserException(BizCode.USER_NOT_FOUND)
                // Token 有效，复用现有 Token
                return buildLoginResp(existingSession.userId, existingSession.token, req)
            }
        }

        // 场景 2: 用户名+密码登录
        val username = req.username ?: throw UserException(BizCode.INVALID_PARAM, "用户名不能为空")
        val password = req.password ?: throw UserException(BizCode.INVALID_PARAM, "密码不能为空")
        val user = userRepository.findByUsername(username)
            ?: throw UserException(BizCode.USER_NOT_FOUND)

        // BCrypt 密码验证（D-03, cost 12）
        if (!verifyPassword(password, user.passwordHash)) {
            throw UserException(BizCode.AUTH_FAILED)
        }

        val token = UUID.randomUUID().toString()
        return buildLoginResp(user.id!!, token, req)
    }

    /**
     * BCrypt 密码验证（D-03）。
     *
     * 提取为 open 方法方便单元测试覆写（BCryptPasswordEncoder 为 final class，不可 mock）。
     *
     * @param rawPassword 明文密码
     * @param storedHash 数据库中存储的 BCrypt 哈希
     * @return 是否匹配
     */
    open fun verifyPassword(rawPassword: String, storedHash: String): Boolean {
        val encoder = BCryptPasswordEncoder(12)
        return encoder.matches(rawPassword, storedHash)
    }

    /**
     * 构建登录响应。
     *
     * 包含 token、uid、server_now、device_type、device_id 等信息。
     * DeviceType/DeviceId 从 LoginReq 直接复制到 LoginResp（Review 修复#3），
     * ChatService 从 LoginResp 中读取，无需重新解析 Request.params。
     *
     * @param userId 用户 ID
     * @param token 登录 Token（重连时复用现有 Token，首次登录时为新生成）
     * @param req 原始登录请求（用于复制 device 信息）
     * @return 构建好的 LoginResp
     */
    private fun buildLoginResp(userId: Long, token: String, req: LoginReq): LoginResp {
        return LoginResp.newBuilder()
            .setUserId(userId)
            .setUid(userId)
            .setToken(token)
            .setServerNow(System.currentTimeMillis())
            .setDeviceType(req.deviceType)
            .setDeviceId(req.deviceId)
            .build()
    }
}
