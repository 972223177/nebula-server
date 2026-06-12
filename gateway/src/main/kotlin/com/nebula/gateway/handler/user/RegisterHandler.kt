package com.nebula.gateway.handler.user

import com.nebula.chat.user.RegisterReq
import com.nebula.chat.user.RegisterResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.gateway.handler.Handler
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.UserRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

/**
 * 用户注册 Handler — method = "user/register"（D-01, D-02, AUTH-01）。
 *
 * 职责：
 * - 验证用户名唯一性（通过 UserRepository.findByUsername）
 * - 验证密码强度（最少 6 位）
 * - 使用 BCrypt cost 12 哈希密码（D-03, T-05-05）
 * - 使用 SnowflakeIdGenerator 生成用户 ID
 * - 返回 RegisterResp（含 uid，不含 token — 注册后用户需通过 /login 获取 Token）
 *
 * 设计决策引用：
 * - D-01: 客户端可通过 user/register 注册新账号
 * - D-02: 注册时用户名唯一性校验、密码最少 6 位
 * - D-03: BCrypt cost 12 密码哈希
 *
 * @param userRepository 用户数据仓库
 * @param idGenerator 雪花算法 ID 生成器
 */
class RegisterHandler(
    private val userRepository: UserRepository,
    private val idGenerator: SnowflakeIdGenerator
) : Handler<RegisterReq, RegisterResp> {

    override val method: String = "user/register"

    /**
     * 处理注册请求。
     *
     * 执行流程：
     * 1. 校验密码长度（>= 6 位）
     * 2. 校验用户名唯一性
     * 3. BCrypt 哈希密码
     * 4. 创建 UserEntity 并保存
     * 5. 返回 RegisterResp（仅 uid，不含 token）
     *
     * @param req 注册请求（用户名、密码、昵称、可选头像）
     * @return 注册响应（含 uid）
     * @throws UserException 当用户名已存在、密码太短或参数无效时
     */
    override suspend fun handle(req: RegisterReq): RegisterResp {
        // 校验密码长度（D-02, T-05-05）
        val password = req.password
        if (password.length < 6) {
            throw UserException(BizCode.INVALID_PARAM, "密码长度不能少于 6 位")
        }

        // 校验用户名合法性
        val username = req.username.trim()
        if (username.isBlank()) {
            throw UserException(BizCode.INVALID_PARAM, "用户名不能为空")
        }

        // 校验用户名唯一性（T-05-05）
        if (userRepository.findByUsername(username) != null) {
            throw UserException(BizCode.USERNAME_EXISTS)
        }

        // BCrypt 哈希密码（D-03, cost 12）
        val encoder = BCryptPasswordEncoder(12)
        val passwordHash = encoder.encode(password)

        // 创建用户实体
        val user = UserEntity(
            username = username,
            passwordHash = passwordHash,
            nickname = req.nickname.ifBlank { username },
            avatar = req.avatar ?: ""
        )
        user.id = idGenerator.nextId()

        // 保存到数据库
        val saved = userRepository.save(user)

        // 返回注册结果（不含 token — 注册后需通过 /login 获取 Token）
        return RegisterResp.newBuilder()
            .setUid(saved.id!!)
            .build()
    }
}
