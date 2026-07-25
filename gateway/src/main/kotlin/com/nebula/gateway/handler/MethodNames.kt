/**
 * 全量业务 method 路由字符串的单一来源（按 method 的 group 段嵌套分组，D-77, D-30）。
 *
 * 职责：
 * - 消除 Handler 的 `override val method`、AuthInterceptor/FrameworkModule 的 skipMethods、
 *   LoginBindingInterceptor 的分支匹配、各类测试中 `setMethod`/`registry.get` 的硬编码字符串散落，
 *   统一收口到此处，杜绝拼写漂移。
 * - 为未来的 IdempotencyInterceptor 声明式注册表（Map<method, IdempotencyConfig>）与白名单短路
 *   提供唯一可引用的 method 来源。
 *
 * 分组约定：每个嵌套 object 对应 method 字符串的 `group/` 段（如 Chat ↔ `chat/`），
 * 其下常量名去掉 group 前缀（如 `chat/send` → Chat.SEND），避免 Chat.CHAT_SEND 式冗余。
 * 常量值必须与 proto 中定义的 method 字符串**逐字符一致**（含大小写）。
 * 前缀类匹配（skipMethods 前缀白名单）以各分组下的 `*_PREFIX` 常量提供（如 Admin.PREFIX）。
 */
package com.nebula.gateway.handler

/** method 路由字符串单一来源（按 group 段嵌套分组）。 */
object MethodNames {

    /** 系统类方法（system 组）。 */
    object System {
        const val PING = "system/ping"
        const val SENSITIVE_WORD_DOWNLOAD = "system/sensitive_word_download"
        /** skipMethods 前缀白名单：匹配 `system/sensitive_word*` 全部方法。 */
        const val SENSITIVE_WORD_PREFIX = "system/sensitive_word"
    }

    /** 用户类方法（user 组）。 */
    object User {
        const val LOGIN = "user/login"
        const val REGISTER = "user/register"
        const val LOGOUT = "user/logout"
        const val SEARCH = "user/search"
        const val GET_PROFILE = "user/getProfile"
        const val BATCH_GET = "user/batchGet"
        const val BATCH_GET_STATUS = "user/batchGetStatus"
        const val SET_PRIVACY = "user/setPrivacy"
        const val GET_PRIVACY = "user/getPrivacy"
    }

    /** 消息发送类方法（chat 组）。 */
    object Chat {
        const val SEND = "chat/send"
    }

    /** 消息读取/回执/序列号类方法（message 组）。 */
    object Message {
        const val PULL = "message/pull"
        const val READ = "message/read"
        const val SEQ = "message/seq"
        const val DELIVERY_ACK = "message/delivery_ack"
    }

    /** 会话/群组成员类方法（conversation 组）。 */
    object Conversation {
        const val LIST = "conversation/list"
        const val GROUP_LIST = "conversation/group_list"
        const val CREATE_PRIVATE = "conversation/create_private"
        const val DELETE = "conversation/delete"
        const val CREATE_GROUP = "conversation/create_group"
        const val EDIT_GROUP_INFO = "conversation/edit_group_info"
        const val INVITE_MEMBER = "conversation/invite_member"
        const val LEAVE_GROUP = "conversation/leave_group"
        const val KICK_MEMBER = "conversation/kick_member"
        const val GROUP_MEMBERS = "conversation/group_members"
    }

    /** 好友关系类方法（friend 组）。 */
    object Friend {
        const val ADD = "friend/add"
        const val ACCEPT = "friend/accept"
        const val REJECT = "friend/reject"
        const val DELETE = "friend/delete"
        const val LIST = "friend/list"
        const val REQUESTS = "friend/requests"
        const val CHECK = "friend/check"
        const val BATCH_CHECK = "friend/batchCheck"
    }

    /** 外部能力接入类方法（external 组）。 */
    object External {
        const val QUERY_WEATHER = "external/query_weather"
        const val WEB_SEARCH = "external/web_search"
    }

    /** 管理类方法（admin 组）。 */
    object Admin {
        const val DEAD_LETTERS = "admin/dead_letters"
        const val RETRY_DEAD_LETTER = "admin/retry_dead_letter"
        const val SENSITIVE_WORD_RELOAD = "admin/sensitive_word_reload"
        /** skipMethods 前缀白名单：匹配 `admin/` 全部方法（D-77）。 */
        const val PREFIX = "admin/"
    }
}
