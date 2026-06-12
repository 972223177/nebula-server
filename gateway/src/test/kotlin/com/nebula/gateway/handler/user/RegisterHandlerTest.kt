package com.nebula.gateway.handler.user

import com.nebula.chat.user.RegisterReq
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.repository.UserRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.persistence.EntityManager
import jakarta.persistence.EntityManagerFactory
import jakarta.persistence.EntityTransaction
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * RegisterHandler 单元测试（D-23, D-24, D-25）。
 *
 * 覆盖场景：
 * - 注册成功
 * - 用户名已存在
 * - 密码太短（3 位）
 * - 用户名为空
 */
class RegisterHandlerTest {

    private lateinit var userRepository: UserRepository
    private lateinit var idGenerator: SnowflakeIdGenerator
    private lateinit var handler: RegisterHandler

    @BeforeEach
    fun setUp() {
        userRepository = mockk<UserRepository>()
        idGenerator = mockk<SnowflakeIdGenerator>()

        // Mock JPA EntityManager chain for manual transaction management
        val tx = mockk<EntityTransaction>(relaxed = true)
        every { tx.begin() } returns Unit
        every { tx.commit() } returns Unit

        val em = mockk<EntityManager>(relaxed = true)
        every { em.transaction } returns tx

        val emf = mockk<EntityManagerFactory>()
        every { emf.createEntityManager() } returns em

        handler = RegisterHandler(userRepository, idGenerator, emf)
    }

    @Test
    fun `注册成功`() = runTest {
        coEvery { userRepository.findByUsername("newuser") } returns null
        every { idGenerator.nextId() } returns 10001L

        val savedUser = UserEntity(
            username = "newuser",
            passwordHash = "hashed",
            nickname = "新用户",
            avatar = ""
        ).apply { id = 10001L }
        coEvery { userRepository.save(any()) } returns savedUser

        val req = RegisterReq.newBuilder()
            .setUsername("newuser")
            .setPassword("password123")
            .setNickname("新用户")
            .build()
        val resp = handler.handle(req)

        assertNotNull(resp)
        assertEquals(10001L, resp.uid, "注册成功应返回正确的 uid")
    }

    @Test
    fun `用户名已存在`() = runTest {
        val existingUser = UserEntity(
            username = "existing",
            passwordHash = "hash",
            nickname = "已有用户",
            avatar = ""
        ).apply { id = 1001L }
        coEvery { userRepository.findByUsername("existing") } returns existingUser

        val req = RegisterReq.newBuilder()
            .setUsername("existing")
            .setPassword("password123")
            .setNickname("新用户")
            .build()

        try {
            handler.handle(req)
            kotlin.test.fail("应抛出 UserException(USERNAME_EXISTS)")
        } catch (e: UserException) {
            assertEquals(BizCode.USERNAME_EXISTS, e.bizCode)
        }
    }

    @Test
    fun `密码太短`() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername("newuser")
            .setPassword("abc")  // 3 位，少于 6 位
            .setNickname("新用户")
            .build()

        try {
            handler.handle(req)
            kotlin.test.fail("应抛出 UserException(INVALID_PARAM)")
        } catch (e: UserException) {
            assertEquals(BizCode.INVALID_PARAM, e.bizCode)
        }
    }

    @Test
    fun `用户名为空`() = runTest {
        val req = RegisterReq.newBuilder()
            .setUsername("  ")  // 空白用户名
            .setPassword("password123")
            .setNickname("新用户")
            .build()

        try {
            handler.handle(req)
            kotlin.test.fail("应抛出 UserException(INVALID_PARAM)")
        } catch (e: UserException) {
            assertEquals(BizCode.INVALID_PARAM, e.bizCode)
        }
    }
}
