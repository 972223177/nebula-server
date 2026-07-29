package com.nebula.gateway.di

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.common.session.SessionStore
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.handler.AllHandlerCollector
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.dao.*
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.redis.SessionRepository
import com.nebula.service.admin.DeadLetterService
import com.nebula.service.chat.MessageService
import com.nebula.service.conversation.ConversationService
import com.nebula.service.external.ExternalServiceOrchestrator
import com.nebula.service.friend.FriendService
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.user.UserService
import io.lettuce.core.api.StatefulRedisConnection
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * 测试用 Koin 模块构建器 — 集中构造 Gateway 装配所需的 mock 依赖与 Handler 声明，
 * 供 [GatewayModuleTest] 与 [MethodNamesConsistencyTest] 复用，避免各测试重复维护同一份 mock 图。
 *
 * Handler 声明（含统一的 [AllHandlerCollector]）与生产 `gatewayModules` 保持结构一致：
 * 新增 Handler 时只需在此 [handlerModule] 中追加一行 `single { XHandler(...) }`，
 * [AllHandlerCollector] 经 `getAll()` 自动纳入，无需改动任何 Collector。
 *
 * serverScope 由 [frameworkModule] 提供，本构建器不再重复声明，避免与框架模块冲突。
 */
object GatewayTestModules {

    fun externalModule() = module {
        val sessionRepo = mockk<SessionRepository>()
        val onlineStatusRepo = mockk<OnlineStatusRepository>()
        val idGenerator = mockk<SnowflakeIdGenerator>()
        val privacyRepo = mockk<PrivacyRepository>()
        val redisConnection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
        val messageQueueRepo = mockk<MessageQueueRepository>()
        val txRunner = mockk<JpaTxRunner>()
        val userDao = mockk<UserDao>()
        val conversationDao = mockk<ConversationDao>()
        val conversationMemberDao = mockk<ConversationMemberDao>()
        val messageDao = mockk<MessageDao>()
        val friendshipDao = mockk<FriendshipDao>()
        val friendRequestDao = mockk<FriendRequestDao>()
        val deadLetterDao = mockk<DeadLetterDao>()

        single { sessionRepo }
        // SessionRepository 实现 SessionStore 接口，需要注册为 SessionStore 以供 SessionRegistry 注入
        single<SessionStore> { sessionRepo }
        single { onlineStatusRepo }
        single { idGenerator }
        single { redisConnection as StatefulRedisConnection<String, String> }
        single { messageQueueRepo }
        single { txRunner }
        single { userDao }
        single { conversationDao }
        single { conversationMemberDao }
        single { messageDao }
        single { friendshipDao }
        single { friendRequestDao }
        single { deadLetterDao }
        single { privacyRepo }
    }

    fun handlerModule() = module {
        val userService = mockk<UserService>()
        val userPrivacyService = mockk<UserPrivacyService>()
        val messageService = mockk<MessageService>()
        val conversationService = mockk<ConversationService>()
        val friendService = mockk<FriendService>()
        val onlineStatusService = mockk<OnlineStatusService>()
        val sensitiveWordService = mockk<SensitiveWordService>()
        val externalOrchestrator = mockk<ExternalServiceOrchestrator>()

        // Service 层
        single { userService }
        single { userPrivacyService }
        single { messageService }
        single { conversationService }
        single { friendService }
        single { onlineStatusService }

        // Phase 5: User Handler
        single { com.nebula.gateway.handler.PingHandler() } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.LoginHandler(userService, get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.LogoutHandler(get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.RegisterHandler(sensitiveWordService, userService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.SearchUserHandler(userService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.GetProfileHandler(userService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.BatchGetUserHandler(userService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.BatchGetStatusHandler(get(), get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.SetPrivacyHandler(userPrivacyService, get(), get(), get(), get(named("serverScope"))) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.user.GetPrivacyHandler(userPrivacyService) } bind com.nebula.gateway.handler.Handler::class

        // Phase 6: Chat & Message
        val deliveryTrackingService = mockk<DeliveryTrackingService>()
        single { deliveryTrackingService }
        single { UserStreamRegistry() }
        single { PushService(get(), get(), get()) }
        single { com.nebula.gateway.handler.chat.send.SendMessageHandler(sensitiveWordService, messageService, get(), get(), get(), get(named("serverScope"))) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.message.PullMessagesHandler(messageService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.message.ReadReportHandler(messageService, get(), get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.delivery.DeliveryAckHandler(get(), get(), get()) } bind com.nebula.gateway.handler.Handler::class

        // Phase 10: Message Reliability
        single { com.nebula.service.sequence.SeqService(com.nebula.service.sequence.SeqServiceImpl(get())) }
        single { com.nebula.gateway.handler.message.MessageSeqHandler(get()) } bind com.nebula.gateway.handler.Handler::class

        // Phase 7: Conversation
        single { com.nebula.gateway.handler.conversation.ConversationLockManager() }
        single { com.nebula.gateway.handler.conversation.ListConversationsHandler(conversationService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.GroupMembersHandler(conversationService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.EditGroupHandler(sensitiveWordService, conversationService, get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.CreateGroupHandler(conversationService, get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.InviteMemberHandler(conversationService, get(), get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.LeaveGroupHandler(conversationService, get(), get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.KickMemberHandler(conversationService, get(), get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.DeleteConversationHandler(conversationService, get(), get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.CreatePrivateConversationHandler(conversationService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.conversation.GroupListHandler(conversationService) } bind com.nebula.gateway.handler.Handler::class

        // Phase 8: Friend
        single { com.nebula.gateway.handler.friend.FriendRejectHandler(friendService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.friend.FriendRequestsHandler(friendService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.friend.FriendListHandler(friendService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.friend.FriendDeleteHandler(friendService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.friend.FriendAddHandler(sensitiveWordService, friendService, get(), get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.friend.FriendAcceptHandler(friendService, get(), get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.friend.FriendCheckRelationHandler(friendService) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.friend.FriendBatchCheckRelationHandler(friendService) } bind com.nebula.gateway.handler.Handler::class

        // 敏感词：下载（免登录）+ 重载（admin）
        single { sensitiveWordService }
        single { com.nebula.gateway.handler.sensitiveword.SensitiveWordDownloadHandler(get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.sensitiveword.SensitiveWordReloadHandler(get(), get()) } bind com.nebula.gateway.handler.Handler::class

        // Phase 10: Admin 死信（无需认证，admin/ 前缀白名单）
        val deadLetterService = mockk<DeadLetterService>()
        single { deadLetterService }
        single { com.nebula.gateway.handler.admin.DeadLetterQueryHandler(get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.admin.RetryDeadLetterHandler(get()) } bind com.nebula.gateway.handler.Handler::class

        // 外部服务（天气 / 搜索 / IP 定位 / 服务发现 / 通用调用）
        single { externalOrchestrator }
        single { com.nebula.gateway.handler.external.QueryWeatherHandler(get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.external.WebSearchHandler(get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.external.IpLocationHandler(get()) } bind com.nebula.gateway.handler.Handler::class
        // 同接口多实现用 bind（否则主 key 相同互相覆盖，getAll 只聚合 1 个，list_services 只返 1 个）
        single { com.nebula.gateway.handler.external.agent.WeatherServiceProvider() } bind com.nebula.gateway.handler.external.agent.ServiceDefinitionProvider::class
        single { com.nebula.gateway.handler.external.agent.WebSearchServiceProvider() } bind com.nebula.gateway.handler.external.agent.ServiceDefinitionProvider::class
        single { com.nebula.gateway.handler.external.agent.GeoIpServiceProvider() } bind com.nebula.gateway.handler.external.agent.ServiceDefinitionProvider::class
        single { com.nebula.gateway.handler.external.agent.ServiceRegistry(get(), getAll()) }
        single { com.nebula.gateway.handler.external.agent.ListServicesHandler(get()) } bind com.nebula.gateway.handler.Handler::class
        single { com.nebula.gateway.handler.external.agent.CallServiceHandler(get()) } bind com.nebula.gateway.handler.Handler::class

        // 统一 Handler 收集器（替代原先按分组逐个列举的 Collector）
        single<HandlerCollector> { AllHandlerCollector(getAll()) }
    }
}
