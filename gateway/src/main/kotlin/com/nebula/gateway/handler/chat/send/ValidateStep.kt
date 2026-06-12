package com.nebula.gateway.handler.chat.send

import com.nebula.common.BizCode
import com.nebula.repository.repository.ConversationMemberRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 验证 Step — 校验消息参数和发送者成员身份（D-08, D-13, D-14）。
 *
 * 职责：
 * - 消息内容非空校验（D-14）
 * - client_message_id 非空校验（D-14）
 * - 发送者是否为会话成员验证（D-08）
 *
 * 任一校验失败时抛出 [SendMessageException]，由 ExceptionInterceptor 统一捕获。
 *
 * @param conversationMemberRepository 会话成员查询接口
 */
class ValidateStep(
    private val conversationMemberRepository: ConversationMemberRepository
) : SendMessageStep {

    /**
     * 执行验证逻辑。
     *
     * @param context Step 链共享上下文
     * @return true 验证通过，继续下一步
     * @throws SendMessageException 当任一验证失败时
     */
    override suspend fun execute(context: SendContext): Boolean {
        val req = context.req

        // D-14: 消息内容非空校验
        if (req.content.isBlank()) {
            throw SendMessageException(BizCode.INVALID_PARAM, "消息内容不能为空")
        }

        // D-14: client_message_id 非空校验
        if (req.clientMessageId.isBlank()) {
            throw SendMessageException(BizCode.INVALID_PARAM, "client_message_id 不能为空")
        }

        // D-08: 发送者成员身份验证
        val member = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(
                req.conversationId, context.senderUid
            )
        }
        if (member == null) {
            throw SendMessageException(BizCode.NOT_MEMBER, "发送者不是会话成员")
        }

        return true
    }

    companion object {
        private val logger = KotlinLogging.logger {}
    }
}
