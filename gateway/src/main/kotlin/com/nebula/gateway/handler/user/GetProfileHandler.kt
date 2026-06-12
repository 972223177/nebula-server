package com.nebula.gateway.handler.user

import com.nebula.chat.user.GetProfileReq
import com.nebula.chat.user.GetProfileResp
import com.nebula.common.BizCode
import com.nebula.common.exception.UserException
import com.nebula.gateway.handler.Handler
import com.nebula.repository.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.ZoneOffset

/**
 * 用户详细资料查询 Handler — method = "user/getProfile"（BIZ-USER-02）。
 *
 * 根据目标用户的 UID 获取其详细资料，当前所有用户资料公开可查。
 * Phase 8 可能增加好友/非好友可见范围控制。
 *
 * **v1 字段填充说明：**
 * - `GetProfileResp.gender`（字段编号 5）：当前 User 表尚无 gender 字段，v1 中暂不填充（保持默认值）
 * - `GetProfileResp.bio`（字段编号 6）：当前 User 表尚无 bio 字段，v1 中暂不填充（保持默认值）
 *
 * @param userRepository 用户数据仓库，用于按 ID 查询用户
 */
class GetProfileHandler(
    private val userRepository: UserRepository
) : Handler<GetProfileReq, GetProfileResp> {

    /** method 路由：user/getProfile — 用户详细资料 */
    override val method: String = "user/getProfile"

    /**
     * 根据用户 ID 获取详细资料。
     *
     * @param req 包含目标用户 UID 的请求
     * @return 用户详细资料，包含 uid、username、displayName、avatarUrl、createdAt
     * @throws UserException(BizCode.USER_NOT_FOUND) 目标用户不存在
     */
    override suspend fun handle(req: GetProfileReq): GetProfileResp {
        val user = withContext(Dispatchers.IO) { userRepository.findById(req.uid).orElse(null) }
            ?: throw UserException(BizCode.USER_NOT_FOUND)

        return GetProfileResp.newBuilder()
            .setUid(user.id!!)
            .setUsername(user.username)
            .setDisplayName(user.nickname)
            .setAvatarUrl(user.avatar)
            .setCreatedAt(user.createdAt?.atZone(ZoneOffset.UTC)?.toInstant()?.toEpochMilli() ?: 0)
            .build()
    }
}
