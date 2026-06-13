package com.nebula.service.user

import com.nebula.chat.user.GetPrivacyReq
import com.nebula.chat.user.GetPrivacyResp
import com.nebula.chat.user.SetPrivacyReq
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.repository.FriendshipRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.*

/**
 * UserPrivacyService 的单元测试。
 *
 * 使用 MockK 隔离测试业务逻辑，mock 三个 Repository 依赖。
 * 覆盖 setHideOnlineStatus 和 getHideOnlineStatus 两个方法的正常路径。
 *
 * 注意：Repository 方法均为 suspend fun，因此使用 coEvery/coVerify 进行 mock。
 */
class UserPrivacyServiceTest {

    private val privacyRepository: PrivacyRepository = mockk()
    private val onlineStatusRepository: OnlineStatusRepository = mockk()
    private val friendshipRepository: FriendshipRepository = mockk()

    private lateinit var service: UserPrivacyService

    @BeforeEach
    fun setUp() {
        clearMocks(privacyRepository, onlineStatusRepository, friendshipRepository)
        service = UserPrivacyService(privacyRepository, onlineStatusRepository, friendshipRepository)
    }

    // ─── setHideOnlineStatus ───

    /**
     * 设置 hideOnlineStatus=true 时：
     * - 调用 privacyRepository.setHideOnlineStatus(userId, true)
     * - 调用 onlineStatusRepository.setHidden(userId)
     * - 不会调用 onlineStatusRepository.setOnline
     */
    @Test
    fun setHideOnlineStatusShouldWriteToRepoAndSyncRedisToHidden() = runTest {
        val userId = 123L
        val req = SetPrivacyReq.newBuilder().setHideOnlineStatus(true).build()

        coEvery { privacyRepository.setHideOnlineStatus(userId, true) } just Runs
        coEvery { onlineStatusRepository.setHidden(userId) } just Runs

        service.setHideOnlineStatus(userId, req)

        coVerify(exactly = 1) { privacyRepository.setHideOnlineStatus(userId, true) }
        coVerify(exactly = 1) { onlineStatusRepository.setHidden(userId) }
        coVerify(exactly = 0) { onlineStatusRepository.setOnline(any()) }
    }

    /**
     * 设置 hideOnlineStatus=false 时：
     * - 调用 privacyRepository.setHideOnlineStatus(userId, false)
     * - 调用 onlineStatusRepository.setOnline(userId)
     * - 不会调用 onlineStatusRepository.setHidden
     */
    @Test
    fun setHideOnlineStatusShouldWriteToRepoAndSyncRedisToOnline() = runTest {
        val userId = 456L
        val req = SetPrivacyReq.newBuilder().setHideOnlineStatus(false).build()

        coEvery { privacyRepository.setHideOnlineStatus(userId, false) } just Runs
        coEvery { onlineStatusRepository.setOnline(userId) } just Runs

        service.setHideOnlineStatus(userId, req)

        coVerify(exactly = 1) { privacyRepository.setHideOnlineStatus(userId, false) }
        coVerify(exactly = 1) { onlineStatusRepository.setOnline(userId) }
        coVerify(exactly = 0) { onlineStatusRepository.setHidden(any()) }
    }

    // ─── getHideOnlineStatus ───

    /**
     * 用户为隐藏状态时，getHideOnlineStatus 返回包含 hideOnlineStatus=true 的 GetPrivacyResp。
     */
    @Test
    fun getHideOnlineStatusShouldReturnTrueWhenUserHidden() = runTest {
        val userId = 789L
        val req = GetPrivacyReq.getDefaultInstance()

        coEvery { privacyRepository.getHideOnlineStatus(userId) } returns true

        val resp = service.getHideOnlineStatus(userId, req)

        assertTrue(resp.hideOnlineStatus)
        coVerify(exactly = 1) { privacyRepository.getHideOnlineStatus(userId) }
    }

    /**
     * 用户为可见状态时，getHideOnlineStatus 返回包含 hideOnlineStatus=false 的 GetPrivacyResp。
     */
    @Test
    fun getHideOnlineStatusShouldReturnFalseWhenUserVisible() = runTest {
        val userId = 101112L
        val req = GetPrivacyReq.getDefaultInstance()

        coEvery { privacyRepository.getHideOnlineStatus(userId) } returns false

        val resp = service.getHideOnlineStatus(userId, req)

        assertFalse(resp.hideOnlineStatus)
        coVerify(exactly = 1) { privacyRepository.getHideOnlineStatus(userId) }
    }
}
