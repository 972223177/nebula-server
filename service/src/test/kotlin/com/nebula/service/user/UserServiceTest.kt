package com.nebula.service.user

import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.LoginReq
import com.nebula.chat.user.RegisterReq
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.repository.UserRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import java.util.Optional

/**
 * UserService 单元测试。
 *
 * 使用 MockK 模拟所有依赖（UserRepository、SnowflakeIdGenerator、OnlineStatusRepository），
 * 覆盖注册、登录、搜索、资料查询、批量查询的全部关键路径与异常场景。
 */
class UserServiceTest {

    private lateinit var userRepository: UserRepository
    private lateinit var idGenerator: SnowflakeIdGenerator
    private lateinit var onlineStatusRepository: OnlineStatusRepository
    private lateinit var userService: UserService

    /** 测试用固定用户 ID */
    private val mockUserId = 10001L

    /** 测试用用户名 */
    private val testUsername = "testuser"

    /** 测试用密码（符合最小长度要求） */
    private val validPassword = "password123"

    @BeforeEach
    fun setup() {
        userRepository = mockk()
        idGenerator = mockk()
        onlineStatusRepository = mockk()
        userService = spyk(UserService(userRepository, idGenerator, onlineStatusRepository))

        // 默认模拟 idGenerator.nextId() 返回固定值
        coEvery { idGenerator.nextId() } returns mockUserId
    }

    // ═══════════════════════════════════════
    // register — 用户注册
    // ═══════════════════════════════════════

    /**
     * 注册：密码长度不足 6 位时抛出 INVALID_PARAM。
     */
    @Test
    fun registerShouldThrowInvalidParamWhenPasswordLengthLessThan6() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword("12345")
            .build()

        val ex = assertThrows(UserException::class.java) {
            runBlocking { userService.register(req) }
        }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    /**
     * 注册：用户名为空（纯空格）时抛出 INVALID_PARAM。
     */
    @Test
    fun registerShouldThrowInvalidParamWhenUsernameIsBlank() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername("   ")
            .setPassword(validPassword)
            .build()

        val ex = assertThrows(UserException::class.java) {
            runBlocking { userService.register(req) }
        }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    /**
     * 注册：用户名已存在时抛出 USERNAME_EXISTS。
     */
    @Test
    fun registerShouldThrowUsernameExistsWhenUsernameAlreadyTaken() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .build()
        coEvery { userRepository.findByUsername(testUsername) } returns mockk()

        val ex = assertThrows(UserException::class.java) {
            runBlocking { userService.register(req) }
        }
        assertEquals(BizCode.USERNAME_EXISTS, ex.bizCode)
    }

    /**
     * 注册：正常流程创建用户，生成 Snowflake ID、BCrypt 加密密码、持久化并返回 ID。
     */
    @Test
    fun registerShouldCreateUserWithBCryptHashAndSnowflakeId() = runTest {
        val nickname = "测试昵称"
        val avatar = "https://example.com/avatar.png"
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .setNickname(nickname)
            .setAvatar(avatar)
            .build()
        coEvery { userRepository.findByUsername(testUsername) } returns null
        coEvery { userRepository.save(any()) } returns mockk()

        val result = userService.register(req)

        assertEquals(mockUserId, result)

        // 验证 Snowflake ID 被生成
        coVerify { idGenerator.nextId() }

        // 验证 userRepository.save() 被调用，且 Entity 字段正确
        coVerify {
            userRepository.save(match {
                it.username == testUsername &&
                    it.nickname == nickname &&
                    it.avatar == avatar &&
                    it.passwordHash.startsWith("\$2a\$") && // BCrypt 哈希前缀
                    it.id == mockUserId &&
                    it.createdAt != null &&
                    it.updatedAt != null
            })
        }
    }

    /**
     * 注册：未提供昵称时，自动使用用户名作为昵称。
     */
    @Test
    fun registerShouldUseUsernameAsNicknameWhenNicknameIsBlank() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .setNickname("")
            .build()
        coEvery { userRepository.findByUsername(testUsername) } returns null
        coEvery { userRepository.save(any()) } returns mockk()

        val result = userService.register(req)

        assertEquals(mockUserId, result)
        coVerify {
            userRepository.save(match { it.nickname == testUsername })
        }
    }

    /**
     * 注册：未提供头像时，默认使用空字符串。
     */
    @Test
    fun registerShouldSetEmptyAvatarWhenAvatarIsBlank() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .setAvatar("")
            .build()
        coEvery { userRepository.findByUsername(testUsername) } returns null
        coEvery { userRepository.save(any()) } returns mockk()

        userService.register(req)

        coVerify {
            userRepository.save(match { it.avatar == "" })
        }
    }

    // ═══════════════════════════════════════
    // loginByPassword — 密码登录
    // ═══════════════════════════════════════

    /**
     * 登录：用户名为空字符串时查询不到用户，抛出 USER_NOT_FOUND。
     * Protobuf 未设置字符串字段默认返回空字符串而非 null。
     */
    @Test
    fun loginByPasswordShouldThrowUserNotFoundWhenUsernameIsEmpty() = runTest {
        val req = LoginReq.newBuilder()
            .setPassword(validPassword)
            .build() // username 为空字符串

        coEvery { userRepository.findByUsername(any()) } returns null

        val ex = assertThrows(UserException::class.java) {
            runBlocking { userService.loginByPassword(req) }
        }
        assertEquals(BizCode.USER_NOT_FOUND, ex.bizCode)
    }

    /**
     * 登录：密码为空字符串时查询到用户但密码不匹配，抛出 AUTH_FAILED。
     * Protobuf 未设置字符串字段默认返回空字符串而非 null。
     */
    @Test
    fun loginByPasswordShouldThrowAuthFailedWhenPasswordIsEmpty() = runTest {
        val req = LoginReq.newBuilder()
            .setUsername(testUsername)
            .build() // password 为空字符串

        val user = UserEntity(username = testUsername, passwordHash = "hash", nickname = testUsername).apply { id = 1L }
        coEvery { userRepository.findByUsername(testUsername) } returns user

        val ex = assertThrows(UserException::class.java) {
            runBlocking { userService.loginByPassword(req) }
        }
        assertEquals(BizCode.AUTH_FAILED, ex.bizCode)
    }

    /**
     * 登录：用户不存在时抛出 USER_NOT_FOUND。
     */
    @Test
    fun loginByPasswordShouldThrowUserNotFoundWhenUserDoesNotExist() = runTest {
        val req = LoginReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .build()
        coEvery { userRepository.findByUsername(testUsername) } returns null

        val ex = assertThrows(UserException::class.java) {
            runBlocking { userService.loginByPassword(req) }
        }
        assertEquals(BizCode.USER_NOT_FOUND, ex.bizCode)
    }

    /**
     * 登录：密码不匹配时抛出 AUTH_FAILED。
     */
    @Test
    fun loginByPasswordShouldThrowAuthFailedWhenPasswordDoesNotMatch() = runTest {
        val req = LoginReq.newBuilder()
            .setUsername(testUsername)
            .setPassword("wrongpassword")
            .build()
        val userEntity = UserEntity(
            username = testUsername,
            passwordHash = "storedHash",
            nickname = testUsername
        ).apply { id = mockUserId }
        coEvery { userRepository.findByUsername(testUsername) } returns userEntity
        // 默认 verifyPassword 返回 false，因此无需额外设置

        val ex = assertThrows(UserException::class.java) {
            runBlocking { userService.loginByPassword(req) }
        }
        assertEquals(BizCode.AUTH_FAILED, ex.bizCode)
    }

    /**
     * 登录：正常流程返回用户 ID。
     */
    @Test
    fun loginByPasswordShouldReturnUserIdOnSuccess() = runTest {
        val req = LoginReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .build()
        val userEntity = UserEntity(
            username = testUsername,
            passwordHash = "storedHash",
            nickname = testUsername
        ).apply { id = mockUserId }
        coEvery { userRepository.findByUsername(testUsername) } returns userEntity
        // 覆写 verifyPassword 使其返回 true，跳过 BCrypt 校验
        every { userService.verifyPassword(validPassword, "storedHash") } returns true

        val result = userService.loginByPassword(req)

        assertEquals(mockUserId, result)
        coVerify { userRepository.findByUsername(testUsername) }
    }

    // ═══════════════════════════════════════
    // searchUsers — 搜索用户
    // ═══════════════════════════════════════

    /**
     * 搜索：关键词为空时返回空响应。
     */
    @Test
    fun searchUsersShouldReturnEmptyResponseWhenKeywordIsBlank() = runTest {
        val resp = userService.searchUsers("   ", 0L, 10)

        assertEquals(0, resp.usersCount)
        assertEquals(0L, resp.nextCursor)
        assertFalse(resp.hasMore)
    }

    /**
     * 搜索：正常分页查询，返回用户列表及 hasMore 标记。
     */
    @Test
    fun searchUsersShouldReturnPaginatedResultsWithHasMore() = runTest {
        val keyword = "test"
        val limit = 2
        val now = LocalDateTime.now()

        val users = listOf(
            createUserEntity(10001L, "testuser1", now.minusMinutes(1)),
            createUserEntity(10002L, "testuser2", now.minusMinutes(2)),
            createUserEntity(10003L, "testuser3", now.minusMinutes(3)) // 多取一条用于判断 hasMore
        )
        coEvery {
            userRepository.findByUsernameContaining(keyword, null, limit + 1)
        } returns users

        val resp = userService.searchUsers(keyword, 0L, limit)

        assertEquals(limit, resp.usersCount)
        assertEquals(10001L, resp.getUsers(0).uid)
        assertEquals(10002L, resp.getUsers(1).uid)
        assertTrue(resp.hasMore)
        assertEquals(
            users[limit - 1].createdAt!!.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            resp.nextCursor
        )
    }

    /**
     * 搜索：返回结果未超限时 hasMore 为 false。
     */
    @Test
    fun searchUsersShouldSetHasMoreToFalseWhenResultsWithinLimit() = runTest {
        val keyword = "test"
        val limit = 10
        val now = LocalDateTime.now()

        val users = listOf(
            createUserEntity(10001L, "testuser1", now.minusMinutes(1)),
            createUserEntity(10002L, "testuser2", now.minusMinutes(2))
        )
        coEvery {
            userRepository.findByUsernameContaining(keyword, null, limit + 1)
        } returns users

        val resp = userService.searchUsers(keyword, 0L, limit)

        assertEquals(2, resp.usersCount)
        assertFalse(resp.hasMore)
    }

    /**
     * 搜索：limit 超限时自动降级为 MAX_SEARCH_LIMIT。
     */
    @Test
    fun searchUsersShouldCapLimitToMaxSearchLimitWhenExceeded() = runTest {
        val keyword = "test"
        val now = LocalDateTime.now()
        // MAX_SEARCH_LIMIT = 20, 传入 100 应降级为 20，所以多取一条判断 hasMore
        val users = (1..21).map { i ->
            createUserEntity(10000L + i, "testuser$i", now.minusMinutes(i.toLong()))
        }
        coEvery {
            userRepository.findByUsernameContaining(keyword, null, 21) // limit+1 = 20+1
        } returns users

        val resp = userService.searchUsers(keyword, 0L, 100)

        assertEquals(20, resp.usersCount)
        assertTrue(resp.hasMore)
    }

    // ═══════════════════════════════════════
    // getProfile — 获取用户资料
    // ═══════════════════════════════════════

    /**
     * 获取资料：用户不存在时抛出 USER_NOT_FOUND。
     */
    @Test
    fun getProfileShouldThrowUserNotFoundWhenUserDoesNotExist() = runTest {
        coEvery { userRepository.findById(mockUserId) } returns Optional.empty()

        val ex = assertThrows(UserException::class.java) {
            runBlocking { userService.getProfile(mockUserId) }
        }
        assertEquals(BizCode.USER_NOT_FOUND, ex.bizCode)
    }

    /**
     * 获取资料：正常返回用户资料，包含所有字段。
     */
    @Test
    fun getProfileShouldReturnProfileWithAllFields() = runTest {
        val now = LocalDateTime.now()
        val userEntity = UserEntity(
            username = testUsername,
            passwordHash = "hash",
            nickname = "显示名称",
            avatar = "https://example.com/avatar.png"
        ).apply {
            id = mockUserId
            createdAt = now
            updatedAt = now
        }
        coEvery { userRepository.findById(mockUserId) } returns Optional.of(userEntity)

        val resp = userService.getProfile(mockUserId)

        assertEquals(mockUserId, resp.uid)
        assertEquals(testUsername, resp.username)
        assertEquals("显示名称", resp.displayName)
        assertEquals("https://example.com/avatar.png", resp.avatarUrl)
        assertEquals(
            now.atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            resp.createdAt
        )
    }

    // ═══════════════════════════════════════
    // batchGetUsers — 批量查询用户
    // ═══════════════════════════════════════

    /**
     * 批量查询：空 uid 列表时返回空响应。
     */
    @Test
    fun batchGetUsersShouldReturnEmptyResponseForEmptyUidList() = runTest {
        val req = BatchIdRequest.getDefaultInstance()

        val resp = userService.batchGetUsers(req)

        assertEquals(0, resp.usersCount)
    }

    /**
     * 批量查询：仅返回存在的用户（部分结果）。
     */
    @Test
    fun batchGetUsersShouldReturnOnlyFoundUsersForPartialResults() = runTest {
        val uid1 = 10001L
        val uid2 = 10002L // 不存在的用户
        val uid3 = 10003L

        val req = BatchIdRequest.newBuilder()
            .addAllUids(listOf(uid1, uid2, uid3))
            .build()

        val user1 = UserEntity(
            username = "user1",
            passwordHash = "hash1",
            nickname = "用户1"
        ).apply { id = uid1 }

        val user3 = UserEntity(
            username = "user3",
            passwordHash = "hash3",
            nickname = "用户3"
        ).apply { id = uid3 }

        coEvery { userRepository.findAllById(listOf(uid1, uid2, uid3)) } returns listOf(user1, user3)

        val resp = userService.batchGetUsers(req)

        assertEquals(2, resp.usersCount)
        assertEquals(uid1, resp.getUsers(0).uid)
        assertEquals(uid3, resp.getUsers(1).uid)
    }

    /**
     * 批量查询：所有 uid 都存在时返回完整结果。
     */
    @Test
    fun batchGetUsersShouldReturnAllUsersWhenAllExist() = runTest {
        val uid1 = 10001L
        val uid2 = 10002L
        val uid3 = 10003L

        val req = BatchIdRequest.newBuilder()
            .addAllUids(listOf(uid1, uid2, uid3))
            .build()

        val users = listOf(
            UserEntity(username = "user1", passwordHash = "h1", nickname = "用户1").apply { id = uid1 },
            UserEntity(username = "user2", passwordHash = "h2", nickname = "用户2").apply { id = uid2 },
            UserEntity(username = "user3", passwordHash = "h3", nickname = "用户3").apply { id = uid3 }
        )

        coEvery { userRepository.findAllById(listOf(uid1, uid2, uid3)) } returns users

        val resp = userService.batchGetUsers(req)

        assertEquals(3, resp.usersCount)
        assertEquals(uid1, resp.getUsers(0).uid)
        assertEquals(uid2, resp.getUsers(1).uid)
        assertEquals(uid3, resp.getUsers(2).uid)
    }

    // ─── 辅助方法 ───

    /** 创建 UserEntity 测试实例 */
    private fun createUserEntity(id: Long, username: String, createdAt: LocalDateTime): UserEntity {
        return UserEntity(
            username = username,
            passwordHash = "hash_$id",
            nickname = "昵称_$id",
            avatar = ""
        ).apply {
            this.id = id
            this.createdAt = createdAt
            this.updatedAt = createdAt
        }
    }
}
