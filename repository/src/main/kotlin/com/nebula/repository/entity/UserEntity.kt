package com.nebula.repository.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 用户实体，映射 users 表。
 *
 * D-06: 使用常规 class + JPA 注解，非 data class。
 * D-07: 必填字段在构造参数中声明，DB 自动生成字段用可空默认值。
 */
@Entity
@Table(name = "users", indexes = [
    Index(name = "uk_username", columnList = "username", unique = true)
])
class UserEntity(
    /** 用户名，登录唯一凭证 */
    @Column(nullable = false, unique = true, length = 64)
    var username: String,

    /** BCrypt 密码哈希 */
    @Column(nullable = false, length = 128)
    var passwordHash: String,

    /** 显示名称 */
    @Column(nullable = false, length = 64)
    var nickname: String,

    /** 头像 URL */
    @Column(length = 256)
    var avatar: String = "",

    /** 在线状态可见性：0=所有人, 1=仅好友, 2=隐藏 */
    @Column(nullable = false)
    var privacyStatus: Int = 0
) {
    /** 用户 ID，Snowflake 算法生成 */
    @Id
    @Column(nullable = false)
    var id: Long? = null

    /** 创建时间，DB 自动生成 */
    @Column(nullable = false, updatable = false)
    var createdAt: LocalDateTime? = null

    /** 更新时间，DB 自动更新 */
    @Column(nullable = false)
    var updatedAt: LocalDateTime? = null
}
