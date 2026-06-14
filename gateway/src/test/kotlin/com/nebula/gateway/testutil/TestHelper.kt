package com.nebula.gateway.testutil

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.nebula.chat.Request
import com.nebula.gateway.codec.ProtoCodec
import com.nebula.gateway.dispatcher.Dispatcher
import com.nebula.gateway.dispatcher.HandlerEntry
import com.nebula.gateway.dispatcher.HandlerRegistry
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.SessionKey
import com.nebula.gateway.handler.conversation.ConversationLockManager
import com.nebula.gateway.interceptor.AuthInterceptor
import com.nebula.gateway.interceptor.ExceptionInterceptor
import com.nebula.gateway.interceptor.LogInterceptor
import com.nebula.gateway.interceptor.RateLimitInterceptor
import com.nebula.gateway.session.Session
import com.nebula.gateway.session.SessionRegistry
import com.nebula.repository.entity.ConversationEntity
import com.nebula.repository.entity.ConversationMemberEntity
import com.nebula.repository.entity.FriendRequestEntity
import com.nebula.repository.entity.FriendshipEntity
import com.nebula.repository.entity.UserEntity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// ═══════════════════════════════════════════════════════════════
// 1. Session 工厂
// ═══════════════════════════════════════════════════════════════

/** 默认测试 Session：userId=1001, token="token-x" */
val DEFAULT_SESSION = Session(1001L, "token-x", "MOBILE", "dev-1", "conn-1")

/**
 * 创建测试 Session。
 *
 * @param userId 用户 ID
 * @param token 会话 Token
 * @param deviceType 设备类型
 * @param deviceId 设备标识
 * @param connectionId 连接标识
 * @return 测试用的 Session 实例
 */
fun testSession(
    userId: Long = 1001L,
    token: String = "token-x",
    deviceType: String = "MOBILE",
    deviceId: String = "dev-1",
    connectionId: String = "conn-1"
): Session = Session(userId, token, deviceType, deviceId, connectionId)

// ═══════════════════════════════════════════════════════════════
// 2. Session 注入辅助
// ═══════════════════════════════════════════════════════════════

/**
 * 构建携带 Session 的协程上下文。
 *
 * 用于 `runTest(sessionContext())` 或 `withContext(sessionContext())`。
 *
 * @param session 要注入的 Session，默认 [DEFAULT_SESSION]
 * @return 包含 SessionKey 的 CoroutineContext
 */
fun sessionContext(session: Session = DEFAULT_SESSION): CoroutineContext =
    EmptyCoroutineContext + SessionKey(session)

/**
 * 在 Session 协程上下文中执行代码块。
 *
 * 等价于 `withContext(SessionKey(session)) { block() }`。
 *
 * @param session 要注入的 Session，默认 [DEFAULT_SESSION]
 * @param block 待执行的代码块
 * @return 代码块返回值
 */
suspend fun <T> withSession(session: Session = DEFAULT_SESSION, block: suspend () -> T): T =
    kotlinx.coroutines.withContext(SessionKey(session)) { block() }

// ═══════════════════════════════════════════════════════════════
// 3. 通用 Mock 工厂
// ═══════════════════════════════════════════════════════════════

/**
 * 创建 Mock ConversationLockManager。
 *
 * `withLock()` 直接执行传入的代码块，跳过真实互斥锁逻辑。
 *
 * @return Mock 的 ConversationLockManager
 */
fun mockLockManager(): ConversationLockManager {
    val lockManager = mockk<ConversationLockManager>()
    coEvery { lockManager.withLock(any(), any<suspend () -> Any>()) } coAnswers {
        @Suppress("UNCHECKED_CAST")
        (args[1] as suspend () -> Any).invoke()
    }
    return lockManager
}

/**
 * 创建 Mock TransactionTemplate。
 *
 * `execute()` 在模拟事务中执行回调，跳过真实事务提交。
 *
 * @return Mock 的 TransactionTemplate
 */
fun mockTransactionTemplate(): TransactionTemplate {
    val transactionTemplate = mockk<TransactionTemplate>()
    every { transactionTemplate.execute<Any?>(any()) } answers {
        @Suppress("UNCHECKED_CAST")
        (args[0] as TransactionCallback<Any?>).doInTransaction(mockk(relaxed = true))
    }
    return transactionTemplate
}

// 注意：Repository save 返回自身的 mock（answers { firstArg() }）无法提取为独立函数，
// 因为 MockK 的 firstArg() 是 MockKMatcherScope 的扩展函数，只能在 answers {} 块内调用。
// 请在各测试中直接使用：every { repo.save(any<T>()) } answers { firstArg() }

// ═══════════════════════════════════════════════════════════════
// 4. 集成测试辅助（Dispatcher 构建器）
// ═══════════════════════════════════════════════════════════════

/**
 * 构建 HandlerEntry。
 *
 * 封装 ProtoCodec 的 codec 创建逻辑，简化 Handler 注册。
 *
 * @param handler Handler 实例
 * @param reqClass 请求消息的 KClass
 * @param respClass 响应消息的 KClass
 * @return 配置好的 HandlerEntry
 */
fun <Req : Any, Resp : Any> handlerEntry(
    handler: Handler<Req, Resp>,
    reqClass: kotlin.reflect.KClass<Req>,
    respClass: kotlin.reflect.KClass<Resp>
): HandlerEntry {
    val reqCodec = ProtoCodec.buildCodec(reqClass)
    val respCodec = ProtoCodec.buildCodec(respClass)
    return HandlerEntry(
        handler = handler,
        reqClass = reqClass,
        respClass = respClass,
        parseFrom = reqCodec.parseFrom,
        toByteArray = respCodec.toByteArray
    )
}

/**
 * 构建 Request Envelope。
 *
 * @param method 请求方法名（如 "chat/send"）
 * @param params 请求参数的 ByteString 序列化，默认空
 * @return Protobuf Request
 */
fun requestEnvelope(method: String, params: ByteString = ByteString.EMPTY): Request =
    Request.newBuilder().setMethod(method).setParams(params).build()

/**
 * 构建带认证拦截器的完整 Dispatcher。
 *
 * 自动创建 AuthInterceptor 匿名类提取固定 token，
 * 并配置标准 Interceptor Pipeline：
 * [AuthInterceptor, LogInterceptor, RateLimitInterceptor, ExceptionInterceptor]
 *
 * @param registry 已注册 Handler 的 HandlerRegistry
 * @param session 测试 Session，决定认证身份
 * @param sessionRegistry Mock 的 SessionRegistry，默认自动创建
 * @param skipMethods AuthInterceptor 跳过的方法集合
 * @return 配置好的 Dispatcher
 */
fun buildTestDispatcher(
    registry: HandlerRegistry,
    session: Session = DEFAULT_SESSION,
    sessionRegistry: SessionRegistry = mockk(),
    skipMethods: Set<String> = setOf("system/ping")
): Dispatcher {
    val authInterceptor = object : AuthInterceptor(sessionRegistry, skipMethods = skipMethods) {
        override fun extractToken(request: Request): String? = session.token
    }
    coEvery { sessionRegistry.validate(session.token) } returns session

    val interceptors = listOf(
        authInterceptor,
        LogInterceptor(),
        RateLimitInterceptor(),
        ExceptionInterceptor()
    )
    return Dispatcher(registry, interceptors)
}

/**
 * 构建只注册一个 Handler 的 Dispatcher，用于冒烟测试。
 *
 * 自动完成 HandlerEntry 创建和注册，简化单个 Handler 的冒烟测试。
 *
 * @param handler Handler 实例
 * @param reqClass 请求类型
 * @param respClass 响应类型
 * @param session 测试 Session，默认 [DEFAULT_SESSION]
 * @param sessionRegistry Mock 的 SessionRegistry，默认自动创建
 * @return 配置好的 Dispatcher
 */
fun <Req : Any, Resp : Any> singleHandlerDispatcher(
    handler: Handler<Req, Resp>,
    reqClass: kotlin.reflect.KClass<Req>,
    respClass: kotlin.reflect.KClass<Resp>,
    session: Session = DEFAULT_SESSION,
    sessionRegistry: SessionRegistry = mockk()
): Dispatcher = buildTestDispatcher(
    HandlerRegistry().apply { register(handlerEntry(handler, reqClass, respClass)) },
    session = session, sessionRegistry = sessionRegistry
)

/**
 * 一键创建带认证和 Handler 注册的 Dispatcher。
 *
 * 等价于手动调用 [handlerEntry] 注册 Handler 后调用 [buildTestDispatcher]。
 *
 * 用法：
 * ```
 * val dispatcher = createDispatcher(
 *     entry(myHandler, MyReq::class, MyResp::class)
 * )
 * ```
 *
 * @param entries 要注册的 HandlerEntry 列表
 * @param session 测试 Session
 * @param sessionRegistry Mock 的 SessionRegistry
 * @return 配置好的 Dispatcher
 */
fun createDispatcher(
    vararg entries: HandlerEntry,
    session: Session = DEFAULT_SESSION,
    sessionRegistry: SessionRegistry = mockk()
): Dispatcher {
    val registry = HandlerRegistry()
    entries.forEach { registry.register(it) }
    return buildTestDispatcher(registry, session, sessionRegistry)
}

// ═══════════════════════════════════════════════════════════════
// 5. 测试数据工厂
// ═══════════════════════════════════════════════════════════════

/**
 * 创建测试 UserEntity。
 *
 * @param userId 用户 ID
 * @param username 用户名
 * @param nickname 显示名称
 * @return 测试用的 UserEntity，已设置 id
 */
fun testUser(userId: Long, username: String = "user$userId", nickname: String = "用户$userId"): UserEntity {
    return UserEntity(
        username = username,
        passwordHash = "hash",
        nickname = nickname
    ).apply { id = userId }
}

/**
 * 创建测试 ConversationEntity（群聊类型）。
 *
 * @param convId 会话 ID（UUID 格式）
 * @param name 群聊名称
 * @param ownerUid 群主用户 ID
 * @param memberCount 成员数量，默认 3
 * @return 测试用的 ConversationEntity，已设置 id、createdAt、updatedAt
 */
fun testConversation(
    convId: String,
    name: String = "测试群聊",
    ownerUid: Long = 1001L,
    memberCount: Int = 3
): ConversationEntity {
    return ConversationEntity(
        type = 2,
        name = name,
        avatar = "",
        groupOwnerUid = ownerUid,
        memberCount = memberCount,
        maxMembers = 200,
        status = 0
    ).apply {
        id = convId
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
    }
}

/**
 * 创建测试 ConversationMemberEntity。
 *
 * @param convId 会话 ID
 * @param userId 用户 ID
 * @param role 成员角色："owner" 或 "member"
 * @return 测试用的 ConversationMemberEntity，已设置 id、joinedAt
 */
fun testMember(convId: String, userId: Long, role: String = "member"): ConversationMemberEntity {
    return ConversationMemberEntity(
        conversationId = convId,
        userId = userId,
        role = role
    ).apply {
        id = userId
        joinedAt = LocalDateTime.now()
    }
}

/**
 * 创建测试 FriendRequestEntity。
 *
 * @param fromUid 发起方用户 ID
 * @param toUid 接收方用户 ID
 * @param status 申请状态：0=pending 1=accepted 2=rejected，默认 0
 * @param message 申请附言，默认空字符串
 * @param id 申请 ID（数据库自增主键），默认 null（不设置）
 * @return 测试用的 FriendRequestEntity，已设置 createdAt、updatedAt
 */
fun testFriendRequest(
    fromUid: Long,
    toUid: Long,
    status: Int = 0,
    message: String = "",
    id: Long? = null
): FriendRequestEntity {
    return FriendRequestEntity(
        fromUid = fromUid,
        toUid = toUid,
        status = status,
        message = message
    ).apply {
        this.id = id
        createdAt = LocalDateTime.now()
        updatedAt = LocalDateTime.now()
    }
}

/**
 * 创建测试 FriendshipEntity。
 *
 * @param userId 用户 ID（排序后的较小值）
 * @param friendId 好友 ID（排序后的较大值）
 * @param deleted 软删除标记：0=正常 1=已删除，默认 0
 * @return 测试用的 FriendshipEntity，已设置 createdAt
 */
fun testFriendship(
    userId: Long,
    friendId: Long,
    deleted: Int = 0
): FriendshipEntity {
    return FriendshipEntity(
        userId = userId,
        friendId = friendId
    ).apply {
        this.deleted = deleted
        createdAt = LocalDateTime.now()
    }
}

/**
 * 构造排序后的私聊会话 ID。
 *
 * 返回格式 `"private:$smaller:$larger"`，确保双方生成的 ID 一致。
 * 等价于 [com.nebula.gateway.handler.friend.FriendAddHandler.buildPrivateConvId]。
 *
 * @param uid1 用户 A
 * @param uid2 用户 B
 * @return 排序后的私聊会话 ID
 */
fun testPrivateConvId(uid1: Long, uid2: Long): String {
    val smaller = minOf(uid1, uid2)
    val larger = maxOf(uid1, uid2)
    return "private:$smaller:$larger"
}

// ═══════════════════════════════════════════════════════════════
// 6. Dispatcher 扩展方法 — 简化 dispatch 调用
// ═══════════════════════════════════════════════════════════════

/**
 * 通过 Dispatcher 发送请求并获取响应。
 *
 * 直接调用 [Dispatcher.dispatch]，由 AuthInterceptor 负责 Session 注入。
 * 调用方需确保 [buildTestDispatcher] 的 [SessionRegistry] 已正确 mock。
 *
 * @param method 请求方法名
 * @param params Protobuf 请求消息（自动调用 toByteString）
 * @return Dispatcher 返回的 Response
 */
suspend fun Dispatcher.dispatchAs(
    method: String,
    params: MessageLite
): com.nebula.chat.Response =
    dispatch(requestEnvelope(method, params.toByteString()))
