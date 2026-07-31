package io.zer0.memory.ai

import kotlinx.serialization.Serializable

/**
 * v1.0.52 P2-3: AI 记忆分析结果(借鉴 Operit MemoryLibrary.kt)。
 *
 * 由 [MemoryExtractPrompt] 驱动 LLM 输出,经 [MemoryAutoSaveScheduler.extractEntities]
 * 解析为该结构,再由 applyAnalysis 落库(创建/更新/合并事实 + 建立知识图谱边)。
 *
 * 设计原则:
 *  - mainProblem: 本次对话的主问题(可选,用于摘要标题)
 *  - extractedEntities: 新提取的实体(写入 facts 表)
 *  - links: 实体间关系(写入 memory_links 表,知识图谱边)
 *  - updatedEntities: 对已有事实的更新(按 title 匹配)
 *  - mergedEntities: 相似记忆合并指令(去重)
 *  - profileMarkdown: 用户画像 Markdown 更新(可选,覆盖式更新)
 *
 * 所有字段均可空/空列表,LLM 可只输出部分(如仅提取实体不合并)。
 */
@Serializable
data class ParsedAnalysis(
    /** 本次对话的主问题(一句话总结,用于会话标题/摘要)。 */
    val mainProblem: ParsedEntity? = null,
    /** 新提取的实体列表(将作为新事实写入)。 */
    val extractedEntities: List<ParsedEntity> = emptyList(),
    /** 实体间关系(知识图谱边)。 */
    val links: List<ParsedLink> = emptyList(),
    /** 对已有事实的更新(按 title 模糊匹配目标事实)。 */
    val updatedEntities: List<ParsedUpdate> = emptyList(),
    /** 相似记忆合并指令(将多个相似事实合并为一条)。 */
    val mergedEntities: List<ParsedMerge> = emptyList(),
    /** 用户画像 Markdown 更新(可选,覆盖式更新)。 */
    val profileMarkdown: String? = null,
)

/**
 * 提取的实体(原子事实)。
 *
 * 与 [io.zer0.memory.fact.FactStore.Fact] 的映射:
 *  - title → 用于匹配/去重/展示标题
 *  - content → fact 正文
 *  - credibility → confidence(0.0~1.0)
 *  - importance → importance(0/1/2,由 Float 映射: <0.4→0, <0.7→1, else 2)
 *  - folderPath → category(如 "preference"/"identity"/"event"...)
 *  - tags → tags
 */
@Serializable
data class ParsedEntity(
    val title: String,
    val content: String,
    val credibility: Float = 0.5f,
    val importance: Float = 0.5f,
    val folderPath: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * 实体间关系(知识图谱边)。
 *
 * linkType 取值:
 *  - causes: A 导致/引起 B
 *  - explains: A 解释/说明 B
 *  - part_of: A 是 B 的一部分
 *  - related_to: 一般关联(默认)
 *  - contradicts: A 与 B 矛盾(用于更新时标记旧事实被推翻)
 *
 * weight 0.0~1.0,表示关系强度(0.5 默认)。
 */
@Serializable
data class ParsedLink(
    val sourceTitle: String,
    val targetTitle: String,
    val linkType: String = "related_to",
    val weight: Float = 0.5f,
)

/**
 * 对已有事实的更新指令。
 *
 * 按 matchTitle 模糊匹配已有事实,用 newContent 替换正文,
 * 并可选更新 importance/category/tags。
 */
@Serializable
data class ParsedUpdate(
    val matchTitle: String,
    val newContent: String,
    val newImportance: Float? = null,
    val newFolderPath: String? = null,
    val newTags: List<String>? = null,
)

/**
 * 相似记忆合并指令。
 *
 * 将 sourceTitles 列出的事实合并为一条,正文取 mergedContent,
 * 保留 mergedTitle 作为新标题,源事实标记为已合并(软删除或硬删除)。
 */
@Serializable
data class ParsedMerge(
    val sourceTitles: List<String>,
    val mergedTitle: String,
    val mergedContent: String,
)
