package com.nebula.gateway.session

import io.grpc.stub.StreamObserver
import io.github.oshai.kotlinlogging.KotlinLogging
import com.nebula.chat.Envelope
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 用户 StreamObserver 注册中心 — userId → StreamObserver 映射（D-01, D-02）。
 *
 * 职责：
 * - 注册 userId 对应的 gRPC StreamObserver（支持多设备 D-02）
 * - 移除单个设备或整个用户的 StreamObserver（D-01 连接清理）
 * - 查询用户所有在线设备的 StreamObserver 列表
 *
 * 使用 CopyOnWriteArrayList 存放每个 userId 的 StreamObserver 列表，
 * 写操作（register/remove）加锁保证线程安全，读操作（getStreams）无锁。
 * PushService 通过 getStreams 获取在线设备列表并逐个推送 Envelope。
 */
class UserStreamRegistry {

    /** userId → StreamObserver 列表映射，支持多设备并行登录（D-02） */
    private val userStreams = ConcurrentHashMap<Long, CopyOnWriteArrayList<StreamObserver<Envelope>>>()

    /**
     * 注册用户 StreamObserver — 多设备支持（D-02）。
     *
     * 如果该 userId 已有 observer 列表，追加到末尾；否则创建新列表。
     *
     * @param userId 用户 ID
     * @param observer 用户的 gRPC StreamObserver
     */
    fun register(userId: Long, observer: StreamObserver<Envelope>) {
        userStreams.compute(userId) { _, existingList ->
            val list = existingList ?: CopyOnWriteArrayList()
            list.add(observer)
            list
        }
    }

    /**
     * 移除用户的单个 StreamObserver（D-01 连接清理）。
     *
     * 从该 userId 的列表中移除指定 observer。如果列表变空，删除整个键。
     *
     * @param userId 用户 ID
     * @param observer 待移除的 StreamObserver
     */
    fun removeStream(userId: Long, observer: StreamObserver<Envelope>) {
        userStreams.computeIfPresent(userId) { _, list ->
            list.remove(observer)
            if (list.isEmpty()) null else list
        }
    }

    /**
     * 移除用户所有 StreamObserver（D-01 连接清理）。
     *
     * 整个删除 userId 的键，用于用户完全离线场景。
     *
     * @param userId 用户 ID
     */
    fun removeUser(userId: Long) {
        userStreams.remove(userId)
    }

    /**
     * 获取用户所有在线设备的 StreamObserver 列表。
     *
     * 返回列表的防御性拷贝，调用方遍历时不受并发修改影响。
     * 注意：返回的快照可能在迭代期间有过期流，调用方应通过 try-catch 处理。
     *
     * @param userId 用户 ID
     * @return StreamObserver 列表，无在线设备时返回空列表
     */
    fun getStreams(userId: Long): List<StreamObserver<Envelope>> {
        return userStreams[userId]?.toList() ?: emptyList()
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
