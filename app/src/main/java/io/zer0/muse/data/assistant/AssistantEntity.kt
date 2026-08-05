package io.zer0.muse.data.assistant

import androidx.compose.runtime.Immutable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Assistant 实体(Phase 8.2 H6)。
 *
 * 独立编写实现(35+ 字段)。
 * 一个 Assistant 代表一种"AI 人格",有独立的 system prompt / 模型 / 工具 / 记忆 / 背景。
 * 不同场景可用不同 Assistant(写作助手/代码助手/翻译助手等)。
 *
 * Phase 8.5 修复: 所有 NOT NULL 字段加 @ColumnInfo(defaultValue=...) 与 MIGRATION_1_2 SQL 对齐,
 * 避免 Room schema 验证时 expected/found defaultValue 不匹配导致崩溃。
 *
 * L-GC7 修复: 加 @Immutable 注解。所有字段均为 val 且类型为不可变基本类型(String/Int/Float/Boolean/Long),
 * 满足 Compose 不可变契约。使 List<AssistantEntity> 在 Compose 中被视为 stable,
 * 避免 remember(message.senderId, assistants) 因 List 实例变化而无谓重算。
 */
@Immutable
@Serializable
@Entity(tableName = "assistants")
data class AssistantEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(defaultValue = "0") val sortIndex: Int = 0,
    @ColumnInfo(defaultValue = "0") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val updatedAt: Long = System.currentTimeMillis(),

    // ── 模型配置 ──
    val modelId: String? = null,
    val providerId: String? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    @ColumnInfo(defaultValue = "20") val contextMessageSize: Int = 20,
    @ColumnInfo(defaultValue = "AUTO") val reasoningLevel: String = "AUTO",

    // ── Prompt ──
    @ColumnInfo(defaultValue = "") val systemPrompt: String = "",
    @ColumnInfo(defaultValue = "") val messageTemplate: String = "",
    @ColumnInfo(defaultValue = "[]") val presetMessagesJson: String = "[]",

    // ── 工具 ──
    @ColumnInfo(defaultValue = "[]") val toolIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val mcpServerIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "1") val streamOutput: Boolean = true,

    // ── 记忆 ──
    @ColumnInfo(defaultValue = "1") val memoryEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "1") val useGlobalMemory: Boolean = true,
    @ColumnInfo(defaultValue = "1") val enableRecentChatsReference: Boolean = true,

    // ── 提醒 ──
    @ColumnInfo(defaultValue = "1") val enableTimeReminder: Boolean = true,

    // ── 头像/背景 ──
    @ColumnInfo(defaultValue = "") val avatarEmoji: String = "",
    @ColumnInfo(defaultValue = "") val avatarImageUrl: String = "",
    @ColumnInfo(defaultValue = "") val backgroundUrl: String = "",
    @ColumnInfo(defaultValue = "1.0") val backgroundOpacity: Float = 1.0f,
    @ColumnInfo(defaultValue = "0") val useGradientBackground: Boolean = false,

    // ── 标签 ──
    @ColumnInfo(defaultValue = "[]") val tagsJson: String = "[]",

    // ── v1.200: 多 Agent 能力标签(JSON 数组,如 ["code","write","research"]) ──
    @ColumnInfo(defaultValue = "[]") val capabilitiesJson: String = "[]",

    // ── 扩展(后续 Phase 实现) ──
    @ColumnInfo(defaultValue = "[]") val quickMessageIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val lorebookIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val modeInjectionIdsJson: String = "[]",
    @ColumnInfo(defaultValue = "[]") val skillIdsJson: String = "[]",

    // v1.0.53: 工具模型(工具调用轮次使用的轻量模型,per-assistant;null 表示沿用全局 toolModelId)
    @ColumnInfo(defaultValue = "") val toolModelId: String = "",

    // ── 请求 ──
    @ColumnInfo(defaultValue = "{}") val customHeadersJson: String = "{}",
    @ColumnInfo(defaultValue = "{}") val customBodiesJson: String = "{}",

    // v1.97: 正则替换规则(JSON 数组,存储 AssistantRegex 列表)
    @ColumnInfo(defaultValue = "[]") val regexRulesJson: String = "[]",

    // ── v1.133 RAG 绑定与配置覆盖 ──
    /** v1.133: 该助手绑定的知识库 ID 列表(JSON 数组)。空数组 = 不绑定任何 KB(不注入 RAG)。 */
    @ColumnInfo(defaultValue = "[]") val knowledgeBaseIdsJson: String = "[]",
    /**
     * v1.133: RAG 配置覆盖(JSON 序列化的 RagConfigOverride,可为 null)。
     * 非空时合并到全局 RagConfig 之上(同名字段以 override 优先)。
     * 例:{"topK":5,"threshold":0.4,"mmrLambda":0.5}
     */
    @ColumnInfo(defaultValue = "NULL") val ragConfigOverride: String? = null,

    // ── v1.107 冗余统计字段(避免全表聚合,Repository 双写维护) ──
    /** 该 Assistant 发送的消息总数(统计页直接读,避免 GROUP BY 全表扫描)。 */
    @ColumnInfo(defaultValue = "0") val messageCount: Int = 0,
    /** 最后使用时间戳(排序/统计"最近活跃"用)。 */
    @ColumnInfo(defaultValue = "0") val lastUsedAt: Long = 0,

    // ── v1.0.19: Assistant 字段补齐 ──
    // 注: tags 分组已由上面的 tagsJson 字段承载(JSON 数组),此处不重复定义。
    /** 一句话简介(用于群聊花名册、助手卡片副标题展示)。 */
    @ColumnInfo(defaultValue = "") val summary: String = "",
    /** 聊天界面用助手名替换模型名,false 时显示模型名。 */
    @ColumnInfo(defaultValue = "0") val useAssistantName: Boolean = false,
    /** 是否允许加入群聊(默认 true,关闭后该助手不出现在群聊成员选择列表)。 */
    @ColumnInfo(defaultValue = "1") val allowGroupChat: Boolean = true,
) {
    /** 便捷判断:是否用图片头像。 */
    fun hasImageAvatar(): Boolean = avatarImageUrl.isNotBlank()

    /** 便捷判断:是否有背景图。 */
    fun hasBackground(): Boolean = backgroundUrl.isNotBlank()
}
