package com.nebula.gateway.di

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.common.session.SessionStore
import com.nebula.common.sensitiveword.SensitiveWordService
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.delivery.DeliveryTrackingService
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.HandlerCollector
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.handler.chat.ChatHandlerCollector
import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.delivery.DeliveryAckHandler
import com.nebula.gateway.handler.conversation.ConversationHandlerCollector
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.conversation.CreateGroupHandler
import com.nebula.gateway.handler.conversation.CreatePrivateConversationHandler
import com.nebula.gateway.handler.conversation.DeleteConversationHandler
import com.nebula.gateway.handler.conversation.EditGroupHandler
import com.nebula.gateway.handler.conversation.GroupListHandler
import com.nebula.gateway.handler.conversation.GroupMembersHandler
import com.nebula.gateway.handler.conversation.InviteMemberHandler
import com.nebula.gateway.handler.conversation.KickMemberHandler
import com.nebula.gateway.handler.conversation.LeaveGroupHandler
import com.nebula.gateway.handler.conversation.ListConversationsHandler
import com.nebula.gateway.handler.friend.FriendAcceptHandler
import com.nebula.gateway.handler.friend.FriendAddHandler
import com.nebula.gateway.handler.friend.FriendBatchCheckRelationHandler
import com.nebula.gateway.handler.friend.FriendCheckRelationHandler
import com.nebula.gateway.handler.friend.FriendDeleteHandler
import com.nebula.gateway.handler.friend.FriendHandlerCollector
import com.nebula.gateway.handler.friend.FriendListHandler
import com.nebula.gateway.handler.friend.FriendRejectHandler
import com.nebula.gateway.handler.friend.FriendRequestsHandler
import com.nebula.gateway.handler.message.MessageSeqHandler
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.gateway.handler.sensitiveword.SensitiveWordDownloadHandler
import com.nebula.gateway.handler.sensitiveword.SensitiveWordHandlerCollector
import com.nebula.gateway.handler.sensitiveword.SensitiveWordReloadHandler
import com.nebula.gateway.handler.external.ExternalHandlerCollector
import com.nebula.gateway.handler.external.QueryWeatherHandler
import com.nebula.gateway.handler.external.WebSearchHandler
import com.nebula.gateway.handler.system.SystemHandlerCollector
import com.nebula.gateway.handler.user.BatchGetStatusHandler
import com.nebula.gateway.handler.user.BatchGetUserHandler
import com.nebula.gateway.handler.user.GetPrivacyHandler
import com.nebula.gateway.handler.user.GetProfileHandler
import com.nebula.gateway.handler.user.LoginHandler
import com.nebula.gateway.handler.user.RegisterHandler
import com.nebula.gateway.handler.user.SearchUserHandler
import com.nebula.gateway.handler.user.SetPrivacyHandler
import com.nebula.gateway.handler.user.UserHandlerCollector
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.dao.ConversationDao
import com.nebula.repository.dao.ConversationMemberDao
import com.nebula.repository.dao.DeadLetterDao
import com.nebula.repository.dao.FriendRequestDao
import com.nebula.repository.dao.FriendshipDao
import com.nebula.repository.dao.JpaTxRunner
import com.nebula.repository.dao.MessageDao
import com.nebula.repository.dao.UserDao
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.redis.SessionRepository
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.test.get
import kotlin.test.assertNotNull

/**
 * GatewayModule Koin 模块装配测试（D-23, D-24）。
 *
 * 方案 A 重构（2026-06-20）：Service/Handler 不再依赖 Spring Data Repository 与 TransactionTemplate，
 * 改用 DAO + JpaTxRunner 注入。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GatewayModuleTest {

    /** Mock 依赖 */
    private val sessionRepo = mockk<SessionRepository>()
    private val onlineStatusRepo = mockk<OnlineStatusRepository>()
    private val idGenerator = mockk<SnowflakeIdGenerator>()
    private val privacyRepo = mockk<PrivacyRepository>()
    private val redisConnection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
    private val messageQueueRepo = mockk<MessageQueueRepository>()
    private val txRunner = mockk<JpaTxRunner>()
    private val userDao = mockk<UserDao>()
    private val conversationDao = mockk<ConversationDao>()
    private val conversationMemberDao = mockk<ConversationMemberDao>()
    private val messageDao = mockk<MessageDao>()
    private val friendshipDao = mockk<FriendshipDao>()
    private val friendRequestDao = mockk<FriendRequestDao>()
    private val deadLetterDao = mockk<DeadLetterDao>()
    private val deliveryTrackingService = mockk<DeliveryTrackingService>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Service 层 mock */
    private val userService = mockk<UserService>()
    private val userPrivacyService = mockk<UserPrivacyService>()
    private val messageService = mockk<MessageService>()
    private val conversationService = mockk<ConversationService>()
    private val friendService = mockk<FriendService>()
    private val onlineStatusService = mockk<OnlineStatusService>()
    private val sensitiveWordService = mockk<SensitiveWordService>()
    private val externalOrchestrator = mockk<ExternalServiceOrchestrator>()

    /**
     * 构建外部 Repository Koin 模块。
     */
    private fun buildExternalModule() = module {
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
        single { deliveryTrackingService }
        single(named("serverScope")) { serverScope }
    }

    /**
     * 构建测试专用的 handlerModule，匹配重构后的构造函数。
     */
    private fun buildHandlerModule() = module {
        // Service 层
        single { userService }
        single { userPrivacyService }
        single { messageService }
        single { conversationService }
        single { friendService }
        single { onlineStatusService }

        // Phase 5: User Handler
        single { PingHandler() }
        single { LoginHandler(userService, get()) }
        single { RegisterHandler(sensitiveWordService, userService) }
        single { SearchUserHandler(userService) }
        single { GetProfileHandler(userService) }
        single { BatchGetUserHandler(userService) }
        single { BatchGetStatusHandler(get(), get()) }
        single { SetPrivacyHandler(userPrivacyService, get(), get(), get(), get(named("serverScope"))) }
        single { GetPrivacyHandler(userPrivacyService) }

        // Phase 6: Chat & Message
        single { UserStreamRegistry() }
        single { PushService(get(), get(), get()) }
        single { SendMessageHandler(sensitiveWordService, messageService, get(), get(), get(), get(named("serverScope"))) }
        single { PullMessagesHandler(messageService) }
        single { ReadReportHandler(messageService, get(), get(), get()) }
        single { DeliveryAckHandler(get(), get(), get()) }

        // Phase 10: Message Reliability
        single { com.nebula.service.sequence.SeqService(get()) }
        single { com.nebula.gateway.handler.message.MessageSeqHandler(get()) }

        // Phase 7: Conversation
        single { ConversationLockManager() }
        single { ListConversationsHandler(conversationService) }
        single { GroupMembersHandler(conversationService) }
        single { EditGroupHandler(sensitiveWordService, conversationService, get()) }
        single { CreateGroupHandler(conversationService, get()) }
        single { InviteMemberHandler(conversationService, get(), get()) }
        single { LeaveGroupHandler(conversationService, get(), get()) }
        single { KickMemberHandler(conversationService, get(), get()) }
        single { DeleteConversationHandler(conversationService, get(), get()) }
        single { CreatePrivateConversationHandler(conversationService) }
        single { GroupListHandler(conversationService) }

        // Phase 8: Friend
        single { FriendRejectHandler(friendService) }
        single { FriendRequestsHandler(friendService) }
        single { FriendListHandler(friendService) }
        single { FriendDeleteHandler(friendService) }
        single { FriendAddHandler(sensitiveWordService, friendService, get(), get()) }
        single { FriendAcceptHandler(friendService, get(), get()) }
        single { FriendCheckRelationHandler(friendService) }
        single { FriendBatchCheckRelationHandler(friendService) }

        // 敏感词：下载（免登录）+ 重载（admin）
        single { sensitiveWordService }
        single { SensitiveWordDownloadHandler(get()) }
        single { SensitiveWordReloadHandler(get(), get()) }

        // 外部服务（天气 / 搜索）—— 镜像生产 externalHandlerModule
        single { externalOrchestrator }
        single { QueryWeatherHandler(get()) }
        single { WebSearchHandler(get()) }
        single<HandlerCollector>(named("external")) { ExternalHandlerCollector(get(), get()) }
    }

    @AfterEach
    fun tearDown() {
        stopKoin()
        // 取消 CoroutineScope，释放 Dispatchers.IO 线程，避免非守护线程阻止 JVM 退出
        scope.cancel()
    }

    @Test
    fun frameworkModuleResolvesHandlerRegistry() {
        startKoin {
            modules(frameworkModule, buildExternalModule())
        }
        val handlerRegistry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(handlerRegistry)
    }

    @Test
    fun frameworkModuleResolvesProtoCodecAndDependencies() {
        startKoin {
            modules(frameworkModule, buildExternalModule())
        }
        val handlerRegistry = GlobalContext.get().get<HandlerRegistry>()
        val protoCodec = GlobalContext.get().get<ProtoCodec>()
        assertNotNull(handlerRegistry)
        assertNotNull(protoCodec)
    }

    @Test
    fun allHandlerCollectorsRegisterAllMethodsViaGetAll() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()

        // 手动解析每个 Collector 并注册
        val pingHandler = GlobalContext.get().get<PingHandler>()
        val systemCollector = com.nebula.gateway.handler.system.SystemHandlerCollector(pingHandler)
        systemCollector.registerAll(registry)

        val userCollector = com.nebula.gateway.handler.user.UserHandlerCollector(
            GlobalContext.get().get<LoginHandler>(),
            GlobalContext.get().get<RegisterHandler>(),
            GlobalContext.get().get<SearchUserHandler>(),
            GlobalContext.get().get<GetProfileHandler>(),
            GlobalContext.get().get<BatchGetUserHandler>(),
            GlobalContext.get().get<BatchGetStatusHandler>(),
            GlobalContext.get().get<SetPrivacyHandler>(),
            GlobalContext.get().get<GetPrivacyHandler>()
        )
        userCollector.registerAll(registry)

        val chatCollector = com.nebula.gateway.handler.chat.ChatHandlerCollector(
            GlobalContext.get().get<SendMessageHandler>(),
            GlobalContext.get().get<PullMessagesHandler>(),
            GlobalContext.get().get<ReadReportHandler>(),
            GlobalContext.get().get<com.nebula.gateway.handler.message.MessageSeqHandler>(),
            GlobalContext.get().get<DeliveryAckHandler>()
        )
        chatCollector.registerAll(registry)

        val convCollector = com.nebula.gateway.handler.conversation.ConversationHandlerCollector(
            GlobalContext.get().get<ListConversationsHandler>(),
            GlobalContext.get().get<GroupMembersHandler>(),
            GlobalContext.get().get<GroupListHandler>(),
            GlobalContext.get().get<EditGroupHandler>(),
            GlobalContext.get().get<CreateGroupHandler>(),
            GlobalContext.get().get<InviteMemberHandler>(),
            GlobalContext.get().get<LeaveGroupHandler>(),
            GlobalContext.get().get<KickMemberHandler>(),
            GlobalContext.get().get<DeleteConversationHandler>(),
            GlobalContext.get().get<CreatePrivateConversationHandler>()
        )
        convCollector.registerAll(registry)

        val friendCollector = com.nebula.gateway.handler.friend.FriendHandlerCollector(
            GlobalContext.get().get<FriendRejectHandler>(),
            GlobalContext.get().get<FriendRequestsHandler>(),
            GlobalContext.get().get<FriendListHandler>(),
            GlobalContext.get().get<FriendDeleteHandler>(),
            GlobalContext.get().get<FriendAddHandler>(),
            GlobalContext.get().get<FriendAcceptHandler>(),
            GlobalContext.get().get<FriendCheckRelationHandler>(),
            GlobalContext.get().get<FriendBatchCheckRelationHandler>()
        )
        friendCollector.registerAll(registry)

        // System Handler
        assertNotNull(registry.get("system/ping"))

        // Phase 5: User Handler
        assertNotNull(registry.get("user/login"))
        assertNotNull(registry.get("user/register"))
        assertNotNull(registry.get("user/search"))
        assertNotNull(registry.get("user/getProfile"))
        assertNotNull(registry.get("user/batchGet"))
        assertNotNull(registry.get("user/batchGetStatus"))
        assertNotNull(registry.get("user/setPrivacy"))
        assertNotNull(registry.get("user/getPrivacy"))

        // Phase 6: Chat & Message Handler
        assertNotNull(registry.get("chat/send"))
        assertNotNull(registry.get("message/pull"))
        assertNotNull(registry.get("message/read"))

        // Phase 7: Conversation Handler
        assertNotNull(registry.get("conversation/list"))
        assertNotNull(registry.get("conversation/group_members"))
        assertNotNull(registry.get("conversation/edit_group_info"))
        assertNotNull(registry.get("conversation/create_group"))
        assertNotNull(registry.get("conversation/invite_member"))
        assertNotNull(registry.get("conversation/leave_group"))
        assertNotNull(registry.get("conversation/kick_member"))

        // Phase 8: Friend Handler
        assertNotNull(registry.get("friend/reject"))
        assertNotNull(registry.get("friend/requests"))
        assertNotNull(registry.get("friend/list"))
        assertNotNull(registry.get("friend/delete"))
        assertNotNull(registry.get("friend/add"))
        assertNotNull(registry.get("friend/accept"))
    }

    // ===================== 领域专项验证测试 =====================

    /**
     * 验证 Chat 领域 Handler Collector 注册全部 5 个 method 名称。
     */
    @Test
    fun chatHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()
        val collector = ChatHandlerCollector(
            GlobalContext.get().get<SendMessageHandler>(),
            GlobalContext.get().get<PullMessagesHandler>(),
            GlobalContext.get().get<ReadReportHandler>(),
            GlobalContext.get().get<MessageSeqHandler>(),
            GlobalContext.get().get<DeliveryAckHandler>()
        )
        collector.registerAll(registry)
        assertNotNull(registry.get("chat/send"))
        assertNotNull(registry.get("message/pull"))
        assertNotNull(registry.get("message/read"))
        assertNotNull(registry.get("message/seq"))
        assertNotNull(registry.get("message/delivery_ack"))
    }

    /**
     * 验证 Conversation 领域 Handler Collector 注册全部 7 个 method 名称。
     */
    @Test
    fun conversationHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()
        val collector = ConversationHandlerCollector(
            GlobalContext.get().get<ListConversationsHandler>(),
            GlobalContext.get().get<GroupMembersHandler>(),
            GlobalContext.get().get<GroupListHandler>(),
            GlobalContext.get().get<EditGroupHandler>(),
            GlobalContext.get().get<CreateGroupHandler>(),
            GlobalContext.get().get<InviteMemberHandler>(),
            GlobalContext.get().get<LeaveGroupHandler>(),
            GlobalContext.get().get<KickMemberHandler>(),
            GlobalContext.get().get<DeleteConversationHandler>(),
            GlobalContext.get().get<CreatePrivateConversationHandler>()
        )
        collector.registerAll(registry)
        assertNotNull(registry.get("conversation/list"))
        assertNotNull(registry.get("conversation/group_members"))
        assertNotNull(registry.get("conversation/group_list"))
        assertNotNull(registry.get("conversation/edit_group_info"))
        assertNotNull(registry.get("conversation/create_group"))
        assertNotNull(registry.get("conversation/invite_member"))
        assertNotNull(registry.get("conversation/leave_group"))
        assertNotNull(registry.get("conversation/kick_member"))
        assertNotNull(registry.get("conversation/delete"))
        assertNotNull(registry.get("conversation/create_private"))
    }

    /**
     * 验证 Friend 领域 Handler Collector 注册全部 6 个 method 名称。
     */
    @Test
    fun friendHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()
        val collector = FriendHandlerCollector(
            GlobalContext.get().get<FriendRejectHandler>(),
            GlobalContext.get().get<FriendRequestsHandler>(),
            GlobalContext.get().get<FriendListHandler>(),
            GlobalContext.get().get<FriendDeleteHandler>(),
            GlobalContext.get().get<FriendAddHandler>(),
            GlobalContext.get().get<FriendAcceptHandler>(),
            GlobalContext.get().get<FriendCheckRelationHandler>(),
            GlobalContext.get().get<FriendBatchCheckRelationHandler>()
        )
        collector.registerAll(registry)
        assertNotNull(registry.get("friend/reject"))
        assertNotNull(registry.get("friend/requests"))
        assertNotNull(registry.get("friend/list"))
        assertNotNull(registry.get("friend/delete"))
        assertNotNull(registry.get("friend/add"))
        assertNotNull(registry.get("friend/accept"))
        assertNotNull(registry.get("friend/check"))
        assertNotNull(registry.get("friend/batchCheck"))
    }

    /**
     * 验证 System 领域 Handler Collector 注册 ping method 名称。
     */
    @Test
    fun systemHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()
        val pingHandler = GlobalContext.get().get<PingHandler>()
        val collector = SystemHandlerCollector(pingHandler)
        collector.registerAll(registry)
        assertNotNull(registry.get("system/ping"))
    }

    /**
     * 验证 User 领域 Handler Collector 注册全部 8 个 method 名称。
     */
    @Test
    fun userHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()
        val collector = UserHandlerCollector(
            GlobalContext.get().get<LoginHandler>(),
            GlobalContext.get().get<RegisterHandler>(),
            GlobalContext.get().get<SearchUserHandler>(),
            GlobalContext.get().get<GetProfileHandler>(),
            GlobalContext.get().get<BatchGetUserHandler>(),
            GlobalContext.get().get<BatchGetStatusHandler>(),
            GlobalContext.get().get<SetPrivacyHandler>(),
            GlobalContext.get().get<GetPrivacyHandler>()
        )
        collector.registerAll(registry)
        assertNotNull(registry.get("user/login"))
        assertNotNull(registry.get("user/register"))
        assertNotNull(registry.get("user/search"))
        assertNotNull(registry.get("user/getProfile"))
        assertNotNull(registry.get("user/batchGet"))
        assertNotNull(registry.get("user/batchGetStatus"))
        assertNotNull(registry.get("user/setPrivacy"))
        assertNotNull(registry.get("user/getPrivacy"))
    }

    /**
     * 验证敏感词 Handler Collector 注册 download 与 reload 两个 method 名称。
     */
    @Test
    fun sensitiveWordHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()
        val collector = SensitiveWordHandlerCollector(
            GlobalContext.get().get<SensitiveWordDownloadHandler>(),
            GlobalContext.get().get<SensitiveWordReloadHandler>()
        )
        collector.registerAll(registry)
        assertNotNull(registry.get("system/sensitive_word_download"))
        assertNotNull(registry.get("admin/sensitive_word_reload"))
    }

    /**
     * 验证外部服务 Handler Collector 注册 query_weather 与 web_search 两个 method 名称。
     */
    @Test
    fun externalHandlersRegisteredCorrectly() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()
        val collector = ExternalHandlerCollector(
            GlobalContext.get().get<QueryWeatherHandler>(),
            GlobalContext.get().get<WebSearchHandler>()
        )
        collector.registerAll(registry)
        assertNotNull(registry.get("external/query_weather"))
        assertNotNull(registry.get("external/web_search"))
    }
}
