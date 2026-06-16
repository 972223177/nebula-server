package com.nebula.common.session

/**
 * 会话持久化存储接口 — 定义 Session 数据的抽象操作。
 *
 * 放置于 common 模块以确保 repository 和 gateway 均可访问此接口，
 * 避免 repository → service 的循环依赖。
 *
 * 由 repository 模块的 [com.nebula.repository.redis.SessionRepository] 实现，
 * 供 gateway 层通过 Koin DI 注入使用。
 */
interface SessionStore {
    /** 保存 Session JSON 到持久化存储，可指定 TTL 秒数（默认 7 天） */
    suspend fun save(token: String, sessionJson: String, ttlSeconds: Long = 604800L)

    /** 根据 token 查询 Session JSON，不存在时返回 null */
    suspend fun findByToken(token: String): String?

    /** 删除指定 token 的 Session */
    suspend fun delete(token: String)

    /** 保存原始 key-value 数据（用于设备类型映射等元数据），可指定 TTL 秒数 */
    suspend fun saveRaw(key: String, value: String, ttlSeconds: Long = 604800L)

    /** 删除指定 key 的原始数据 */
    suspend fun deleteKey(key: String)

    /** 查询指定 key 的原始数据，不存在时返回 null */
    suspend fun findRaw(key: String): String?

    /**
     * 滑动续期 Session TTL。
     *
     * 每次请求认证通过后应调用此方法，刷新 Session 过期时间，
     * 避免活跃用户在固定 TTL 到期后被强制下线。
     *
     * @param token Session 令牌
     * @param ttlSeconds 续期 TTL 秒数，默认 7 天
     */
    suspend fun refreshTtl(token: String, ttlSeconds: Long = 604800L)
}
