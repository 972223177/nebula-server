package com.nebula.service.friend

import com.nebula.chat.friend.FriendAcceptReq
import com.nebula.chat.friend.FriendListReq
import com.nebula.common.BizCode
import com.nebula.common.exception.FriendException
import com.nebula.repository.dao.*
import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.entity.UserEntity
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.service.user.UserPrivacyService
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.persistence.EntityManager
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * FriendService 单元测试（mock 风格）。
 *
 * 覆盖 6cd66f7 提交的两处修复：
 * 1. acceptFriendRequest 防御性自校验：fromUid==toUid 的异常申请直接拒绝（SELF_FRIEND），
 *    避免建立 (uid,uid) 自好友关系与 private:uid:uid 自会话。
 * 2. listFriends 过滤 (user_id==friend_id) 的自好友记录，好友列表不显示自己账户。
 */
class FriendServiceTest {

    private lateinit var friendRequestDao: FriendRequestDao
    private lateinit var friendshipDao: FriendshipDao
    private lateinit var conversationDao: ConversationDao
    private lateinit var conversationMemberDao: ConversationMemberDao
    private lateinit var userDao: UserDao
    private lateinit var txRunner: JpaTxRunner
    private lateinit var onlineStatusRepository: OnlineStatusRepository
    private lateinit var userPrivacyService: UserPrivacyService
    private lateinit var em: EntityManager
    private lateinit var friendService: FriendService

    @BeforeEach
    fun setup() {
        friendRequestDao = mockk()
        friendshipDao = mockk()
        conversationDao = mockk()
        conversationMemberDao = mockk()
        userDao = mockk()
        txRunner = mockk()
        onlineStatusRepository = mockk(relaxed = true)
        userPrivacyService = mockk(relaxed = true)
        em = mockk(relaxed = true)
        friendService = FriendService(
            friendRequestDao, friendshipDao, conversationDao,
            conversationMemberDao, userDao, txRunner, onlineStatusRepository, userPrivacyService
        )
        coEvery { txRunner.execute<Any>(any()) } coAnswers {
            @Suppress("UNCHECKED_CAST")
            (args[0] as suspend (EntityManager) -> Any).invoke(em)
        }
    }

    @Test
    fun acceptFriendRequestShouldRejectSelfRequest() = runTest {
        val req = FriendAcceptReq.newBuilder().setRequestId(99L).build()
        // 历史脏数据: 发起方与接收方相同
        val selfRequest = FriendRequestEntity(fromUid = 5, toUid = 5, status = 0)
        coEvery { friendRequestDao.findById(em, 99L) } returns selfRequest

        val ex = assertFailsWith<FriendException> {
            friendService.acceptFriendRequest(req, 5L)
        }
        assertEquals(BizCode.SELF_FRIEND, ex.bizCode)
    }

    @Test
    fun listFriendsShouldFilterSelfFriendship() = runTest {
        val selfFs = FriendshipEntity(userId = 5, friendId = 5).apply { deleted = 0 }
        val normalFs = FriendshipEntity(userId = 5, friendId = 8).apply { deleted = 0 }
        coEvery { friendshipDao.findFriendsByUserId(em, 5L, any(), any()) } returns listOf(selfFs, normalFs)
        coEvery { userDao.findAllById(em, listOf(8L)) } returns listOf(
            UserEntity(username = "u8", passwordHash = "h", nickname = "用户8").apply { id = 8L }
        )

        val resp = friendService.listFriends(FriendListReq.newBuilder().setLimit(50).build(), 5L)

        assertEquals(1, resp.friendsCount)
        assertEquals(8L, resp.getFriends(0).uid)
        assertFalse(resp.friendsList.any { it.uid == 5L }, "好友列表不应包含自己账户")
    }
}
