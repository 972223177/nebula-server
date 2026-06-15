package com.nebula.service.user

import com.nebula.chat.user.BatchGetUserResp
import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.GetProfileResp
import com.nebula.chat.user.LoginReq
import com.nebula.chat.user.LoginResp
import com.nebula.chat.user.RegisterReq
import com.nebula.chat.user.RegisterResp
import com.nebula.chat.user.SearchUserResp
import com.nebula.chat.user.UserBrief
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.repository.UserRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * 用户业务服务（D-01 ~ D-05, D-07 ~ D-08）。
 *
 * 提供用户注册、登录、搜索、资料查询、批量查询等核心业务逻辑。
 * 不依赖网关层组件（SessionRegistry、PushService 等），由调用方（Handler）负责 Session 管理和推送。
 */
class UserService(
    private val userRepository: UserRepository,
    private val idGenerator: SnowflakeIdGenerator,
    private val onlineStatusRepository: OnlineStatusRepository
) {

    companion object {
        /** 日志记录器 */
        private val logger = KotlinLogging.logger {}
        /** BCrypt cost 因子（D-03） */
        private const val BCRYPT_COST = 12
        /** 密码最小长度 */
        private const val MIN_PASSWORD_LENGTH = 6
        /** 单页最大返回条数（D-08） */
        private const val MAX_SEARCH_LIMIT = 20
    }

    /**
     * 用户注册（D-01, D-02, AUTH-01）。
     *
     * 校验用户名唯一性 → 校验密码强度 → BCrypt 哈希密码 → 生成 Snowflake ID → 持久化。
     * 使用独立 EntityManager + 显式事务确保写入成功（D-09），由调用方传入 emf 参数。
     *
     * @param req 注册请求（含 username, password, nickname, avatar）
     * @return 注册响应（含 uid）
     * @throws UserException 用户名已存在、密码过短、参数无效时
     */
    suspend fun register(req: RegisterReq): Long {
        val password = req.password
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw UserException(BizCode.INVALID_PARAM, "密码长度不能少于 $MIN_PASSWORD_LENGTH 位")
        }

        val username = req.username.trim()
        if (username.isBlank()) {
            throw UserException(BizCode.INVALID_PARAM, "用户名不能为空")
        }

        // 校验用户名唯一性
        if (withContext(Dispatchers.IO) { userRepository.findByUsername(username) } != null) {
            throw UserException(BizCode.USERNAME_EXISTS)
        }

        // BCrypt 哈希密码
        val encoder = BCryptPasswordEncoder(BCRYPT_COST)
        val passwordHash = encoder.encode(password)

        val user = UserEntity(
            username = username,
            passwordHash = passwordHash,
            nickname = req.nickname.ifBlank { username },
            avatar = req.avatar.ifBlank { "" }
        )
        user.id = idGenerator.nextId()
        val now = LocalDateTime.now()
        user.createdAt = now
        user.updatedAt = now

        withContext(Dispatchers.IO) { userRepository.save(user) }

        return requireNotNull(user.id) { "用户ID不能为null" }
    }

    /**
     * 用户登录（D-04, D-05, AUTH-01）。
     *
     * 两种登录场景：
     * 1. Token 重连（req.hasToken()）：验证 Token 有效性（需 SessionRegistry 配合，由调用方处理）
     * 2. 密码登录：验证用户名密码
     *
     * 注意：Token 重连场景的 SessionRegistry 验证由 Handler 层完成，
     * 此方法仅处理密码登录场景。
     *
     * @param req 登录请求（含 username + password）
     * @return 用户 ID（LoginResp 的构建由 Handler 层完成，因需要 SessionRegistry 生成 Token）
     * @throws UserException 用户不存在、密码错误时
     */
    suspend fun loginByPassword(req: LoginReq): Long {
        val username = req.username ?: throw UserException(BizCode.INVALID_PARAM, "用户名不能为空")
        val password = req.password ?: throw UserException(BizCode.INVALID_PARAM, "密码不能为空")

        val user = withContext(Dispatchers.IO) { userRepository.findByUsername(username) }
            ?: throw UserException(BizCode.USER_NOT_FOUND)

        if (!verifyPassword(password, user.passwordHash)) {
            throw UserException(BizCode.AUTH_FAILED)
        }

        return requireNotNull(user.id) { "用户ID不能为null" }
    }

    /**
     * 按用户名搜索用户（D-07, D-08）。
     *
     * 游标分页，按 createdAt 倒序。
     *
     * @param keyword 搜索关键词
     * @param cursor 游标（毫秒时间戳），0 表示首次查询
     * @param limit 每页条数（最大 MAX_SEARCH_LIMIT）
     * @return 搜索响应（含用户列表、游标、hasMore）
     */
    suspend fun searchUsers(keyword: String, cursor: Long, limit: Int): SearchUserResp {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) {
            return SearchUserResp.getDefaultInstance()
        }

        val actualLimit = if (limit in 1..MAX_SEARCH_LIMIT) limit else MAX_SEARCH_LIMIT
        val cursorDateTime = if (cursor == 0L) null
            else LocalDateTime.ofInstant(Instant.ofEpochMilli(cursor), ZoneOffset.UTC)

        val users = withContext(Dispatchers.IO) {
            userRepository.findByUsernameContaining(
                keyword = trimmed,
                cursor = cursorDateTime,
                limit = actualLimit + 1
            )
        }

        val hasMore = users.size > actualLimit
        val result = if (hasMore) users.dropLast(1) else users

        val builder = SearchUserResp.newBuilder()
        result.forEach { entity ->
            builder.addUsers(UserBrief.newBuilder()
                .setUid(requireNotNull(entity.id) { "用户ID不能为null" })
                .setUsername(entity.username)
                .setDisplayName(entity.nickname)
                .setAvatarUrl(entity.avatar)
                .setCreatedAt(entity.createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
                .build())
        }
        builder.setNextCursor(
            result.lastOrNull()?.createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0
        )
        builder.setHasMore(hasMore)
        return builder.build()
    }

    /**
     * 获取用户详细资料。
     *
     * @param uid 目标用户 ID
     * @return 用户资料响应
     * @throws UserException 用户不存在
     */
    suspend fun getProfile(uid: Long): GetProfileResp {
        val user = withContext(Dispatchers.IO) { userRepository.findById(uid).orElse(null) }
            ?: throw UserException(BizCode.USER_NOT_FOUND)

        return GetProfileResp.newBuilder()
            .setUid(requireNotNull(user.id) { "用户ID不能为null" })
            .setUsername(user.username)
            .setDisplayName(user.nickname)
            .setAvatarUrl(user.avatar)
            .setCreatedAt(user.createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
            .build()
    }

    /**
     * 批量查询用户信息。
     *
     * @param req 批量请求（含 uid 列表）
     * @return 批量用户信息响应
     */
    suspend fun batchGetUsers(req: BatchIdRequest): BatchGetUserResp {
        val uidList = req.uidsList
        if (uidList.isEmpty()) {
            return BatchGetUserResp.getDefaultInstance()
        }

        val users = withContext(Dispatchers.IO) {
            userRepository.findAllById(uidList.map { it })
        }.associateBy { it.id }

        val builder = BatchGetUserResp.newBuilder()
        uidList.forEach { uid ->
            val user = users[uid]
            if (user != null) {
                builder.addUsers(UserBrief.newBuilder()
                    .setUid(requireNotNull(user.id) { "用户ID不能为null" })
                    .setUsername(user.username)
                    .setDisplayName(user.nickname)
                    .setAvatarUrl(user.avatar)
                    .setCreatedAt(user.createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
                    .build())
            }
        }
        return builder.build()
    }

    /**
     * BCrypt 密码验证（D-03）。
     *
     * MockK 原生支持 mock final 方法，无需 open。
     *
     * @param rawPassword 明文密码
     * @param storedHash 数据库中存储的 BCrypt 哈希
     * @return 是否匹配
     */
    fun verifyPassword(rawPassword: String, storedHash: String): Boolean {
        val encoder = BCryptPasswordEncoder(BCRYPT_COST)
        return encoder.matches(rawPassword, storedHash)
    }
}
