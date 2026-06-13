package com.nebula.gateway.di

import com.nebula.common.idgen.SnowflakeIdGenerator
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.PingHandler
import com.nebula.gateway.handler.chat.send.SendMessageHandler
import com.nebula.gateway.handler.chat.send.SendMessageStep
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.handler.conversation.CreateGroupHandler
import com.nebula.gateway.handler.conversation.EditGroupHandler
import com.nebula.gateway.handler.conversation.GroupMembersHandler
import com.nebula.gateway.handler.conversation.InviteMemberHandler
import com.nebula.gateway.handler.conversation.KickMemberHandler
import com.nebula.gateway.handler.conversation.LeaveGroupHandler
import com.nebula.gateway.handler.conversation.ListConversationsHandler
import com.nebula.gateway.handler.message.PullMessagesHandler
import com.nebula.gateway.handler.message.ReadReportHandler
import com.nebula.gateway.handler.user.BatchGetStatusHandler
import com.nebula.gateway.handler.user.BatchGetUserHandler
import com.nebula.gateway.handler.user.GetPrivacyHandler
import com.nebula.gateway.handler.user.GetProfileHandler
import com.nebula.gateway.handler.user.LoginHandler
import com.nebula.gateway.handler.user.RegisterHandler
import com.nebula.gateway.handler.user.SearchUserHandler
import com.nebula.gateway.handler.user.SetPrivacyHandler
import com.nebula.gateway.push.PushService
import com.nebula.gateway.session.UserStreamRegistry
import com.nebula.repository.redis.MessageQueueRepository
import com.nebula.repository.redis.OnlineStatusRepository
import com.nebula.repository.redis.PrivacyRepository
import com.nebula.repository.redis.SessionRepository
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import com.nebula.repository.repository.MessageRepository
import com.nebula.repository.repository.UserRepository
import io.lettuce.core.api.StatefulRedisConnection
import jakarta.persistence.EntityManagerFactory
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.test.get
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * GatewayModule Koin 模块装配测试（D-23, D-24）。
 *
 * Review 修复：使用 @AfterEach + stopKoin() 显式清理 Koin 容器，
 * 防止测试间 Koin 状态污染。每个测试方法独立启动自己的 Koin 实例。
 */
@TestInstance(TestInstance.Lifecycle.PER_METHOD)
class GatewayModuleTest {

    /** Mock 依赖 */
    private val sessionRepo = mockk<SessionRepository>()
    private val userRepo = mockk<UserRepository>()
    private val onlineStatusRepo = mockk<OnlineStatusRepository>()
    private val idGenerator = mockk<SnowflakeIdGenerator>()
    private val privacyRepo = mockk<PrivacyRepository>()
    private val redisConnection = mockk<StatefulRedisConnection<String, String>>(relaxed = true)
    private val conversationMemberRepo = mockk<ConversationMemberRepository>()
    private val conversationRepo = mockk<ConversationRepository>()
    private val messageRepo = mockk<MessageRepository>()
    private val messageQueueRepo = mockk<MessageQueueRepository>()
    private val emf = mockk<EntityManagerFactory>()
    private val transactionTemplate = mockk<TransactionTemplate>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 构建供测试使用的外部 Repository Koin 模块。
     * 模拟 NebulaServer 中 externalModule 的所有外部依赖。
     * PrivacyRepository 直接 mock 以避免依赖 StatefulRedisConnection 的泛型类型擦除问题。
     */
    private fun buildExternalModule() = module {
        single { sessionRepo }
        single { userRepo }
        single { onlineStatusRepo }
        single { idGenerator }
        single { redisConnection as StatefulRedisConnection<String, String> }
        single { messageQueueRepo }
        single { emf as EntityManagerFactory }
        single { conversationMemberRepo }
        single { conversationRepo }
        single { messageRepo }
        single { transactionTemplate }  // Phase 7: D-19 TransactionTemplate
    }

    /**
     * 构建测试专用的 handlerModule，代替生产 handlerModule。
     * PrivacyRepository 直接使用 mock 实例，避免 Koin 解析 StatefulRedisConnection 泛型参数。
     * Phase 6 组件使用 mock 外部依赖，避免真实 Redis/Lettuce 连接。
     */
    private fun buildHandlerModule() = module {
        single { PingHandler() }
        single { LoginHandler(get(), get()) }
        single { RegisterHandler(get(), get(), get()) }
        single { SearchUserHandler(get()) }
        single { GetProfileHandler(get()) }
        single { BatchGetUserHandler(get()) }
        single { privacyRepo }         // 使用 mock PrivacyRepository
        single { BatchGetStatusHandler(get(), get()) }
        single { SetPrivacyHandler(get()) }
        single { GetPrivacyHandler(get()) }

        // Phase 6: Chat & Message — 使用 mock 外部依赖
        single { scope }
        single { UserStreamRegistry() }
        single { PushService(get(), get()) }
        single { listOf<SendMessageStep>(mockk(), mockk(), mockk()) }
        single { SendMessageHandler(get(), get(), get(), get(), get()) }
        single { PullMessagesHandler(get(), get(), get()) }  // Phase 7: 新增第3参数 ConvMemberRepo
        single { ReadReportHandler(get(), get(), get(), get()) }

        // Phase 7: Conversation — 使用 mock 外部依赖
        single { ConversationLockManager() }
        single { ListConversationsHandler(get(), get()) }
        single { GroupMembersHandler(get(), get()) }
        single { EditGroupHandler(get(), get(), get()) }
        single { CreateGroupHandler(get(), get(), get(), get(), get()) }
        single { InviteMemberHandler(get(), get(), get(), get(), get()) }
        single { LeaveGroupHandler(get(), get(), get(), get(), get()) }
        single { KickMemberHandler(get(), get(), get(), get(), get()) }
    }

    @AfterEach
    fun tearDown() {
        // Review 修复：显式清理 Koin，防止测试间 Koin 状态污染
        stopKoin()
    }

    // ========== 框架组件解析测试 ==========

    @Test
    fun `frameworkModule resolves HandlerRegistry`() {
        startKoin {
            modules(frameworkModule, buildExternalModule())
        }
        val handlerRegistry = GlobalContext.get().get<HandlerRegistry>()
        assertNotNull(handlerRegistry)
    }

    @Test
    fun `frameworkModule resolves ProtoCodec and dependencies`() {
        startKoin {
            modules(frameworkModule, buildExternalModule())
        }
        val handlerRegistry = GlobalContext.get().get<HandlerRegistry>()
        val protoCodec = GlobalContext.get().get<ProtoCodec>()
        assertNotNull(handlerRegistry)
        assertNotNull(protoCodec)
    }

    // ========== Phase 4 Handler 解析 ==========

    @Test
    fun `handlerModule resolves PingHandler`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val pingHandler = GlobalContext.get().get<PingHandler>()
        assertNotNull(pingHandler)
    }

    // ========== Phase 5 Handler 解析 ==========

    @Test
    fun `LoginHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<LoginHandler>() }
    }

    @Test
    fun `RegisterHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<RegisterHandler>() }
    }

    @Test
    fun `SearchUserHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<SearchUserHandler>() }
    }

    @Test
    fun `GetProfileHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<GetProfileHandler>() }
    }

    @Test
    fun `BatchGetUserHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<BatchGetUserHandler>() }
    }

    @Test
    fun `BatchGetStatusHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<BatchGetStatusHandler>() }
    }

    @Test
    fun `SetPrivacyHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<SetPrivacyHandler>() }
    }

    @Test
    fun `GetPrivacyHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<GetPrivacyHandler>() }
    }

    // ========== Phase 7 Handler 解析 ==========

    @Test
    fun `ListConversationsHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<ListConversationsHandler>() }
    }

    @Test
    fun `GroupMembersHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<GroupMembersHandler>() }
    }

    @Test
    fun `EditGroupHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<EditGroupHandler>() }
    }

    @Test
    fun `CreateGroupHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<CreateGroupHandler>() }
    }

    @Test
    fun `InviteMemberHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<InviteMemberHandler>() }
    }

    @Test
    fun `LeaveGroupHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<LeaveGroupHandler>() }
    }

    @Test
    fun `KickMemberHandler can be resolved from Koin`() {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        assertDoesNotThrow { GlobalContext.get().get<KickMemberHandler>() }
    }

    // ========== registerHandlers 功能验证 ==========

    @Test
    fun `registerHandlers registers all phase 5 methods`() = runTest {
        startKoin {
            modules(frameworkModule, buildHandlerModule(), buildExternalModule())
        }
        val registry = GlobalContext.get().get<HandlerRegistry>()
        val codec = GlobalContext.get().get<ProtoCodec>()
        val pingHandler = GlobalContext.get().get<PingHandler>()
        val loginHandler = GlobalContext.get().get<LoginHandler>()
        val registerHandler = GlobalContext.get().get<RegisterHandler>()
        val searchUserHandler = GlobalContext.get().get<SearchUserHandler>()
        val getProfileHandler = GlobalContext.get().get<GetProfileHandler>()
        val batchGetUserHandler = GlobalContext.get().get<BatchGetUserHandler>()
        val batchGetStatusHandler = GlobalContext.get().get<BatchGetStatusHandler>()
        val setPrivacyHandler = GlobalContext.get().get<SetPrivacyHandler>()
        val getPrivacyHandler = GlobalContext.get().get<GetPrivacyHandler>()
        val sendMessageHandler = GlobalContext.get().get<SendMessageHandler>()
        val pullMessagesHandler = GlobalContext.get().get<PullMessagesHandler>()
        val readReportHandler = GlobalContext.get().get<ReadReportHandler>()
        val listConversationsHandler = GlobalContext.get().get<ListConversationsHandler>()
        val groupMembersHandler = GlobalContext.get().get<GroupMembersHandler>()
        val editGroupHandler = GlobalContext.get().get<EditGroupHandler>()
        val createGroupHandler = GlobalContext.get().get<CreateGroupHandler>()
        val inviteMemberHandler = GlobalContext.get().get<InviteMemberHandler>()
        val leaveGroupHandler = GlobalContext.get().get<LeaveGroupHandler>()
        val kickMemberHandler = GlobalContext.get().get<KickMemberHandler>()

        registerHandlers(
            registry, codec,
            pingHandler, loginHandler, registerHandler, searchUserHandler,
            getProfileHandler, batchGetUserHandler, batchGetStatusHandler,
            setPrivacyHandler, getPrivacyHandler,
            sendMessageHandler, pullMessagesHandler, readReportHandler,
            listConversationsHandler, groupMembersHandler, editGroupHandler,
            createGroupHandler, inviteMemberHandler, leaveGroupHandler, kickMemberHandler
        )

        // 验证所有 Phase 5~6 方法已注册
        assertNotNull(registry.get("system/ping"))
        assertSame(pingHandler, registry.get("system/ping")?.handler)

        assertNotNull(registry.get("user/login"))
        assertSame(loginHandler, registry.get("user/login")?.handler)

        assertNotNull(registry.get("user/register"))
        assertNotNull(registry.get("user/search"))
        assertNotNull(registry.get("user/getProfile"))
        assertNotNull(registry.get("user/batchGet"))
        assertNotNull(registry.get("user/batchGetStatus"))
        assertNotNull(registry.get("user/setPrivacy"))
        assertNotNull(registry.get("user/getPrivacy"))

        // Phase 6: Chat & Message Handler 注册验证
        assertNotNull(registry.get("chat/send"))
        assertSame(sendMessageHandler, registry.get("chat/send")?.handler)
        assertNotNull(registry.get("message/pull"))
        assertSame(pullMessagesHandler, registry.get("message/pull")?.handler)
        assertNotNull(registry.get("message/read"))
        assertSame(readReportHandler, registry.get("message/read")?.handler)

        // Phase 7: Conversation Handler 注册验证
        assertNotNull(registry.get("conversation/list"))
        assertSame(listConversationsHandler, registry.get("conversation/list")?.handler)
        assertNotNull(registry.get("conversation/group_members"))
        assertSame(groupMembersHandler, registry.get("conversation/group_members")?.handler)
        assertNotNull(registry.get("conversation/edit_group_info"))
        assertNotNull(registry.get("conversation/create_group"))
        assertNotNull(registry.get("conversation/invite_member"))
        assertNotNull(registry.get("conversation/leave_group"))
        assertNotNull(registry.get("conversation/kick_member"))
    }
}
