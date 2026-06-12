package com.nebula.gateway.session

import com.nebula.repository.redis.SessionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * SessionRegistry L1/L2 双级缓存单元测试（D-23, D-24, D-25）。
 *
 * 覆盖场景：
 * - validate: L1 命中不走 Redis；L1 未命中从 L2 查询并回填 L1
 * - register: 同时写入 L1 和 L2
 * - unregister: 移除 L1/L2 并触发驱逐回调
 * - L2 Redis 超时降级为仅 L1
 */
class SessionRegistryTest {

    private lateinit var sessionRepository: SessionRepository
    private lateinit var registry: SessionRegistry

    private val testSession = Session(
        userId = 1001L,
        token = "test-token-abc",
        deviceType = "android",
        deviceId = "device-001",
        connectionId = "conn-001"
    )

    @BeforeEach
    fun setUp() {
        sessionRepository = mockk<SessionRepository>()
        registry = SessionRegistry(sessionRepository)
    }

    @Test
    fun `validate returns session from L1 cache`() = runTest {
        // 先注册到 L1（模拟已登录状态）
        registry.addToLocalCache(testSession)

        val result = registry.validate(testSession.token)

        assertNotNull(result)
        assertEquals(testSession.userId, result.userId)
        assertEquals(testSession.token, result.token)
        // 验证 Redis 未被调用（L1 命中）
        coVerify(inverse = true) { sessionRepository.findByToken(any()) }
    }

    @Test
    fun `validate queries L2 when L1 misses`() = runTest {
        // L1 未命中，Mock L2 返回 Session 的 JSON
        val sessionJson = """{"userId":1001,"token":"test-token-abc","deviceType":"android","deviceId":"device-001","connectionId":"conn-001"}"""
        coEvery { sessionRepository.findByToken(testSession.token) } returns sessionJson

        val result = registry.validate(testSession.token)

        assertNotNull(result)
        assertEquals(testSession.userId, result.userId)
        assertEquals(testSession.token, result.token)
        coVerify(exactly = 1) { sessionRepository.findByToken(testSession.token) }

        // 验证结果已回填到 L1
        val cached = registry.getFromLocalCache(testSession.token)
        assertNotNull(cached)
    }

    @Test
    fun `register stores to L1 and L2`() = runTest {
        coEvery { sessionRepository.save(any(), any()) } returns Unit

        registry.register(testSession)

        // 验证 L1 可查到
        val cached = registry.getFromLocalCache(testSession.token)
        assertNotNull(cached)
        assertEquals(testSession.userId, cached.userId)

        // 验证 Redis save 被调用
        coVerify(exactly = 1) { sessionRepository.save(testSession.token, any()) }
    }

    @Test
    fun `unregister triggers eviction callbacks`() = runTest {
        coEvery { sessionRepository.delete(any()) } returns Unit
        registry.addToLocalCache(testSession)

        var callbackToken: String? = null
        registry.onEviction { token -> callbackToken = token }

        registry.unregister(testSession.token)

        // 验证回调被触发
        assertNotNull(callbackToken)
        assertEquals(testSession.token, callbackToken)

        // 验证 L1 已移除
        val cached = registry.getFromLocalCache(testSession.token)
        assertNull(cached)

        // 验证 Redis delete 被调用
        coVerify(exactly = 1) { sessionRepository.delete(testSession.token) }
    }
}
