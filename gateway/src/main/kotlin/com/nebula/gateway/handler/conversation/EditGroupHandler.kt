package com.nebula.gateway.handler.conversation

import com.nebula.chat.PushEventType
import com.nebula.chat.Response
import com.nebula.chat.conversation.EditGroupReq
import com.nebula.chat.conversation.GroupUpdatedPayload
import com.nebula.common.BizCode
import com.nebula.common.exception.ConversationException
import com.nebula.gateway.handler.Handler
import com.nebula.gateway.handler.requireSession
import com.nebula.gateway.push.PushService
import com.nebula.repository.repository.ConversationMemberRepository
import com.nebula.repository.repository.ConversationRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/**
 * 编辑群信息 Handler — method = "conversation/edit_group_info"（D-15）。
 *
 * 仅群主可以修改群名称/头像（D-15），至少传一个修改项。
 * name ≤128 字符，avatar_url ≤256 字符。
 * 修改后异步推送 GROUP_UPDATED 给所有成员。
 * 单表更新，无需事务包裹（D-19 非多表操作）。
 *
 * @param conversationRepository 会话数据仓库
 * @param conversationMemberRepository 会话成员数据仓库
 * @param pushService 推送服务
 */
class EditGroupHandler(
    private val conversationRepository: ConversationRepository,
    private val conversationMemberRepository: ConversationMemberRepository,
    private val pushService: PushService
) : Handler<EditGroupReq, Response> {

    override val method: String = "conversation/edit_group_info"

    companion object {
        private val logger = KotlinLogging.logger {}
        /** 群聊解散状态常量 */
        private const val STATUS_DISSOLVED = 1
        /** 群主角色常量 */
        private const val ROLE_OWNER = "owner"
        private const val MAX_NAME_LEN = 128
        private const val MAX_AVATAR_LEN = 256
    }

    override suspend fun handle(req: EditGroupReq): Response {
        val session = currentCoroutineContext().requireSession()

        // 至少传 name 或 avatar_url
        val hasName = req.hasName() && req.name.isNotBlank()
        val hasAvatar = req.hasAvatarUrl() && req.avatarUrl.isNotBlank()
        if (!hasName && !hasAvatar) {
            throw ConversationException(BizCode.INVALID_PARAM, "至少修改群名称或头像")
        }

        // name 长度校验
        if (hasName && req.name.length > MAX_NAME_LEN) {
            throw ConversationException(BizCode.INVALID_PARAM, "群名称不能超过${MAX_NAME_LEN}个字符")
        }

        // avatar_url 长度校验
        if (hasAvatar && req.avatarUrl.length > MAX_AVATAR_LEN) {
            throw ConversationException(BizCode.INVALID_PARAM, "头像 URL 不能超过${MAX_AVATAR_LEN}个字符")
        }

        // 获取会话信息
        val conversation = withContext(Dispatchers.IO) {
            conversationRepository.findById(req.conversationId).orElse(null)
        } ?: throw ConversationException(BizCode.CONV_NOT_FOUND, "会话不存在")

        // 验证会话未解散
        if (conversation.status == STATUS_DISSOLVED) {
            throw ConversationException(BizCode.GROUP_DISSOLVED, "群聊已解散")
        }

        // 验证请求者是群主
        val selfMember = withContext(Dispatchers.IO) {
            conversationMemberRepository.findByConversationIdAndUserId(req.conversationId, session.userId)
        } ?: throw ConversationException(BizCode.NOT_MEMBER, "不是会话成员")

        if (selfMember.role != ROLE_OWNER) {
            throw ConversationException(BizCode.GROUP_PERM_DENIED, "仅群主可修改群信息")
        }

        // 单表更新（无需事务包裹，D-19）
        withContext(Dispatchers.IO) {
            val entity = conversationRepository.findById(req.conversationId).get()
            if (hasName) entity.name = req.name
            if (hasAvatar) entity.avatar = req.avatarUrl
            entity.updatedAt = LocalDateTime.now()
            conversationRepository.save(entity)
        }

        // 异步推送 GROUP_UPDATED（D-15）
        val payload = GroupUpdatedPayload.newBuilder().apply {
            conversationId = req.conversationId
            if (hasName) name = req.name
            if (hasAvatar) avatarUrl = req.avatarUrl
        }.build()
        pushService.pushConversationEvent(
            convId = req.conversationId,
            eventType = PushEventType.GROUP_UPDATED,
            payloadBytes = payload.toByteString()
        )

        return Response.newBuilder()
            .setCode(BizCode.OK.code)
            .setMsg("ok")
            .setMethod(method)
            .build()
    }
}
