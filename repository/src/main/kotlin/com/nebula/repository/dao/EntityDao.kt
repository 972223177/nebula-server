package com.nebula.repository.dao

import jakarta.persistence.EntityManager

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
 * 4. **不嵌套调度**：DAO 方法不内部 `withContext(IO)`，由调用方负责在 IO 线程上调用
 *
 * ## 关于线程调度（性能优化）
 *
 * 所有 DAO 方法设计为"调用方在 IO 线程调用"——通常意味着调用方已经在 [JpaTxRunner.execute]
 * 的 `withContext(Dispatchers.IO)` 上下文中。这样避免了**嵌套 withContext** 的性能开销：
 *
 * - 每次 `withContext` 涉及 3 个步骤：挂起当前协程、调度器切换、等待恢复
 * - 嵌套 withContext 会产生叠加开销：N 次挂起 + N 个 DispatchedTask 对象分配 + N 次线程队列操作
 * - 相同 Dispatcher 默认仍有调度开销（除非重写 `isDispatchNeeded` 返回 false）
 *
 * **设计契约**：
 * - 任何 DAO 方法调用必须已在 IO 线程上，或在调用方自行 withContext(IO) 包裹
 * - JpaTxRunner.execute 已经提供 IO 上下文，DAO 调用无需再 wrap
 * - 直接外部调用 DAO（如测试场景）需自行保证 IO 上下文
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
 * // Service 层（在 JpaTxRunner 内部，IO 上下文已就绪）
 * suspend fun getByUsername(username: String): UserEntity? = txRunner.execute { em ->
 *     userDao.findByUsername(em, username)  // 直接调用，无嵌套 withContext
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
     * 必须在 IO 线程上调用（参见类 KDoc 的"线程调度"说明）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param id 主键值
     * @return 实体，不存在时返回 null
     */
    suspend fun findById(em: EntityManager, id: Any): T? {
        return em.find(entityClass, id)
    }

    /**
     * 根据主键批量查询实体。
     *
     * 必须在 IO 线程上调用。
     *
     * @param em 当前事务的 [EntityManager]
     * @param ids 主键值列表
     * @return 实体列表（按入参 ids 顺序；缺失 ID 静默忽略）
     */
    suspend fun findAllById(em: EntityManager, ids: Collection<*>): List<T> {
        if (ids.isEmpty()) {
            return emptyList()
        }
        val query = em.createQuery(
            "SELECT e FROM ${entityClass.simpleName} e WHERE e.id IN :ids",
            entityClass
        )
        query.setParameter("ids", ids)
        return query.resultList
    }

    /**
     * 插入新实体（INSERT）。
     *
     * 必须在 IO 线程上调用。主键由实体自身生成（Snowflake 等），生成后会被 em 自动赋值。
     *
     * @param em 当前事务的 [EntityManager]
     * @param entity 待持久化实体
     * @return 持久化后的实体（同引用）
     */
    suspend fun insert(em: EntityManager, entity: T): T {
        em.persist(entity)
        return entity
    }

    /**
     * 批量插入实体（同一事务内）。
     *
     * 必须在 IO 线程上调用。
     *
     * @param em 当前事务的 [EntityManager]
     * @param entities 待持久化实体列表
     * @return 持久化后的实体列表（同引用）
     */
    suspend fun insertAll(em: EntityManager, entities: Collection<T>): List<T> {
        entities.forEach { em.persist(it) }
        return entities.toList()
    }

    /**
     * 更新实体（UPDATE）。等价于 JPA merge：若实体在 PersistenceContext 中已存在则更新，否则插入。
     *
     * 必须在 IO 线程上调用。
     *
     * @param em 当前事务的 [EntityManager]
     * @param entity 待更新实体
     * @return 合并后的实体（可能是新托管实例）
     */
    suspend fun update(em: EntityManager, entity: T): T {
        return em.merge(entity)
    }

    /**
     * 按主键删除实体。
     *
     * 必须在 IO 线程上调用。实体不存在时静默忽略（Hibernate 行为）。
     *
     * @param em 当前事务的 [EntityManager]
     * @param id 主键值
     */
    suspend fun deleteById(em: EntityManager, id: Any) {
        val entity = em.find(entityClass, id) ?: return
        em.remove(entity)
    }

    /**
     * 删除指定实体。
     *
     * 必须在 IO 线程上调用。
     *
     * @param em 当前事务的 [EntityManager]
     * @param entity 待删除实体
     */
    suspend fun delete(em: EntityManager, entity: T) {
        em.remove(entity)
    }

    /**
     * 执行 JPQL 查询，返回单条结果。
     *
     * 必须在 IO 线程上调用。
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
    ): T? {
        val query = em.createQuery(jpql, entityClass)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        return query.resultList.firstOrNull()
    }

    /**
     * 执行 JPQL 查询，返回结果列表。
     *
     * 必须在 IO 线程上调用。
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
    ): List<T> {
        val query = em.createQuery(jpql, entityClass)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        return query.resultList
    }

    /**
     * 执行 JPQL UPDATE/DELETE 语句（@Modifying 等价物）。
     *
     * 必须在 IO 线程上调用。
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
    ): Int {
        val query = em.createQuery(jpql)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        return query.executeUpdate()
    }

    /**
     * 执行 COUNT 查询。
     *
     * 必须在 IO 线程上调用。
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
    ): Long {
        val query = em.createQuery(jpql, Long::class.java)
        params.forEach { (name, value) -> query.setParameter(name, value) }
        return query.singleResult
    }
}
