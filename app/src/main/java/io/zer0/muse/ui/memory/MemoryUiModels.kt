package io.zer0.muse.ui.memory

import io.zer0.memory.ticker.MemoryTicker

/**
 * 记忆页的状态模型：`MemoryUiState` 及配套的枚举/数据类。
 *
 * 从 [MemoryViewModel] 所在文件切出——这些类型被 UI 层与各组件引用，
 * 但自身不含逻辑，与 ViewModel 生命周期无关。
 */
/** F-7: 记忆时间范围筛选项(按 createdAt 距今天数,ALL 表示全部)。 */
enum class MemoryTimeRange(val days: Long?) {
    ALL(null),
    LAST_7(7),
    LAST_30(30),
    LAST_90(90),
}

/**
 * v2.6.23(M2): 一对未裁决矛盾(事实级,带双方 id 与所属 scope)。
 *
 * 取代旧的纯文本 JSON 清单:文本对无法裁决"哪条是对的",只能"清除"再等下轮重报;
 * 带 id 后记忆中心可以真正"保留这一句",另一方按 v18 supersedes 让位。
 */
data class MemoryConflictUi(
    val idA: Long,
    val idB: Long,
    val a: String,
    val b: String,
    val scope: String,
)

data class MemoryUiState(
    val isLoading: Boolean = true,
    val query: String = "",
    val factItems: List<MemoryItem> = emptyList(),
    val summaryItems: List<MemoryItem> = emptyList(),
    val compileItems: List<MemoryItem> = emptyList(),
    val contradictions: List<MemoryConflictUi> = emptyList(),
    val searchResults: List<MemoryItem> = emptyList(),
    val isSearching: Boolean = false,
    val factCount: Int = 0,
    val summaryCount: Int = 0,
    val lastUpdatedAt: String? = null,
    /** 完整错误堆栈(供 UI 可滚动展示,方便用户复制给开发者定位问题)。 */
    val errorTrace: String? = null,
    /**
     * v0.51: 记忆 dashboard 概览字段。
     *
     *  - [compiledMarkdown]:最近编译的 memory.md 摘要(由 [MemoryTicker.readCompiledMemoryMarkdown]
     *    裁剪到 tokenBudget 后的完整 markdown 文本,供"AI 对你的理解"卡片折叠展示)。
     *  - [healthMap]:MemoryTicker 各步骤健康快照(rollingSummary/compileDaily/compileToday/
     *    rollDailyWindow/compileFacts/deepMemory),用于在 dashboard 展示记忆健康状态。
     *  - [lastCompileTime]:最近一次成功编译时间(从 [healthMap] 的 lastSuccessAt 取最大值)。
     */
    val compiledMarkdown: String = "",
    val healthMap: Map<String, MemoryTicker.StepHealth> = emptyMap(),
    val lastCompileTime: String? = null,
    /** v4: 同步状态文案(如"最近编译 5 分钟前,最近的对话可能还未进入记忆")。 */
    val syncStatus: String = "",
    /** v4: 同步状态是否处于"可能未进入记忆"的过期状态。 */
    val syncStale: Boolean = false,
    /** v5: 记忆统计字段。 */
    val totalFactCount: Int = 0,
    val weekNewCount: Int = 0,
    val monthNewCount: Int = 0,
    /** v5: 重要度分布 (0/1/2 的计数)。 */
    val importanceDistribution: Map<Int, Int> = emptyMap(),
    /** v5: 最活跃会话(top 5, sessionId → count)。 */
    val topSessions: List<Pair<String, Int>> = emptyList(),
    /** v5: 近30天每日新增数(日期 → 条数)。 */
    val dailyTrend: List<Pair<String, Int>> = emptyList(),
    /**
     * v1.98: 经验库相关 state。
     *
     * - [experienceEnabled]:经验库开关(用户在设置页切换,实时反映)。
     * - [experienceItems]:经验条目列表(按 updatedAt 降序,供 UI 展示与编辑)。
     * - [experienceCount]:条目总数(用于 dashboard 指标)。
     */
    val experienceEnabled: Boolean = false,
    val experienceItems: List<MemoryItem> = emptyList(),
    val experienceCount: Int = 0,
    /**
     * v1.0.72: 群聊记忆(独立于主记忆系统)。
     *
     * 展示在记忆中心"群聊"Tab,供用户查看/单条删除/一键清空。
     * 数据来自 [io.zer0.muse.data.groupchat.GroupChatMemoryRepository]。
     */
    val groupChatMemories: List<GroupChatMemoryUiItem> = emptyList(),
    val groupChatMemoriesLoading: Boolean = false,
)

/**
 * 单条记忆 UI 项 — 跨 4 层统一形态。
 *
 * @param id 唯一标识(factId / sessionId / sectionKey)
 * @param title 标题(事实摘要 / 会话标题 / 编译段名)
 * @param content 完整内容(markdown 友好)
 * @param tags 标签(仅 Fact 层有)
 * @param time 时间戳文案
 * @param source 来源层("Fact" / "Summary" / "Compile")
 */
data class MemoryItem(
    val id: String,
    val title: String,
    val content: String,
    val tags: List<String> = emptyList(),
    val time: String? = null,
    val source: String,
    /** v4: 重要程度(0=普通,1=重要,2=关键)。仅 Fact 层有值。 */
    val importance: Int = 0,
    /** v4: 来源会话 id(仅 Fact 层有值,用于显示来源)。 */
    val sessionId: String? = null,
    /** v4: 入库时间 ISO 8601(用于显示"X天前入库")。 */
    val createdAt: String? = null,
    /** v1.98: 分类(仅 Experience 层有值,编辑时回填到表单)。 */
    val category: String? = null,
    /**
     * v8: 记忆作用域(仅 Fact 层有值)。
     *  - "main":主助手作用域
     *  - assistantId:子助手/团队成员作用域
     * 用于 UI 显示 scope 徽章(主助手=默认色,子助手=tertiary 色)。
     */
    val scope: String? = null,
    /** B4-05: 手动置顶时间 ISO 8601,null 表示未置顶。 */
    val pinnedAt: String? = null,
    /** 置信度(0-1)。编译/推断出来的事实可能偏低,用于判断“这条要不要当真”。 */
    val confidence: Float = 1.0f,
    /** 最近一次被检索命中的时间 ISO 8601,null = 从未命中。 */
    val lastHitAt: String? = null,
    /** 累计命中次数(0 = 从未被检索召回)。 */
    val hitCount: Int = 0,
    /** 会话摘要所属记忆空间;事实项由事实存储自身保留。 */
    val spaceId: String? = null,
    /** 已合并进另一条记忆(不参与召回,列表仍可见)。 */
    val mergedIntoId: Long? = null,
    /** 已退出自动召回(衰减到阈值以下,行仍在)。 */
    val suppressed: Boolean = false,
    /** 被别的记忆取代(内容被新记忆推翻)。 */
    val superseded: Boolean = false,
    /**
     * v2.6.23(M1): 证据摘录 —— 该事实出处的用户原话片段。
     * null/空白 = 没有可回看的出处(早期数据或推断所得),UI 不显示「依据」。
     */
    val evidenceQuote: String? = null,
    /** v2.6.23(M1): 证据所在消息 id;仅用于「跳回原话」,可空。 */
    val evidenceMessageId: String? = null,
    /** v2.6.23(M2): 与另一条事实存在未裁决矛盾(UI 显示「两种说法」徽标与裁决入口)。 */
    val pendingConflictWith: Long? = null,
) {
    /** 列表徽章与详情说明共用的状态投影(三种状态互相独立,可同时命中)。 */
    val status: MemoryFactStatus
        get() = MemoryFactStatus(merged = mergedIntoId != null, suppressed = suppressed, superseded = superseded)
}

/**
 * 记忆「不在正常使用中」的状态投影。
 *
 * 三种状态各自独立判定 —— 一条记忆可能既已出场又被合并,做成互斥枚举会让列表
 * 漏掉其中一个真实原因,用户看到的徽章就不足以解释它为什么不生效。
 *
 * @param merged 已合并进另一条记忆(指向聚合后的新行)
 * @param suppressed 已退出自动召回(衰减到阈值以下,行仍在)
 * @param superseded 被别的记忆取代(内容被新记忆推翻)
 */
data class MemoryFactStatus(
    val merged: Boolean = false,
    val suppressed: Boolean = false,
    val superseded: Boolean = false,
) {
    /** 三种状态都没命中 = 仍在正常召回链路里。 */
    val isInactive: Boolean get() = merged || suppressed || superseded
}

/**
 * 「被取代」集合的比对键。
 *
 * 各分库的 fact.id 独立自增会撞号,只比裸 id 会把子助手库里同号的记忆认成本行。
 */
internal fun memoryFactKey(scope: String?, id: Long): String = "${scope.orEmpty()}:$id"

/**
 * 单条事实行的状态判定(纯函数,便于单测)。
 *
 * @param mergedIntoId 本行 merged_into_id
 * @param suppressedAt 本行 suppressed_at(非空即已出场)
 * @param supersededKeys 由 [memoryFactKey] 组成的「已被其他行取代」集合
 */
internal fun memoryFactStatusOf(
    scope: String?,
    id: Long,
    mergedIntoId: Long?,
    suppressedAt: String?,
    supersededKeys: Set<String>,
): MemoryFactStatus = MemoryFactStatus(
    merged = mergedIntoId != null,
    suppressed = suppressedAt != null,
    superseded = memoryFactKey(scope, id) in supersededKeys,
)

/**
 * v8: 记忆作用域筛选项(供 UI 顶部 FilterChip 渲染)。
 *
 *  - [id]:作用域标识("main" 或 assistantId),null 表示"全部作用域"(UI 用 all 标记)。
 *    为方便 UI 区分"全部"与具体作用域,使用 [isAll] 标记。
 *  - [displayName]:UI 显示名("全部" / "主助手" / 助手名称)。
 *  - [isMain]:是否为主助手作用域(用于 UI 选用默认色 vs tertiary 色)。
 *  - [isAll]:是否为"全部"选项(用于 UI 显示"全部"且默认选中)。
 */
data class ScopeOption(
    val id: String?,
    val displayName: String,
    val isMain: Boolean = false,
    val isAll: Boolean = false,
)

/**
 * v1.0.72: 群聊记忆 UI 条目(记忆中心"群聊"Tab)。
 *
 * @param id 群聊记忆 id
 * @param groupChatId 群聊 id
 * @param groupChatName 群聊名称(从 GroupChatRepository 解析,失败回退 "群聊")
 * @param assistantName 发言助手名(从 AssistantRepository 解析,失败回退原始 id)
 * @param summary 摘要文本
 * @param createdAt 创建时间戳
 * @param timeText 格式化时间文案
 */
data class GroupChatMemoryUiItem(
    val id: String,
    val groupChatId: String,
    val groupChatName: String,
    val assistantName: String,
    val summary: String,
    val createdAt: Long,
    val timeText: String,
)

/**
 * 手动合并记忆的草稿状态,null = 没有正在进行的合并。
 *
 * 只承载一次合并会话的中间结果:模型出的草稿在用户确认前不落库,
 * 所以正文([mergedText])是可编辑的 UI 输入,[sourceItems] 保留原始条目以便确认时取 id。
 *
 * @param sources 参与合并的原始 N 条正文(只读展示,让用户知道合并了什么)
 * @param mergedText 模型生成的合并草稿(UI 可编辑)
 * @param sourceItems 被选中的原始记忆条目(确认合并时按 id 落库)
 */
data class MemoryMergeDraft(
    val sources: List<String>,
    val mergedText: String,
    val sourceItems: List<MemoryItem>,
)

/**
 * 阶段 6: 记忆页 ViewModel。
 *
 * 数据来源(全部已通过 Koin 注册):
 *  - [FactStore]:Fact 层 + LIKE 搜索
 *  - [SessionSummaryManager]:Summary 层
 *  - [MemoryCompiler]:Compile 层(读取 4 段编译产物)
 *
 * Deep 层无独立存储,通过 Fact 总数 + 时间戳推断展示。
 *
 * v8: 新增 [assistantRepository] 注入,用于:
 *  - 加载 [availableScopes](主助手 + 所有子助手)
 *  - [selectedScope] 控制记忆页 factItems 按作用域筛选
 *  - [addFact] 写入时使用当前 [selectedScope](null 时默认 "main")
 */
