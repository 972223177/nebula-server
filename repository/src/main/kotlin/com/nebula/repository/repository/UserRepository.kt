package com.nebula.repository.repository

import com.nebula.repository.entity.UserEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 用户数据仓库。
 */
interface UserRepository : JpaRepository<UserEntity, Long> {
    /** 按用户名查找用户 */
    fun findByUsername(username: String): UserEntity?
}
