package com.nebula.gateway.session

import com.nebula.chat.Envelope
import io.grpc.stub.StreamObserver
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UserStreamRegistry 单元测试。
 *
 * 覆盖场景：
 * - register 后 getStreams 返回包含该 observer 的列表
 * - 相同 userId 注册多个 observer 可全部返回
 * - removeStream 移除单个 observer，其他 observer 保留
 * - removeUser 移除整个 userId 所有 observer
 * - 未注册的 userId 返回空列表
 */
class UserStreamRegistryTest {

    private lateinit var registry: UserStreamRegistry
    private lateinit var observer1: StreamObserver<Envelope>
    private lateinit var observer2: StreamObserver<Envelope>

    @BeforeEach
    fun setUp() {
        registry = UserStreamRegistry()
        observer1 = mockk<StreamObserver<Envelope>>(relaxed = true)
        observer2 = mockk<StreamObserver<Envelope>>(relaxed = true)
    }

    @Test
    fun registerAddsObserverAndGetStreamsReturnsIt() {
        registry.register(1001L, observer1)
        val streams = registry.getStreams(1001L)
        assertTrue(streams.contains(observer1))
        assertEquals(1, streams.size)
    }

    @Test
    fun sameUserIdRegistersMultipleObservers() {
        registry.register(1001L, observer1)
        registry.register(1001L, observer2)
        val streams = registry.getStreams(1001L)
        assertTrue(streams.contains(observer1))
        assertTrue(streams.contains(observer2))
        assertEquals(2, streams.size)
    }

    @Test
    fun removeStreamRemovesSingleObserverAndKeepsOthers() {
        registry.register(1001L, observer1)
        registry.register(1001L, observer2)
        registry.removeStream(1001L, observer1)
        val streams = registry.getStreams(1001L)
        assertTrue(streams.contains(observer2))
        assertEquals(1, streams.size)
    }

    @Test
    fun removeUserRemovesAllObserversForUserId() {
        registry.register(1001L, observer1)
        registry.register(1001L, observer2)
        registry.removeUser(1001L)
        val streams = registry.getStreams(1001L)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun getStreamsReturnsEmptyListForUnregisteredUserId() {
        val streams = registry.getStreams(9999L)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun removeStreamOnEmptyListDoesNotThrow() {
        // 不应该抛异常
        registry.removeStream(9999L, observer1)
        val streams = registry.getStreams(9999L)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun removeUserOnNonExistentUserIdDoesNotThrow() {
        registry.removeUser(9999L)
        val streams = registry.getStreams(9999L)
        assertTrue(streams.isEmpty())
    }

    @Test
    fun registerAfterRemoveUserReAddsObserver() {
        registry.register(1001L, observer1)
        registry.removeUser(1001L)
        registry.register(1001L, observer2)
        val streams = registry.getStreams(1001L)
        assertTrue(streams.contains(observer2))
        assertEquals(1, streams.size)
    }

    @Test
    fun removeStreamRemovesLastObserverAndCleansUpKey() {
        registry.register(1001L, observer1)
        registry.removeStream(1001L, observer1)
        val streams = registry.getStreams(1001L)
        assertTrue(streams.isEmpty())
    }
}
