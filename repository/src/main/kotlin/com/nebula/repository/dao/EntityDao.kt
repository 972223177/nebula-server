package com.nebula.repository.dao

import jakarta.persistence.EntityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 实体 DAO 基类（替代 Spring Data JpaRepository）。
 *
 * 设计动机：摆脱 Spring Data JPA 派生查询魔法，提供直白、显式的数据库操作。
 *
 * ## 设计原则
 *
 * 1. **不隐藏 SQL**：所有查询都是显式 JPQL 字符串
 * 2. **不绑线程**：接收 [EntityManager] 参数，由调用方提供（通常来自 [JpaTxRunner]）
 * 3. **不管理事务**：仅做单次操作；事务由 [JpaTxRunner] 统一管理
 * 4. **不阻塞协程**：所有方法 `suspend` + `withContext(Dispatchers.IO)`
 *
 * ## 与 JpaRepository 的差异
 *
 * - 无 `findByXxx` 派生方法（需手写 JPQL）
 * - 无自动 dirty checking（每次 `update` 需显式 merge）
 * - 无 Pageable 内置（分页用 `setFirstResult` + `setMaxResults`）
 *
 * ## 用法
 *
 * ```kotlin
 * class UserDao : EntityDao<UserEntity>(UserEntity::class.java) {
 *     suspend fun findByUsername(em: EntityManager, username: String): UserEntity? =
 *         querySingle(em, "SELECT u FROM UserEntity u WHERE u.username = :username",
 *             "username" to username)
 * }
 *
 * // Service 层
 * suspend fun getByUsername(username: String): UserEntity? = txRunner.execute { em ->
 *     userDao.findByUsername(em, username)
 * }
 * ```
 *
 * @param T 实体类型
 * @param entityClass 实体的 Class 对象（用于 JPA 操作）
 */
abstract class EntityDao<T : Any>(protected val entityClass: Class<T>) {

    /**
     * 根据主键查询实体。
     *
     * @param em 当前事务的 [EntityManager]
     * @param id 主键值
     * @return 实体，不存在时返回 null
     */
    suspend fun findById(em: EntityManager, id: Any): T? = io {
        em.find(entityClass, id)
    }

    /**
     * 根据主键批量查询实体。
     *
     * @param em 当前事务的 [EntityManager]
     * @param ids 主键值列表
     * @return 实体列表（按入参 ids 顺序；缺失 ID 静默忽略）
     */
    suspend fun findAllById(em: EntityManager, ids: Collection<*>): List<T> = io {
        if (ids.isEmpty()) {
            return@io emptyList()
        }
        val query = em.createQuery(
            "SELECT e FROM ${entityClass.simpleName} e WHERE e.id IN :ids",
            entityClass
        )
        query.setParameter("ids", ids)
        query.resultList
    }

    /**
     * 插入新实体（INSERT）。
     *
     * 主键由实体自身生成（Snowflake 等），生成后会被 em 自动赋值。
     *
     * @param em 当前事务的 [EntityManager]
     * @param entity 待持久化实体
     * @return 持久化后的实体（同引用）
     */
    suspend fun insert(em: EntityManager, entity: T): T = io {
        em.persist(entity)
        entity
    }

    /**
     * 批量插入实体（同一事务内）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param entities 待持久化实体列表
     * @return 持久化后的实体列表（同引用）
     */
    suspend fun insertAll(em: EntityManager, entities: Collection<T>): List<T> = io {
        entities.forEach { em.persist(it) }
        entities.toList()
    }

    /**
     * 更新实体（UPDATE）。等价于 JPA merge：若实体在 PersistenceContext 中已存在则更新，否则插入。
     *
     * @param em 当前事务的 [EntityManager]
     * @param entity 待更新实体
     * @return 合并后的实体（可能是新托管实例）
     */
    suspend fun update(em: EntityManager, entity: T): T = io {
        em.merge(entity)
    }

    /**
     * 按主键删除实体。
     *
     * 实体不存在时静默忽略（Hibernate 行为）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param id 主键值
     */
    suspend fun deleteById(em: EntityManager, id: Any) = io {
        val entity = em.find(entityClass, id) ?: return@io
        em.remove(entity)
    }

    /**
     * 删除指定实体。
     *
     * @param em 当前事务的 [EntityManager]
     * @param entity 待删除实体
     */
    suspend fun delete(em: EntityManager, entity: T) = io {
        em.remove(entity)
    }

    /**
     * 执行 JPQL 查询，返回单条结果。
     *
     * @param em 当前事务的 [EntityManager]
     * @param jpql JPQL 语句
     * @param params 命名参数，键为 JPQL 中的 `:paramName`，值为绑定值
     * @return 查询结果，不存在时返回 null
     */
    suspend fun querySingle(
        em: EntityManager,
        jpql: String,
        vararg params: Pair<String, Any?>
    ): T? = io {
        val query = em.createQuery(jpql, entityClass)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        query.resultList.firstOrNull()
    }

    /**
     * 执行 JPQL 查询，返回结果列表。
     *
     * @param em 当前事务的 [EntityManager]
     * @param jpql JPQL 语句
     * @param params 命名参数
     * @return 查询结果列表
     */
    suspend fun queryList(
        em: EntityManager,
        jpql: String,
        vararg params: Pair<String, Any?>
    ): List<T> = io {
        val query = em.createQuery(jpql, entityClass)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        query.resultList
    }

    /**
     * 执行 JPQL UPDATE/DELETE 语句（@Modifying 等价物）。
     *
     * 不返回受影响行数（Hibernate `executeUpdate` 的返回），由调用方按需使用 `query.executeUpdate()`。
     *
     * @param em 当前事务的 [EntityManager]
     * @param jpql UPDATE/DELETE 语句
     * @param params 命名参数
     * @return 受影响的行数
     */
    suspend fun executeUpdate(
        em: EntityManager,
        jpql: String,
        vararg params: Pair<String, Any?>
    ): Int = io {
        val query = em.createQuery(jpql)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        query.executeUpdate()
    }

    /**
     * 执行 COUNT 查询。
     *
     * @param em 当前事务的 [EntityManager]
     * @param jpql SELECT COUNT(...) 语句
     * @param params 命名参数
     * @return 计数结果
     */
    suspend fun count(
        em: EntityManager,
        jpql: String,
        vararg params: Pair<String, Any?>
    ): Long = io {
        val query = em.createQuery(jpql, Long::class.java)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        query.singleResult
    }

    /**
     * 协程内的 IO 调度辅助方法 — 等价于 `withContext(Dispatchers.IO) { block() }`。
     *
     * 单次调用内嵌，避免每个方法都写一长串 withContext。
     */
    protected suspend inline fun <R> io(crossinline block: () -> R): R =
        withContext(Dispatchers.IO) { block() }
}
