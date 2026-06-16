package com.nebula.repository.testutil

import org.junit.jupiter.api.Test
import javax.sql.DataSource
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Flyway 数据库迁移验证测试。
 *
 * 继承 [DatabaseTestBase] 自动获得 TestContainers MySQL 容器和 Flyway 迁移执行能力。
 * 测试内容覆盖：
 * - 各迁移脚本执行后目标表是否存在
 * - 关键表的字段结构是否符合预期
 * - 初始种子数据是否正确导入
 *
 * 对应迁移文件：
 * - V1__init_schema.sql —— 建表
 * - V1_2__seed_users.sql —— 初始用户数据
 * - V2__phase7_conversation_schema.sql —— 会话相关表追加列
 * - V3__add_friend_request_message.sql —— 好友申请表追加消息列
 * - V4__add_dead_letters.sql —— 创建死信表
 * - V5__phase11_data_integrity.sql —— 唯一约束+索引
 */
class FlywayMigrationTest : DatabaseTestBase() {

    // ==================== 期望的表列表 ====================

    /** V1 初始化的所有表名 */
    private val expectedTables = setOf(
        "users",
        "conversations",
        "conversation_members",
        "messages",
        "friendships",
        "friend_requests"
    )

    /**
     * 验证所有预期表均已存在。
     * 查询 information_schema.TABLES 并与期望集合做比对。
     */
    @Test
    fun shouldCreateAllExpectedTables() {
        val ds = getDataSource()
        val actualTables = queryTables(ds)

        expectedTables.forEach { tableName ->
            assertTrue(
                actualTables.contains(tableName),
                "Expected table `$tableName` not found in database. Existing tables: $actualTables"
            )
        }

        assertTrue(
            actualTables.containsAll(expectedTables),
            "Table set mismatch. Expected: $expectedTables, Actual: $actualTables"
        )
    }

    /**
     * 验证 [users] 表的字段结构。
     * 检查关键字段名称、类型（简化）、非空约束和默认值。
     */
    @Test
    fun shouldHaveCorrectColumnsInUsersTable() {
        val ds = getDataSource()
        val columns = queryColumns(ds, "users")

        // id：BigInt，主键
        assertColumn(columns, "id", nullable = false)
        // username：VARCHAR 唯一键
        assertColumn(columns, "username", nullable = false)
        // password_hash：VARCHAR
        assertColumn(columns, "password_hash", nullable = false)
        // nickname：VARCHAR
        assertColumn(columns, "nickname", nullable = false)
        // avatar：VARCHAR，默认值 ''
        assertColumn(columns, "avatar", nullable = false, default = "")
        // privacy_status：INT，默认值 0
        assertColumn(columns, "privacy_status", nullable = false, default = "0")
        // created_at / updated_at
        assertColumn(columns, "created_at", nullable = false)
        assertColumn(columns, "updated_at", nullable = false)
    }

    /**
     * 验证 [conversations] 表包含 V2 追加的列。
     * 基础列 + 追加列（status, last_message_id, last_message_preview, last_message_ts）。
     */
    @Test
    fun shouldHaveCorrectColumnsInConversationsTable() {
        val ds = getDataSource()
        val columns = queryColumns(ds, "conversations")

        // V1 基础字段
        assertColumn(columns, "id", nullable = false)
        assertColumn(columns, "type", nullable = false)
        assertColumn(columns, "name", nullable = false)

        // V2 追加字段（D-17, D-21）
        assertColumn(columns, "status", nullable = false, default = "0")
        assertColumn(columns, "last_message_id", nullable = false, default = "0")
        assertColumn(columns, "last_message_preview", nullable = false, default = "")
        assertColumn(columns, "last_message_ts", nullable = false, default = "0")
    }

    /**
     * 验证 [conversation_members] 表包含 V2 追加的 role 列。
     */
    @Test
    fun shouldHaveCorrectColumnsInConversationMembersTable() {
        val ds = getDataSource()
        val columns = queryColumns(ds, "conversation_members")

        // V1 基础字段
        assertColumn(columns, "conversation_id", nullable = false)
        assertColumn(columns, "user_id", nullable = false)

        // V2 追加字段（D-17）
        assertColumn(columns, "role", nullable = false, default = "member")
    }

    /**
     * 验证 [messages] 表的关键字段。
     */
    @Test
    fun shouldHaveCorrectColumnsInMessagesTable() {
        val ds = getDataSource()
        val columns = queryColumns(ds, "messages")

        assertColumn(columns, "id", nullable = false)
        assertColumn(columns, "conversation_id", nullable = false)
        assertColumn(columns, "sender_uid", nullable = false)
        assertColumn(columns, "message_type", nullable = false)
        assertColumn(columns, "client_ts", nullable = false)
        assertColumn(columns, "server_ts", nullable = false)
        // payload 可为空
        assertColumn(columns, "payload", nullable = true)
        // client_message_id 可为空，允许 NULL 值用于不包括 client_id 的消息
        assertColumn(columns, "client_message_id", nullable = true)
    }

    /**
     * 验证 [friend_requests] 表包含 V3 追加的 message 列。
     */
    @Test
    fun shouldHaveCorrectColumnsInFriendRequestsTable() {
        val ds = getDataSource()
        val columns = queryColumns(ds, "friend_requests")

        // V1 基础字段
        assertColumn(columns, "from_uid", nullable = false)
        assertColumn(columns, "to_uid", nullable = false)
        assertColumn(columns, "status", nullable = false, default = "0")

        // V3 追加字段（D-42）
        assertColumn(columns, "message", nullable = false, default = "")
    }

    /**
     * 验证种子数据（V1_2__seed_users.sql）已正确导入。
     * 预期 3 个预置用户：admin（1000001）、testuser1（1000002）、testuser2（1000003）。
     */
    @Test
    fun shouldImportSeedUsers() {
        val ds = getDataSource()
        val conn = ds.connection
        conn.use { c ->
            val stmt = c.createStatement()

            // 验证用户总数
            val countRs = stmt.executeQuery("SELECT COUNT(*) FROM users")
            assertTrue(countRs.next(), "users 表无数据，种子数据未导入")
            val count = countRs.getInt(1)
            assertEquals(3, count, "Expected 3 seed users, actual: $count")

            // 验证特定用户记录
            val expectedUsers = mapOf(
                1000001L to "admin",
                1000002L to "testuser1",
                1000003L to "testuser2"
            )

            val userRs = stmt.executeQuery("SELECT id, username FROM users ORDER BY id")
            val actualUsers = mutableListOf<Pair<Long, String>>()
            while (userRs.next()) {
                actualUsers.add(userRs.getLong("id") to userRs.getString("username"))
            }

            // 按 id 提取已经存在的用户名用于校验
            val actualMap = actualUsers.toMap()
            expectedUsers.forEach { (expectedId, expectedUsername) ->
                val actualUsername = actualMap[expectedId]
                assertNotNull(actualUsername, "Seed user id=$expectedId not found")
                assertEquals(expectedUsername, actualUsername, "Seed user id=$expectedId username mismatch")
            }
        }
    }

    // ==================== V4/V5 迁移验证（P2-01）====================

    /**
     * 验证 V4 迁移创建了 dead_letters 表并包含正确字段结构。
     */
    @Test
    fun shouldHaveDeadLettersTable() {
        val ds = getDataSource()
        val columns = queryColumns(ds, "dead_letters")

        assertColumn(columns, "id", nullable = false)
        assertColumn(columns, "conversation_id", nullable = false)
        assertColumn(columns, "sender_uid", nullable = false)
        assertColumn(columns, "message_type", nullable = false)
        assertColumn(columns, "content", nullable = false)
        assertColumn(columns, "fail_reason", nullable = false, default = "")
        assertColumn(columns, "fail_count", nullable = false, default = "0")
        assertColumn(columns, "status", nullable = false, default = "pending")
        assertColumn(columns, "created_at", nullable = false)
    }

    /**
     * 验证 V5 迁移添加的唯一约束：
     * - uk_friendship_pair（函数索引 LEAST/GREATEST）
     * - uk_from_to_status（from_uid, to_uid, status）
     * - uk_client_msg_id（dead_letters.client_msg_id）
     */
    @Test
    fun shouldHaveDataIntegrityConstraints() {
        val ds = getDataSource()
        val conn = ds.connection
        conn.use { c ->
            val stmt = c.createStatement()
            val rs = stmt.executeQuery(
                """
                SELECT CONSTRAINT_NAME
                FROM information_schema.TABLE_CONSTRAINTS
                WHERE TABLE_SCHEMA = 'nebula_test'
                  AND CONSTRAINT_TYPE = 'UNIQUE'
                  AND CONSTRAINT_NAME IN ('uk_friendship_pair', 'uk_from_to_status', 'uk_client_msg_id')
                """.trimIndent()
            )
            val constraints = mutableSetOf<String>()
            while (rs.next()) {
                constraints.add(rs.getString("CONSTRAINT_NAME"))
            }
            assertTrue(constraints.contains("uk_friendship_pair"), "uk_friendship_pair 唯一约束应存在")
            assertTrue(constraints.contains("uk_from_to_status"), "uk_from_to_status 唯一约束应存在")
            assertTrue(constraints.contains("uk_client_msg_id"), "uk_client_msg_id 唯一约束应存在")
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 查询 information_schema.TABLES 获取数据库中存在的所有用户表名。
     */
    private fun queryTables(ds: DataSource): Set<String> {
        val conn = ds.connection
        conn.use { c ->
            val stmt = c.createStatement()
            val rs = stmt.executeQuery(
                """
                SELECT TABLE_NAME
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = 'nebula_test'
                  AND TABLE_TYPE = 'BASE TABLE'
                """.trimIndent()
            )
            val tables = mutableSetOf<String>()
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"))
            }
            return tables
        }
    }

    /**
     * 查询 information_schema.COLUMNS 获取指定表的所有字段元信息。
     *
     * @return 列名到列元数据的映射
     */
    private fun queryColumns(ds: DataSource, tableName: String): Map<String, ColumnInfo> {
        val conn = ds.connection
        conn.use { c ->
            val pstmt = c.prepareStatement(
                """
                SELECT COLUMN_NAME, IS_NULLABLE, COLUMN_DEFAULT
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = 'nebula_test'
                  AND TABLE_NAME = ?
                ORDER BY ORDINAL_POSITION
                """.trimIndent()
            )
            pstmt.setString(1, tableName)
            val rs = pstmt.executeQuery()
            val columns = mutableMapOf<String, ColumnInfo>()
            while (rs.next()) {
                val colName = rs.getString("COLUMN_NAME")
                val nullable = rs.getString("IS_NULLABLE") == "YES"
                val default = rs.getString("COLUMN_DEFAULT")
                columns[colName] = ColumnInfo(colName, nullable, default)
            }
            return columns
        }
    }

    /**
     * 断言某列存在且满足非空/默认值约束。
     */
    private fun assertColumn(
        columns: Map<String, ColumnInfo>,
        columnName: String,
        nullable: Boolean,
        default: String? = null
    ) {
        val col = columns[columnName]
        assertNotNull(col, "Column `$columnName` not found")
        assertEquals(nullable, col.nullable, "Column `$columnName` IS_NULLABLE mismatch")
        if (default != null) {
            assertEquals(default, col.defaultValue, "Column `$columnName` default value mismatch")
        }
    }

    /**
     * 字段元信息值对象。
     */
    private data class ColumnInfo(
        val name: String,
        val nullable: Boolean,
        val defaultValue: String?
    )
}
