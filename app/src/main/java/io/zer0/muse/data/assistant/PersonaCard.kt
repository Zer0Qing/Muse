package io.zer0.muse.data.assistant

import io.zer0.muse.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * v2.7.x(E3): 人设卡的严格 schema —— 导入/导出的唯一形状。
 *
 * 为什么不能继续用整份 [AssistantEntity]:
 *  `AssistantCardExporter.writeZip` 以前把实体**原样**序列化出去(注释自己写着「敏感字段不清除」),
 *  里面包括 `customHeadersJson`(常见放法是 Authorization 头)、`providerId` / `modelId` /
 *  `toolModelId`(指向本机加密存储的凭据)、以及 `messageCount` / `lastUsedAt` 这类使用统计。
 *  角色卡是会被转发到群聊里的东西 —— 那等于把「这台设备配过什么、用了多少」一起发出去。
 *  同时它用 `AppJson`(`ignoreUnknownKeys` + `coerceInputValues`)解析,一个拼错的字段会被
 *  静默接受成默认值:导入"成功",人设却是空的。
 *
 * 所以这里只放**人设本身**,并且用一份严格的解析配置:
 *  - 未知字段直接拒绝,不猜;
 *  - 类型不符直接拒绝,不悄悄降级;
 *  - 凭据、模型绑定、使用统计、会话内容一律不在 schema 里 —— 不是「导出时抹掉」,
 *    而是**根本没有地方放它们**。
 */
@Serializable
data class PersonaCard(
    /** 结构版本。不匹配直接拒绝,而不是尽力解释。 */
    @SerialName("schemaVersion") val schemaVersion: Int = VERSION,
    /** 固定标识,防止把别的 JSON(或旧版整份实体)误当成人设卡导入。 */
    @SerialName("kind") val kind: String = KIND,
    @SerialName("name") val name: String,
    @SerialName("summary") val summary: String = "",
    @SerialName("systemPrompt") val systemPrompt: String = "",
    @SerialName("personaDefinition") val personaDefinition: String = "",
    @SerialName("thinkingChain") val thinkingChain: String = "",
    @SerialName("messageTemplate") val messageTemplate: String = "",
    /** 开场白:它是人设的一部分,与"会话内容"不同(由作者撰写,不是聊天记录)。 */
    @SerialName("presetMessagesJson") val presetMessagesJson: String = "[]",
    @SerialName("avatarEmoji") val avatarEmoji: String = "",
    @SerialName("tagsJson") val tagsJson: String = "[]",
    @SerialName("capabilitiesJson") val capabilitiesJson: String = "[]",
    @SerialName("temperature") val temperature: Float? = null,
    @SerialName("topP") val topP: Float? = null,
    @SerialName("maxTokens") val maxTokens: Int? = null,
    @SerialName("contextMessageSize") val contextMessageSize: Int = 20,
    @SerialName("reasoningLevel") val reasoningLevel: String = "AUTO",
    @SerialName("streamOutput") val streamOutput: Boolean = true,
    @SerialName("memoryEnabled") val memoryEnabled: Boolean = true,
    @SerialName("useGlobalMemory") val useGlobalMemory: Boolean = false,
    @SerialName("enableRecentChatsReference") val enableRecentChatsReference: Boolean = true,
    @SerialName("enableTimeReminder") val enableTimeReminder: Boolean = true,
    @SerialName("useAssistantName") val useAssistantName: Boolean = false,
    @SerialName("allowGroupChat") val allowGroupChat: Boolean = true,
    @SerialName("regexRulesJson") val regexRulesJson: String = "[]",
    /** 绑定的**名称**引用(工具/知识库等),不是 id:id 是设备本地的,跨设备毫无意义。 */
    @SerialName("bindingHints") val bindingHints: List<String> = emptyList(),
) {
    companion object {
        const val VERSION = 1
        const val KIND = "muse.persona"
    }
}

/** 解析结果。失败原因只说字段与规则,不回显内容(人设正文可能包含用户信息)。 */
sealed interface PersonaParse {
    @JvmInline
    value class Ok(val card: PersonaCard) : PersonaParse

    /** [reason] 面向用户;[field] 给日志与测试定位用。 */
    data class Invalid(val reason: String, val field: String? = null) : PersonaParse
}

object PersonaCardCodec {

    /**
     * 严格解析配置:未知键与类型不符都要失败。
     *
     * 刻意不复用 `AppJson` —— 那份配置为「宽容读取存量数据」而设计,
     * 而导入外部文件要的是相反的东西:宁可拒绝,也不要"看起来成功了"。
     */
    private val strict = Json {
        ignoreUnknownKeys = false
        coerceInputValues = false
        explicitNulls = true
        isLenient = false
    }

    /**
     * 人设正文各字段的长度上限。超限拒绝,而不是截断 —— 截断会静默改变人设。
     *
     * 但「一律拒绝」有个死结：一份本来就超过这个数的卡，导入时被拒，
     * 用户此后**永远改不动它**（连删减都存不进去），只能放弃这份人设。
     * 所以导入路径用一个更宽的上限（[IMPORT_MAX_TEXT_CHARS]）：
     * 只挡住真正会撑爆存储的极端值，正常超限的卡先让它进来、由用户自己删减。
     */
    private const val MAX_TEXT_CHARS = 200_000

    /** 导入时用的宽上限：只挡异常体积，不因「略超常规上限」就把整份人设拒之门外。 */
    internal const val IMPORT_MAX_TEXT_CHARS = 2_000_000
    private const val MAX_NAME_CHARS = 80

    fun encode(card: PersonaCard): String = strict.encodeToString(card)

    /**
     * @param maxTextChars 正文各字段允许的最大长度。默认按常规上限校验；
     *        导入外部卡片时传 [IMPORT_MAX_TEXT_CHARS]，避免「本来就超限的卡永远进不来」。
     */
    @Suppress("TooGenericExceptionCaught") // 序列化失败的种类太多(键、类型、编码),任何一种都必须变成 Invalid,而不是崩到调用方
    fun decode(text: String, maxTextChars: Int = MAX_TEXT_CHARS): PersonaParse {
        val card = try {
            strict.decodeFromString(PersonaCard.serializer(), text)
        } catch (error: Exception) {
            // kotlinx 的消息里可能带片段正文,只取"哪个字段"这一层信息。
            val field = Regex(""""(\w+)""").find(error.message.orEmpty())?.groupValues?.getOrNull(1)
            return PersonaParse.Invalid(
                reason = "人设文件不符合结构要求:未知字段、缺必填项或类型不符",
                field = field,
            )
        }
        return validate(card, maxTextChars) ?: PersonaParse.Ok(card)
    }

    /**
     * 结构对了之后再核内容:结构合法不等于内容可用。
     *
     * 规则排成一张表而不是四段 if —— 这些规则会一条条长出来(名称长度、正文上限,
     * 以后可能还有「开场白必须是合法 JSON 数组」)。写成 if 链每加一条就多一次 return;
     * 规则表只加一行,而且列表顺序就是判定顺序。
     */
    private fun validate(card: PersonaCard, maxTextChars: Int = MAX_TEXT_CHARS): PersonaParse.Invalid? {
        val broken = rules.firstOrNull { it.appliesTo(card, maxTextChars) } ?: return null
        return PersonaParse.Invalid(broken.reason(card), broken.field(card))
    }

    private class Rule(
        val appliesTo: (PersonaCard, Int) -> Boolean,
        val reason: (PersonaCard) -> String,
        val field: (PersonaCard) -> String,
    )

    /** 哪个正文段超限了;都没超返回 null。 */
    private fun overLongField(card: PersonaCard, maxTextChars: Int): String? = listOf(
        card.systemPrompt to "systemPrompt",
        card.personaDefinition to "personaDefinition",
        card.thinkingChain to "thinkingChain",
        card.messageTemplate to "messageTemplate",
        card.presetMessagesJson to "presetMessagesJson",
    ).firstOrNull { (value, _) -> value.length > maxTextChars }?.second

    private val rules = listOf(
        Rule(
            { card, _ -> card.kind != PersonaCard.KIND },
            { "这份文件不是 Muse 人设卡(kind 不符)" },
            { "kind" },
        ),
        Rule(
            { card, _ -> card.schemaVersion != PersonaCard.VERSION },
            { "人设结构版本是 ${it.schemaVersion},本机只认 ${PersonaCard.VERSION}" },
            { "schemaVersion" },
        ),
        Rule(
            { card, _ -> card.name.isBlank() || card.name.length > MAX_NAME_CHARS },
            { "名称必须存在且不超过 $MAX_NAME_CHARS 字" },
            { "name" },
        ),
        Rule(
            { card, limit -> overLongField(card, limit) != null },
            { "人设正文过长,已拒绝" },
            { "systemPrompt" },
        ),
    )

    /** 从本机实体取「可导出的那部分」。凭据与本地绑定不参与。 */
    fun fromEntity(entity: AssistantEntity): PersonaCard = PersonaCard(
        name = entity.name,
        summary = entity.summary,
        systemPrompt = entity.systemPrompt,
        personaDefinition = entity.personaDefinition,
        thinkingChain = entity.thinkingChain,
        messageTemplate = entity.messageTemplate,
        presetMessagesJson = entity.presetMessagesJson,
        avatarEmoji = entity.avatarEmoji,
        tagsJson = entity.tagsJson,
        capabilitiesJson = entity.capabilitiesJson,
        temperature = entity.temperature,
        topP = entity.topP,
        maxTokens = entity.maxTokens,
        contextMessageSize = entity.contextMessageSize,
        reasoningLevel = entity.reasoningLevel,
        streamOutput = entity.streamOutput,
        memoryEnabled = entity.memoryEnabled,
        useGlobalMemory = entity.useGlobalMemory,
        enableRecentChatsReference = entity.enableRecentChatsReference,
        enableTimeReminder = entity.enableTimeReminder,
        useAssistantName = entity.useAssistantName,
        allowGroupChat = entity.allowGroupChat,
        regexRulesJson = entity.regexRulesJson,
    )

    /**
     * 把人设套到一份既有实体上([base])。
     *
     * 只改人设字段。`id` / `providerId` / `modelId` / `toolModelId` / `customHeadersJson` /
     * `customBodiesJson` / 统计与时间戳 / `sortIndex` 全部沿用 base ——
     * 导入一份人设不该改变"它用哪个模型、带哪个密钥、排第几"。
     */
    fun applyTo(card: PersonaCard, base: AssistantEntity): AssistantEntity = base.copy(
        name = card.name,
        summary = card.summary,
        systemPrompt = card.systemPrompt,
        personaDefinition = card.personaDefinition,
        thinkingChain = card.thinkingChain,
        messageTemplate = card.messageTemplate,
        presetMessagesJson = card.presetMessagesJson,
        avatarEmoji = card.avatarEmoji,
        tagsJson = card.tagsJson,
        capabilitiesJson = card.capabilitiesJson,
        temperature = card.temperature,
        topP = card.topP,
        maxTokens = card.maxTokens,
        contextMessageSize = card.contextMessageSize,
        reasoningLevel = card.reasoningLevel,
        streamOutput = card.streamOutput,
        memoryEnabled = card.memoryEnabled,
        useGlobalMemory = card.useGlobalMemory,
        enableRecentChatsReference = card.enableRecentChatsReference,
        enableTimeReminder = card.enableTimeReminder,
        useAssistantName = card.useAssistantName,
        allowGroupChat = card.allowGroupChat,
        regexRulesJson = card.regexRulesJson,
    )

    /** 新建一份人设的落库形状:凭据与绑定留空,由本机设置决定。 */
    fun toNewEntity(card: PersonaCard): AssistantEntity {
        val now = System.currentTimeMillis()
        return AssistantEntity(
            id = "",
            name = card.name,
            summary = card.summary,
            systemPrompt = card.systemPrompt,
            personaDefinition = card.personaDefinition,
            thinkingChain = card.thinkingChain,
            messageTemplate = card.messageTemplate,
            presetMessagesJson = card.presetMessagesJson,
            avatarEmoji = card.avatarEmoji,
            tagsJson = card.tagsJson,
            capabilitiesJson = card.capabilitiesJson,
            temperature = card.temperature,
            topP = card.topP,
            maxTokens = card.maxTokens,
            contextMessageSize = card.contextMessageSize,
            reasoningLevel = card.reasoningLevel,
            streamOutput = card.streamOutput,
            memoryEnabled = card.memoryEnabled,
            useGlobalMemory = card.useGlobalMemory,
            enableRecentChatsReference = card.enableRecentChatsReference,
            enableTimeReminder = card.enableTimeReminder,
            useAssistantName = card.useAssistantName,
            allowGroupChat = card.allowGroupChat,
            regexRulesJson = card.regexRulesJson,
            createdAt = now,
            updatedAt = now,
        )
    }
}

/**
 * 导入前的差分预览项。
 *
 * 值只给**截断后的摘要**:用户在确认界面上要看的是"哪些地方会变、大概变成什么",
 * 而不是把整份人设重印一遍;而且截图转发时,长正文会连带别人看不该看的内容一起出去。
 */
data class PersonaFieldChange(
    val field: String,
    /** 字符串资源 id 而不是资源名:类型安全,不会到运行时才发现文案不存在。 */
    val labelRes: Int,
    val from: String,
    val to: String,
)

object PersonaDiff {

    private const val PREVIEW_CHARS = 48

    /** 字段清单:`Spec(字段名, 字符串资源 id, 取值函数)`。新增人设字段时只改这一处。 */
    private data class Spec(val field: String, val labelRes: Int, val of: (AssistantEntity) -> String)

    private val specs = listOf(
        Spec("name", R.string.assistant_diff_name) { it.name },
        Spec("summary", R.string.assistant_diff_summary) { it.summary },
        Spec("systemPrompt", R.string.assistant_diff_system_prompt) { it.systemPrompt },
        Spec("personaDefinition", R.string.assistant_diff_persona_definition) { it.personaDefinition },
        Spec("thinkingChain", R.string.assistant_diff_thinking_chain) { it.thinkingChain },
        Spec("messageTemplate", R.string.assistant_diff_message_template) { it.messageTemplate },
        Spec("presetMessagesJson", R.string.assistant_diff_preset_messages) { it.presetMessagesJson },
        // 未设置要说成「未设置」,而不是 "null",也不许悄悄拿对方的旧值当默认值。
        Spec("temperature", R.string.assistant_diff_temperature) { it.temperature?.toString() ?: "未设置" },
        Spec("topP", R.string.assistant_diff_top_p) { it.topP?.toString() ?: "未设置" },
        Spec("maxTokens", R.string.assistant_diff_max_tokens) { it.maxTokens?.toString() ?: "未设置" },
        Spec("contextMessageSize", R.string.assistant_diff_context_size) { it.contextMessageSize.toString() },
        Spec("reasoningLevel", R.string.assistant_diff_reasoning_level) { it.reasoningLevel },
        Spec("memoryEnabled", R.string.assistant_diff_memory_enabled) { it.memoryEnabled.toString() },
        Spec("useGlobalMemory", R.string.assistant_diff_use_global_memory) { it.useGlobalMemory.toString() },
        Spec("allowGroupChat", R.string.assistant_diff_allow_group_chat) { it.allowGroupChat.toString() },
    )

    /** 只列**真的会变**的字段;顺序稳定(按 specs 顺序),便于逐条测试。 */
    fun changes(current: AssistantEntity, incoming: PersonaCard): List<PersonaFieldChange> {
        val applied = PersonaCardCodec.applyTo(incoming, current)
        return specs.mapNotNull { spec ->
            val before = spec.of(current)
            val after = spec.of(applied)
            if (before == after) return@mapNotNull null
            PersonaFieldChange(spec.field, spec.labelRes, preview(before), preview(after))
        }
    }

    fun preview(value: String): String {
        val flat = value.replace('\n', ' ').trim()
        if (flat.length <= PREVIEW_CHARS) return flat
        return flat.take(PREVIEW_CHARS) + "…"
    }
}
