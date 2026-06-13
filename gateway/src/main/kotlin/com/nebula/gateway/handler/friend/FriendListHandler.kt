package com.nebula.gateway.handler.friend

import com.nebula.chat.friend.FriendBrief
import com.nebula.chat.friend.FriendListReq
import com.nebula.chat.friend.FriendListResp
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository
import com.nebula.repository.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import org.springframework.data.domain.PageRequest
import java.time.ZoneOffset

/**
 * 获取好友列表 Handler，支持游标分页（D-46）。
 *
 * 游标分页查询好友记录 → 提取 friendUid → 批量查询 User 信息
 * → 批量查询在线状态（排除隐藏用户）→ 构造 FriendListResp。
 */
class FriendListHandler(
    private val friendshipRepository: FriendshipRepository,
    private val userRepository: UserRepository,
    private val onlineStatusRepository: OnlineStatusRepository,
    private val privacyRepository: PrivacyRepository
) : Handler<FriendListReq, FriendListResp> {

    override val method: String = "friend/list"

    companion object {
        /** 默认每页大小 */
        private const val DEFAULT_PAGE_SIZE = 20
    }

    override suspend fun handle(req: FriendListReq): FriendListResp {
        val session = currentCoroutineContext().requireSession()
        val cursor = if (req.cursor > 0) req.cursor else 0L
        val limit = if (req.limit > 0) req.limit else DEFAULT_PAGE_SIZE

        // 游标分页查询好友记录
        val friendships = withContext(Dispatchers.IO) {
            friendshipRepository.findFriendsByUserId(
                session.userId, cursor, PageRequest.of(0, limit)
            )
        }

        // 提取好友 UID（排除自己的 UID）
        val friendUids = friendships.map { f ->
            if (f.userId == session.userId) f.friendId else f.userId
        }.distinct()

        if (friendUids.isEmpty()) {
            return FriendListResp.getDefaultInstance()
        }

        // 批量查询 User 信息
        val userMap = withContext(Dispatchers.IO) {
            userRepository.findAllById(friendUids).associateBy { it.id }
        }

        // 批量查询隐藏用户（排除隐藏用户）
        val hiddenUids = privacyRepository.batchGetHideOnlineStatus(friendUids)

        // 批量查询在线状态
        val statusMap = onlineStatusRepository.batchGetStatus(friendUids)

        // 构建好友关系时间映射
        val createdAtMap = friendships.associate { f ->
            val friendUid = if (f.userId == session.userId) f.friendId else f.userId
            friendUid to (f.createdAt?.toEpochSecond(ZoneOffset.UTC)?.times(1000) ?: 0L)
        }

        val friends = friendUids.map { uid ->
            val user = userMap[uid]
            // 隐藏用户在线状态始终为 0（离线），status=2 仅在状态查询时显示
            val status = if (uid in hiddenUids) 0 else (statusMap[uid]?.status ?: 0)

            FriendBrief.newBuilder()
                .setUid(uid)
                .setUsername(user?.username ?: "")
                .setDisplayName(user?.nickname ?: "")
                .setAvatarUrl(user?.avatar ?: "")
                .setStatus(status)
                .setCreatedAt(createdAtMap[uid] ?: 0L)
                .build()
        }

        return FriendListResp.newBuilder()
            .addAllFriends(friends)
            .build()
    }
}
