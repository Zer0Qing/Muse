package io.zer0.muse.web

import io.zer0.ai.core.ProviderConfig
import io.zer0.common.AppJson
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.SessionEntity
import kotlinx.serialization.decodeFromString

/**
 * 只读命令的载荷投影。
 *
 * 只做「仓储数据 → 协议 DTO」的映射,不读 flow、不碰仓库:空列表/空会话在这里
 * 退化成空数组或带 error 的事件,而不是抛异常。命令分支拿到数据后调这里。
 */
internal object HostReadCommands {

    const val TYPE_ASSISTANTS = "assistants.list"
    const val TYPE_ASSISTANT_DETAIL = "assistants.detail"
    const val TYPE_PROVIDERS = "providers.list"
    const val TYPE_SESSION_DETAIL = "sessions.detail"

    /** 会话不存在时的 error 值。 */
    const val ERROR_SESSION_NOT_FOUND = "session_not_found"

    /** 助手不存在时的 error 值。 */
    const val ERROR_ASSISTANT_NOT_FOUND = "assistant_not_found"

    /**
     * 人设摘要的字符上限。
     *
     * 人设块是自由文本,用户写到几千字很常见;详情页只展示开头几行,整段下发会把回执
     * 撑大且没有展示位。只留开头而不是保头保尾:人设是从第一句开始读的(身份 → 关系 →
     * 说话方式),头尾拼起来会把两段不相干的话粘在一起,读着像换了个人。
     */
    private const val PERSONA_SUMMARY_LIMIT = 500

    /**
     * 助手一览。
     *
     * 头像走与消息图片同一套媒体路由：手机本地路径（`file://…/avatar/…`）浏览器取不到，
     * 换成 `/api/media/<token>`；`http(s)` 原样透传；`android.resource://`、内联 base64 这类
     * 无法变成浏览器可读地址的给 null，前端退回首字。
     */
    fun assistantsEvent(
        assistants: List<AssistantEntity>,
        defaultAssistantId: String?,
        requestId: String?,
        mediaLinks: HostMediaLinks,
    ): HostEvent = HostEvent(
        type = TYPE_ASSISTANTS,
        requestId = requestId,
        assistants = assistants.map { assistant ->
            HostAssistant(
                id = assistant.id,
                name = assistant.name,
                avatarUri = mediaLinks.fileOrHttpLink(assistant.avatarImageUrl),
                isDefault = assistant.id == defaultAssistantId,
            )
        },
    )

    /**
     * 单个助手的详情。
     *
     * 头像与 [assistantsEvent] 同一套媒体路由:本机路径换成 `/api/media/<token>`,
     * `http(s)` 原样透传,转不了的给 null。
     *
     * 提示词只给字数([HostAssistantDetail.systemPromptLength]),正文不下发;
     * 人设只给截断后的摘要([HostAssistantDetail.personaSummary]),截断口径见
     * [PERSONA_SUMMARY_LIMIT]。
     */
    fun assistantDetailEvent(
        assistant: AssistantEntity?,
        requestId: String?,
        mediaLinks: HostMediaLinks,
    ): HostEvent {
        if (assistant == null) {
            return HostEvent(type = TYPE_ASSISTANT_DETAIL, requestId = requestId, error = ERROR_ASSISTANT_NOT_FOUND)
        }
        return HostEvent(
            type = TYPE_ASSISTANT_DETAIL,
            requestId = requestId,
            assistantDetail = HostAssistantDetail(
                id = assistant.id,
                name = assistant.name,
                avatarUri = mediaLinks.fileOrHttpLink(assistant.avatarImageUrl),
                description = assistant.summary,
                modelId = assistant.modelId,
                providerId = assistant.providerId,
                temperature = assistant.temperature,
                topP = assistant.topP,
                maxTokens = assistant.maxTokens,
                personaSummary = prefixOf(assistant.personaDefinition.trim(), PERSONA_SUMMARY_LIMIT),
                personaTruncated = assistant.personaDefinition.trim().length > PERSONA_SUMMARY_LIMIT,
                systemPromptLength = assistant.systemPrompt.length,
                toolCount = toolCountOf(assistant),
                contextMessageSize = assistant.contextMessageSize,
                reasoningLevel = assistant.reasoningLevel,
                enabled = assistant.enabled,
            ),
        )
    }

    /** 头部截断,不切断代理对(emoji 等). */
    private fun prefixOf(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val end = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, end)
    }

    /** 勾选的工具数量。[AssistantEntity.toolIdsJson] 坏掉时按 0 算,不让一条脏 JSON 拖垮整个详情。 */
    private fun toolCountOf(assistant: AssistantEntity): Int =
        runCatching { AppJson.decodeFromString<List<String>>(assistant.toolIdsJson) }.getOrDefault(emptyList()).size

    /**
     * 供应商与模型一览。
     *
     * 过滤 [ProviderConfig.hiddenFromSettings]:这类内部供应商本来就不在 App 的供应商设置列表里,
     * 网页端看到只会是重复项。
     */
    fun providersEvent(
        providers: List<ProviderConfig>,
        selectedModelId: String?,
        requestId: String?,
    ): HostEvent = HostEvent(
        type = TYPE_PROVIDERS,
        requestId = requestId,
        selectedModelId = selectedModelId,
        providers = providers.filterNot { it.hiddenFromSettings }.map { provider ->
            HostProvider(
                id = provider.id,
                name = provider.displayName,
                enabled = provider.enabled,
                models = provider.models.map { model -> HostProviderModel(id = model.id, name = model.name) },
            )
        },
    )

    /** 会话元信息;[session] 为空时返回带 [ERROR_SESSION_NOT_FOUND] 的空载荷事件。 */
    fun sessionDetailEvent(
        session: SessionEntity?,
        messageCount: Int,
        requestId: String?,
    ): HostEvent {
        if (session == null) {
            return HostEvent(type = TYPE_SESSION_DETAIL, requestId = requestId, error = ERROR_SESSION_NOT_FOUND)
        }
        return HostEvent(
            type = TYPE_SESSION_DETAIL,
            requestId = requestId,
            sessionId = session.id,
            sessionDetail = HostSessionDetail(
                id = session.id,
                title = session.title,
                assistantId = session.assistantId,
                archived = session.archived,
                createdAt = session.createdAt,
                updatedAt = session.updatedAt,
                messageCount = messageCount,
            ),
        )
    }
}
