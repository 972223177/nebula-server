package com.nebula.service.user

import com.nebula.chat.user.BatchIdRequest
import com.nebula.chat.user.LoginReq
import com.nebula.chat.user.RegisterReq
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.dao.UserDao
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.redis.OnlineStatusRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDateTime
import kotlin.test.assertFailsWith

/**
 * UserService 单元测试（方案 A 重构版 — 2026-06-20）。
 *
 * 使用 MockK 模拟 DAO + JpaTxRunner：
 * - txRunner.execute 提取传入的 EntityManager 透传给 DAO 调用
 * - DAO 方法接收 EM 参数并返回 mock 设置的值
 *
 * 覆盖注册、登录、搜索、资料查询、批量查询的全部关键路径与异常场景。
 */
class UserServiceTest {

    private lateinit var userDao: UserDao
    private lateinit var txRunner: JpaTxRunner
    private lateinit var idGenerator: SnowflakeIdGenerator
    private lateinit var onlineStatusRepository: OnlineStatusRepository
    private lateinit var userService: UserService
    private lateinit var em: EntityManager

    /** 测试用固定用户 ID */
    private val mockUserId = 10001L

    /** 测试用用户名 */
    private val testUsername = "testuser"

    /** 测试用密码（符合最小长度要求） */
    private val validPassword = "password123"

    @BeforeEach
    fun setup() {
        userDao = mockk()
        txRunner = mockk()
        idGenerator = mockk()
        onlineStatusRepository = mockk()
        em = mockk(relaxed = true)
        userService = spyk(UserService(userDao, txRunner, idGenerator, onlineStatusRepository))

        // 默认 txRunner.execute 直接执行传入的 lambda
        coEvery { txRunner.execute<Any>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[0] as suspend (EntityManager) -> Any).invoke(em)
        }

        // 默认模拟 idGenerator.nextId() 返回固定值
        coEvery { idGenerator.nextId() } returns mockUserId
    }

    // ═══════════════════════════════════════
    // register — 用户注册
    // ═══════════════════════════════════════

    @Test
    fun registerShouldThrowInvalidParamWhenPasswordLengthLessThan6() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword("12345")
            .build()

        val ex = assertFailsWith<UserException> {
            userService.register(req)
        }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun registerShouldThrowInvalidParamWhenUsernameIsBlank() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername("   ")
            .setPassword(validPassword)
            .build()

        val ex = assertFailsWith<UserException> {
            userService.register(req)
        }
        assertEquals(BizCode.INVALID_PARAM, ex.bizCode)
    }

    @Test
    fun registerShouldThrowUsernameExistsWhenUsernameAlreadyTaken() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .build()
        // 事务内校验已存在
        coEvery { userDao.findByUsername(em, testUsername) } returns mockk()

        val ex = assertFailsWith<UserException> {
            userService.register(req)
        }
        assertEquals(BizCode.USERNAME_EXISTS, ex.bizCode)
    }

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
        coEvery { userDao.findByUsername(em, testUsername) } returns null
        // userDao.insert(em, entity) — answers 提取第二个参数（entity）
        coEvery { userDao.insert(em, any()) } answers { args[1] as UserEntity }

        val result = userService.register(req)

        assertEquals(mockUserId, result)
        coVerify { idGenerator.nextId() }
        coVerify {
            userDao.insert(em, match {
                it.username == testUsername &&
                    it.nickname == nickname &&
                    it.avatar == avatar &&
                    it.passwordHash.startsWith("\$2a\$") &&
                    it.id == mockUserId &&
                    it.createdAt != null &&
                    it.updatedAt != null
            })
        }
    }

    @Test
    fun registerShouldUseUsernameAsNicknameWhenNicknameIsBlank() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .setNickname("")
            .build()
        coEvery { userDao.findByUsername(em, testUsername) } returns null
        coEvery { userDao.insert(em, any()) } answers { args[1] as UserEntity }

        userService.register(req)

        coVerify {
            userDao.insert(em, match { it.nickname == testUsername })
        }
    }

    @Test
    fun registerShouldSetEmptyAvatarWhenAvatarIsBlank() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .setAvatar("")
            .build()
        coEvery { userDao.findByUsername(em, testUsername) } returns null
        coEvery { userDao.insert(em, any()) } answers { args[1] as UserEntity }

        userService.register(req)

        coVerify {
            userDao.insert(em, match { it.avatar == "" })
        }
    }

    @Test
    fun registerShouldHandleDuplicateKeyAsUsernameExists() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .build()
        coEvery { userDao.findByUsername(em, testUsername) } returns null
        coEvery { userDao.insert(em, any()) } throws
            PersistenceException("Duplicate entry 'testuser' for key 'uk_username'")

        val ex = assertFailsWith<UserException> {
            userService.register(req)
        }
        assertEquals(BizCode.USERNAME_EXISTS, ex.bizCode)
    }

    // ═══════════════════════════════════════
    // loginByPassword — 密码登录
    // ═══════════════════════════════════════

    @Test
    fun loginByPasswordShouldThrowUserNotFoundWhenUsernameIsEmpty() = runTest {
        val req = LoginReq.newBuilder()
            .setPassword(validPassword)
            .build()
        coEvery { userDao.findByUsername(em, any()) } returns null

        val ex = assertFailsWith<UserException> {
            userService.loginByPassword(req)
        }
        assertEquals(BizCode.USER_NOT_FOUND, ex.bizCode)
    }

    @Test
    fun loginByPasswordShouldThrowAuthFailedWhenPasswordIsEmpty() = runTest {
        val req = LoginReq.newBuilder()
            .setUsername(testUsername)
            .build()
        val user = UserEntity(username = testUsername, passwordHash = "hash", nickname = testUsername).apply { id = 1L }
        coEvery { userDao.findByUsername(em, testUsername) } returns user

        val ex = assertFailsWith<UserException> {
            userService.loginByPassword(req)
        }
        assertEquals(BizCode.AUTH_FAILED, ex.bizCode)
    }

    @Test
    fun loginByPasswordShouldThrowUserNotFoundWhenUserDoesNotExist() = runTest {
        val req = LoginReq.newBuilder()
            .setUsername(testUsername)
            .setPassword(validPassword)
            .build()
        coEvery { userDao.findByUsername(em, testUsername) } returns null

        val ex = assertFailsWith<UserException> {
            userService.loginByPassword(req)
        }
        assertEquals(BizCode.USER_NOT_FOUND, ex.bizCode)
    }

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
        coEvery { userDao.findByUsername(em, testUsername) } returns userEntity

        val ex = assertFailsWith<UserException> {
            userService.loginByPassword(req)
        }
        assertEquals(BizCode.AUTH_FAILED, ex.bizCode)
    }

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
        coEvery { userDao.findByUsername(em, testUsername) } returns userEntity
        every { userService.verifyPassword(validPassword, "storedHash") } returns true

        val result = userService.loginByPassword(req)

        assertEquals(mockUserId, result)
        coVerify { userDao.findByUsername(em, testUsername) }
    }

    // ═══════════════════════════════════════
    // searchUsers — 搜索用户
    // ═══════════════════════════════════════

    @Test
    fun searchUsersShouldReturnEmptyResponseWhenKeywordIsBlank() = runTest {
        val resp = userService.searchUsers("   ", 0L, 10)

        assertEquals(0, resp.usersCount)
        assertEquals(0L, resp.nextCursor)
        assertFalse(resp.hasMore)
    }

    @Test
    fun searchUsersShouldReturnPaginatedResultsWithHasMore() = runTest {
        val keyword = "test"
        val limit = 2
        val now = LocalDateTime.now()

        val users = listOf(
            createUserEntity(10001L, "testuser1", now.minusMinutes(1)),
            createUserEntity(10002L, "testuser2", now.minusMinutes(2)),
            createUserEntity(10003L, "testuser3", now.minusMinutes(3))
        )
        coEvery {
            userDao.findByUsernameContaining(em, keyword, null, limit + 1)
        } returns users

        val resp = userService.searchUsers(keyword, 0L, limit)

        assertEquals(limit, resp.usersCount)
        assertEquals(10001L, resp.getUsers(0).uid)
        assertEquals(10002L, resp.getUsers(1).uid)
        assertTrue(resp.hasMore)
        assertEquals(
            requireNotNull(users[limit - 1].createdAt).atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli(),
            resp.nextCursor
        )
    }

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
            userDao.findByUsernameContaining(em, keyword, null, limit + 1)
        } returns users

        val resp = userService.searchUsers(keyword, 0L, limit)

        assertEquals(2, resp.usersCount)
        assertFalse(resp.hasMore)
    }

    @Test
    fun searchUsersShouldCapLimitToMaxSearchLimitWhenExceeded() = runTest {
        val keyword = "test"
        val now = LocalDateTime.now()
        val users = (1..21).map { i ->
            createUserEntity(10000L + i, "testuser$i", now.minusMinutes(i.toLong()))
        }
        coEvery {
            userDao.findByUsernameContaining(em, keyword, null, 21)
        } returns users

        val resp = userService.searchUsers(keyword, 0L, 100)

        assertEquals(20, resp.usersCount)
        assertTrue(resp.hasMore)
    }

    // ═══════════════════════════════════════
    // getProfile — 获取用户资料
    // ═══════════════════════════════════════

    @Test
    fun getProfileShouldThrowUserNotFoundWhenUserDoesNotExist() = runTest {
        coEvery { userDao.findById(em, mockUserId) } returns null

        val ex = assertFailsWith<UserException> {
            userService.getProfile(mockUserId)
        }
        assertEquals(BizCode.USER_NOT_FOUND, ex.bizCode)
    }

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
        coEvery { userDao.findById(em, mockUserId) } returns userEntity

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

    @Test
    fun batchGetUsersShouldReturnEmptyResponseForEmptyUidList() = runTest {
        val req = BatchIdRequest.getDefaultInstance()

        val resp = userService.batchGetUsers(req)

        assertEquals(0, resp.usersCount)
    }

    @Test
    fun batchGetUsersShouldReturnOnlyFoundUsersForPartialResults() = runTest {
        val uid1 = 10001L
        val uid2 = 10002L
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

        coEvery { userDao.findAllById(em, listOf(uid1, uid2, uid3)) } returns listOf(user1, user3)

        val resp = userService.batchGetUsers(req)

        assertEquals(2, resp.usersCount)
        assertEquals(uid1, resp.getUsers(0).uid)
        assertEquals(uid3, resp.getUsers(1).uid)
    }

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

        coEvery { userDao.findAllById(em, listOf(uid1, uid2, uid3)) } returns users

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
