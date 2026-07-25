package io.zer0.muse.tools

import io.zer0.muse.data.assistant.AssistantEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * v1.200: Agent 能力标签常量与辅助函数。
 *
 * 每个 Assistant 可在 AssistantDetailPages 中被标注一组能力标签,
 * 供 AgentRouter 在用户未显式指定 assistantId 时自动匹配最合适的子助手。
 */
object AgentCapability {

    // ── 通用能力 ──
    const val CHAT = "chat"
    const val REASONING = "reasoning"
    const val WRITING = "writing"
    const val CREATIVE = "creative"
    const val TRANSLATION = "translation"
    const val REVIEW = "review"

    // ── 专业领域 ──
    const val CODE = "code"
    const val MATH = "math"
    const val RESEARCH = "research"
    const val DATA = "data"
    const val LEGAL = "legal"
    const val MEDICAL = "medical"
    const val FINANCE = "finance"
    const val EDUCATION = "education"

    // ── 工具/模态 ──
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val WEB_SEARCH = "web_search"
    const val MEMORY = "memory"
    const val SCHEDULE = "schedule"
    const val KNOWLEDGE = "knowledge"

    /** 所有可选能力标签,按分类排序,用于 UI 展示。 */
    val ALL_CAPABILITIES = listOf(
        CHAT, REASONING, WRITING, CREATIVE, TRANSLATION, REVIEW,
        CODE, MATH, RESEARCH, DATA, LEGAL, MEDICAL, FINANCE, EDUCATION,
        IMAGE, VIDEO, WEB_SEARCH, MEMORY, SCHEDULE, KNOWLEDGE,
    )

    /** 能力标签的友好中文名。 */
    fun displayName(capability: String): String = when (capability) {
        CHAT -> "闲聊"
        REASONING -> "推理"
        WRITING -> "写作"
        CREATIVE -> "创意"
        TRANSLATION -> "翻译"
        REVIEW -> "审阅"
        CODE -> "编程"
        MATH -> "数学"
        RESEARCH -> "调研"
        DATA -> "数据处理"
        LEGAL -> "法律"
        MEDICAL -> "医疗"
        FINANCE -> "金融"
        EDUCATION -> "教育"
        IMAGE -> "图片生成"
        VIDEO -> "视频生成"
        WEB_SEARCH -> "联网搜索"
        MEMORY -> "记忆整理"
        SCHEDULE -> "日程管理"
        KNOWLEDGE -> "知识库"
        else -> capability
    }

    /** 从 AssistantEntity 解析能力标签列表。 */
    fun fromEntity(entity: AssistantEntity): List<String> {
        return parseCapabilitiesJson(entity.capabilitiesJson)
    }

    /** 把能力标签列表序列化为 JSON 字符串。 */
    fun toJson(capabilities: List<String>): String {
        return io.zer0.common.AppJson.encodeToString(
            ListSerializer(String.serializer()),
            capabilities.distinct().filter { it.isNotBlank() },
        )
    }

    /** 解析能力标签 JSON 字符串。 */
    fun parseCapabilitiesJson(json: String): List<String> {
        if (json.isBlank() || json == "[]") return emptyList()
        return runCatching {
            io.zer0.common.AppJson.decodeFromString(
                ListSerializer(String.serializer()),
                json,
            )
        }.getOrElse { emptyList() }
    }
}
