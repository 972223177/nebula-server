package com.nebula.repository.repository

import com.nebula.repository.entity.ConversationEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 会话数据仓库。
 */
interface ConversationRepository : JpaRepository<ConversationEntity, String>
