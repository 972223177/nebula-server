package com.nebula.service.init

import com.nebula.common.init.DeadLetterCallback
import com.nebula.service.admin.DeadLetterService
import com.nebula.service.chat.MessageService
import com.nebula.service.conversation.ConversationService
import com.nebula.service.friend.FriendService
import com.nebula.service.sequence.SeqService
import com.nebula.service.user.OnlineStatusService
import com.nebula.service.user.UserPrivacyService
import com.nebula.service.user.UserService
import org.koin.dsl.module

/**
 * Service 层 Koin 模块 — 注册所有业务服务实例。
 *
 * 放置于 service 模块而非 gateway 模块，因为 service 构造函数的依赖
 * 包含 repository 层类型，gateway 层不应可见（D-28 分层架构）。
 *
 * 所有需要 MySQL 写入/读取的服务现在都通过 [com.nebula.repository.dao.JpaTxRunner] 承载事务。
 */
val serviceKoinModule = module {
    single { UserService(get(), get(), get(), get()) }
    single { UserPrivacyService(get(), get()) }
    single { OnlineStatusService(get()) }
    single { MessageService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { ConversationService(get(), get(), get(), get()) }
    single { FriendService(get(), get(), get(), get(), get(), get(), get(), get()) }
    single { DeadLetterService(get(), get(), get(), get()) }
    single<DeadLetterCallback> { get<DeadLetterService>() }
    single { SeqService(get()) }
}
