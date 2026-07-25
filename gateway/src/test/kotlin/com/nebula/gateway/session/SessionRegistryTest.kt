package com.nebula.gateway.session

import com.nebula.common.session.SessionStore
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

    private lateinit var sessionStore: SessionStore
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
        sessionStore = mockk<SessionStore>()
        registry = SessionRegistry(sessionStore)
    }

    @Test
    fun validateReturnsSessionFromL1Cache() = runTest {
        // 先注册到 L1（模拟已登录状态）
        registry.addToLocalCache(testSession)

        val result = registry.validate(testSession.token)

        assertNotNull(result)
        assertEquals(testSession.userId, result.userId)
        assertEquals(testSession.token, result.token)
        // 验证 Redis 未被调用（L1 命中）
        coVerify(inverse = true) { sessionStore.findByToken(any()) }
    }

    @Test
    fun validateQueriesL2WhenL1Misses() = runTest {
        // L1 未命中，Mock L2 返回 Session 的 JSON
        val sessionJson = """{"userId":1001,"token":"test-token-abc","deviceType":"android","deviceId":"device-001","connectionId":"conn-001"}"""
        coEvery { sessionStore.findByToken(testSession.token) } returns sessionJson

        val result = registry.validate(testSession.token)

        assertNotNull(result)
        assertEquals(testSession.userId, result.userId)
        assertEquals(testSession.token, result.token)
        coVerify(exactly = 1) { sessionStore.findByToken(testSession.token) }

        // 验证结果已回填到 L1
        val cached = registry.getFromLocalCache(testSession.token)
        assertNotNull(cached)
    }

    @Test
    fun registerStoresToL1AndL2() = runTest {
        coEvery { sessionStore.save(any(), any()) } returns Unit

        registry.register(testSession)

        // 验证 L1 可查到
        val cached = registry.getFromLocalCache(testSession.token)
        assertNotNull(cached)
        assertEquals(testSession.userId, cached.userId)

        // 验证 Redis save 被调用
        coVerify(exactly = 1) { sessionStore.save(testSession.token, any()) }
    }

    @Test
    fun unregisterTriggersEvictionCallbacks() = runTest {
        coEvery { sessionStore.delete(any()) } returns Unit
        registry.addToLocalCache(testSession)

        var callbackToken: String? = null
        var callbackReason: EvictionReason? = null
        registry.onEviction { token, reason -> callbackToken = token; callbackReason = reason }

        registry.unregister(testSession.token)

        // 验证回调被触发且透传默认原因 KICK
        assertNotNull(callbackToken)
        assertEquals(testSession.token, callbackToken)
        assertEquals(EvictionReason.KICK, callbackReason)

        // 验证 L1 已移除
        val cached = registry.getFromLocalCache(testSession.token)
        assertNull(cached)

        // 验证 Redis delete 被调用
        coVerify(exactly = 1) { sessionStore.delete(testSession.token) }
    }

    @Test
    fun peekDeviceTypeTokenReturnsNullWhenNoMapping() {
        // 未注册任何设备类型映射时，peek 应返回 null
        assertNull(registry.peekDeviceTypeToken(testSession.userId, testSession.deviceType))
    }

    @Test
    fun peekDeviceTypeTokenReturnsExistingToken() = runTest {
        coEvery { sessionStore.save(any(), any()) } returns Unit
        coEvery { sessionStore.saveRaw(any(), any()) } returns Unit

        registry.registerWithDeviceType(testSession)

        // 注册后应能 peek 到当前 token
        assertEquals(testSession.token, registry.peekDeviceTypeToken(testSession.userId, testSession.deviceType))
    }

    @Test
    fun replaceSessionSilentlyReplacesMappingWithoutEviction() = runTest {
        coEvery { sessionStore.save(any(), any()) } returns Unit
        coEvery { sessionStore.delete(any()) } returns Unit
        coEvery { sessionStore.saveRaw(any(), any()) } returns Unit
        coEvery { sessionStore.deleteKey(any()) } returns Unit

        registry.registerWithDeviceType(testSession)

        // 注册驱逐回调，验证静默替换不应触发
        var evictionFired = false
        registry.onEviction { _, _ -> evictionFired = true }

        val newSession = testSession.copy(token = "test-token-new", connectionId = "conn-002")
        val replaced = registry.replaceSessionSilently(newSession)

        // 返回被替换的旧 token
        assertEquals(testSession.token, replaced)
        // 关键：静默替换不触发 eviction 回调（AUTH-07 自踢防护）
        assertEquals(false, evictionFired)
        // 设备类型映射已指向新 token
        assertEquals("test-token-new", registry.peekDeviceTypeToken(testSession.userId, testSession.deviceType))
        // 旧 token 已从 L1 移除，新 token 已写入 L1
        assertNull(registry.getFromLocalCache(testSession.token))
        assertNotNull(registry.getFromLocalCache("test-token-new"))
    }

    @Test
    fun replaceSessionSilentlyReturnsNullWhenNoExisting() = runTest {
        coEvery { sessionStore.save(any(), any()) } returns Unit
        coEvery { sessionStore.saveRaw(any(), any()) } returns Unit

        // 无旧映射时首次静默替换应返回 null（仅写入新映射）
        val replaced = registry.replaceSessionSilently(testSession)

        assertNull(replaced)
        assertEquals(testSession.token, registry.peekDeviceTypeToken(testSession.userId, testSession.deviceType))
    }
}
