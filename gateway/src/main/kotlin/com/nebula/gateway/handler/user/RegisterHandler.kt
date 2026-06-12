package com.nebula.gateway.handler.user

import com.nebula.chat.user.RegisterReq
import com.nebula.chat.user.RegisterResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.gateway.handler.Handler
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.UserRepository
import jakarta.persistence.EntityManagerFactory
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import java.time.LocalDateTime

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
 * 事务说明（D-09）：
 * JpaConfig.getRepository() 创建的 Repository 未配置 Spring PlatformTransactionManager，
 * @Transactional 注解不生效。本 Handler 通过 EntityManagerFactory 创建独立 EntityManager
 * 并手动管理事务，确保 save() 提交到数据库。其他 Handler（如 LoginHandler）通过
 * JPA Repository 查询时，由于使用独立 EntityManager，无法看到本 Handler 写入的数据。
 * 该问题将在 Phase 6 修复（TODO: 全局事务管理）。
 *
 * @param userRepository 用户数据仓库
 * @param idGenerator 雪花算法 ID 生成器
 * @param emf JPA EntityManagerFactory
 */
class RegisterHandler(
    private val userRepository: UserRepository,
    private val idGenerator: SnowflakeIdGenerator,
    private val emf: EntityManagerFactory
) : Handler<RegisterReq, RegisterResp> {

    override val method: String = "user/register"

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

        // 使用独立 EntityManager + 显式事务确保写入成功（D-09）
        val em = emf.createEntityManager()
        try {
            em.transaction.begin()

            val user = UserEntity(
                username = username,
                passwordHash = passwordHash,
                nickname = req.nickname.ifBlank { username },
                avatar = req.avatar ?: ""
            )
            user.id = idGenerator.nextId()
            val now = LocalDateTime.now()
            user.createdAt = now
            user.updatedAt = now

            em.persist(user)
            em.flush()

            em.transaction.commit()

            return RegisterResp.newBuilder()
                .setUid(user.id!!)
                .build()
        } catch (e: Exception) {
            if (em.transaction.isActive) {
                em.transaction.rollback()
            }
            throw e
        } finally {
            em.close()
        }
    }
}
