package io.zer0.memory.prompt

/**
 * Compile prompts builder (openhanako prompts/compile.ts 移植)。
 *
 * 四块独立编译 + editable facts 各自的 system prompt。
 * 输出统一为纯文本(无 markdown 标题),由 [CompiledMemoryState.normalizeLlmResult] 规范化。
 */
object CompilePrompts {

    /** compile today: 当天 sessions → today.md */
    fun buildTodayPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Distill today's conversation summaries into a "user-current-state and broad-theme list".

Principles:
- Merge multiple back-and-forth on the same topic/project into ONE event; do not enumerate line by line
- Time markers use major periods ("morning/evening" or rough HH:MM range), no minute-level precision
- Memory's core job is to maintain a user model: prioritize who the user is, what they like, what they care about, and what they are broadly focused on recently
- Work-related content may only be kept at the broad-theme level: record the domain/project/theme, not details inside that theme

May record:
- The user's identity, personality traits, aesthetics, interests, likes, and dislikes
- Broad themes the user is currently focused on, such as "memory systems", "Project Hana", or "AI Agent"
- Changes in the user's life, creative work, relationships, or long-term areas of attention

Do NOT record:
- Execution steps, filenames, tools, commands, validation order, collaboration preferences, or work details
- Task-level methodology choices, tool preferences, format requirements, terminology rules
- Specific subproblems, concrete solutions, concrete code changes, tests, or release flows
- Specific content of assistant's output ("wrote an article about X" is enough; do not excerpt the article)
- Revisions, retries, interruptions and resumptions — these are process noise

Output 3-5 coarse events, 1-2 sentences each. Max 180 words. Keep it short on quiet days. Do not output Markdown headings. Do not start with #, ##, or ###; output body text only.
            """.trimIndent()
        }
        return """
请把今天的对话摘要整理成一份"用户近况与大主题清单"。

提炼原则:
- 把同一主题/项目的多次往返归并为一件事,不要逐条流水账
- 时间标注用主时段("上午/傍晚"或粗略 HH:MM 区间),不需精确到分钟
- 记忆的核心职责是维护用户模型,优先记录用户是谁、喜欢什么、在意什么、最近关注什么
- 工作相关内容只允许保留到大主题层级:只写用户最近关注的领域/项目/主题,不写该主题里的细节

可以记录:
- 用户的身份、人格特质、审美、兴趣、喜欢或讨厌的事物
- 用户最近关注的大主题,例如"记忆系统""Project Hana""AI Agent"
- 用户生活、创作、关系或长期关注方向的变化

不要记录:
- 不要记录执行步骤、文件名、工具、命令、检查顺序、协作偏好、工作细节
- 任务过程中的方法论选择、工具偏好、格式要求、术语规则
- 具体子问题、具体方案、具体改法、具体测试或发布流程
- 助手具体产出的内容("生成了一篇关于 X 的文章"够了,不要摘录文章内容)
- 来回修改、重试、被打断又恢复这类过程波动

输出 3-5 条粗颗粒事件,每条 1-2 句。最多 300 字。一天平淡就写得短。不要输出 Markdown 标题,不要以 #、##、### 开头;直接输出正文列表或段落。
        """.trimIndent()
    }

    /** compile daily: 已结束那天的 today 草稿/摘要 → daily/{date}.md */
    fun buildDailyPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Distill that day's timeline entries or final "today draft" into a short two-to-three sentence diary entry.

Positioning: this is one entry feeding a weekly overview, not a detailed log. The reader only needs a glance at what broadly happened that day and what the user was focused on.

Principles:
- Merge multiple back-and-forth on the same topic/project into ONE event; do not enumerate line by line
- Preserve the day's coarse sense of time, such as "morning", "evening", or one representative HH:MM; do not turn it into timeless topic labels
- Memory's core job is to maintain a user model: prioritize who the user is, what they like, what they care about, and what they focused on that day
- Work-related content may only be kept at the broad-theme level: record the domain/project/theme, not details inside that theme

Do NOT record:
- Execution steps, filenames, tools, commands, validation order, collaboration preferences, or work details
- Task-level methodology choices, tool preferences, format requirements, terminology rules
- Specific subproblems, concrete solutions, concrete code changes, tests, or release flows
- Specific content of assistant's output
- Revisions, retries, interruptions and resumptions

Output only two to three sentences, max 30 words. Keep it shorter on quiet days. Do not output a date heading; the caller adds the date. Do not output Markdown headings. Do not start with #, ##, or ###; output body text only.
            """.trimIndent()
        }
        return """
你会收到这一天的时间线条目或最终版"今日草稿"(当天结束时对用户近况的整理稿),请把它蒸馏成两三句话的简短日记条目。

关键定位:这是给一周概览用的一条记录,不是详细日志。读的人只需要一眼看出这一天大致发生了什么、用户在关注什么。

提炼原则:
- 把同一主题/项目的多次往返归并为一件事,不要逐条流水账
- 保留这一天的粗时间感,例如"上午"、"傍晚"或一个代表性 HH:MM;不要写成无时间锚点的主题标签
- 记忆的核心职责是维护用户模型:优先记录用户是谁、喜欢什么、在意什么、这天关注什么
- 工作相关内容只允许保留到大主题层级:只写用户这天关注的领域/项目/主题,不写该主题里的细节

不要记录:
- 不要记录执行步骤、文件名、工具、命令、检查顺序、协作偏好、工作细节
- 任务过程中的方法论选择、工具偏好、格式要求、术语规则
- 具体子问题、具体方案、具体改法、具体测试或发布流程
- 助手具体产出的内容
- 来回修改、重试、被打断又恢复这类过程波动

只输出两三句话,最多 60 字。这天平淡就写得更短。不要输出日期抬头(调用方会自行加上日期),不要输出 Markdown 标题,不要以 #、##、### 开头;直接输出正文。
        """.trimIndent()
    }

    /** compile week: 7 天滑动窗口 sessions → week.md */
    fun buildWeekPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Distill the past 7 days' conversation summaries into a "weekly user-theme overview".

Positioning: at the week layer, the record is already coarse-grained. It is NOT a collection of "what happened each day" — it is one level above: distilling what the user was broadly focused on, invested in, and what important changes happened.

Layering:
- Memory's core job is to maintain a user model: who the user is, what they like, what they care about, and what they are broadly focused on recently
- Work-related content may only be kept at the broad-theme level: record the domain/project/theme, not details inside that theme
- Persistent focus themes ("focused on X this week", "spent several days on Y") come first
- Substantial personal current-state, creative themes, relationship changes, or interest changes come second
- Time is vague ("early in the week / a few days ago / these last two days"); do NOT preserve exact timestamps

Explicitly do NOT keep:
- Execution steps, filenames, tools, commands, validation order, collaboration preferences, or work details
- Specific subproblems, concrete solutions, concrete code changes, tests, or release flows
- Task-level details (how it was done, how many revisions, interruptions and resumptions)
- Task-level methodology, tools, format choices
- Within-conversation revisions and temporary decisions
- Specific content of assistant's output
- Trivial activity (small talk, lookups, debugging)

Record only "what the user was broadly focused on and what important changes happened this week". For work, keep only the broad theme. Skip the rest.

Output 3-5 weekly themes/events. Max 240 words. Do not output Markdown headings. Do not start with #, ##, or ###; output body text only.
            """.trimIndent()
        }
        return """
请把过去 7 天的对话摘要整理成一份"本周用户主题概要"。

关键定位:到 week 这一层,记录已经是粗线条的了。它不是"每天发生的事"的集合,而是再上一层——归纳用户这一周大致在关注什么、投入什么、发生了什么重要变化。读这份记录的人只需要知道用户近况和大主题,不需要知道任何过程细节。

提炼层级:
- 记忆的核心职责是维护用户模型:用户是谁、喜欢什么、在意什么、最近关注什么
- 工作相关内容只允许保留到大主题层级:只写用户最近关注的领域/项目/主题,不写该主题里的细节
- 持续性的关注主题("本周持续关注 X"、"这几天主要在做 Y")放最前
- 够分量的个人近况、创作主题、关系变化、兴趣变化次之
- 时间用模糊表述("周初/前几天/这两天"),不留精确时间戳

明确不要保留的内容:
- 不要记录执行步骤、文件名、工具、命令、检查顺序、协作偏好、工作细节
- 某个主题里的具体子问题、具体方案、具体改法、具体测试或发布流程
- 任务过程中的方法论、工具、格式选择
- 单次对话内的来回修改、临时决定
- 助手的具体产出内容
- 不重要的杂事(普通的闲聊、查询、调试)

只记录"用户这一周大致关注什么、发生了什么重要变化"。工作只记大主题,其他可以不写。

输出 3-5 条本周主题/事件。最多 400 字。不要输出 Markdown 标题,不要以 #、##、### 开头;直接输出正文列表或段落。
        """.trimIndent()
    }

    /** compile longterm: week.md fold 进 longterm.md */
    fun buildLongtermPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Fold this week's additions into the existing long-term context.

Long-term context is the most stable layer. It records durable user identity, personality, aesthetics, values, and long-running focus themes. It does NOT record transient current state or task details.

Principles:
- Merge new additions into the existing long-term context; do not duplicate
- Keep only durable, stable user attributes (identity, personality, aesthetics, values, long-term focus)
- Drop transient current-state info (it belongs in today/week layers)
- For the same topic, newer info wins

Output 3-6 long-term user attributes/themes. Max 240 words. Do not output Markdown headings. Do not start with #, ##, or ###; output body text only.
            """.trimIndent()
        }
        return """
把本周新增内容折叠进已有的长期情况。

长期情况是最稳定的一层。它记录持久的用户身份、人格、审美、价值和长期关注主题。它不记录短暂近况或任务细节。

原则:
- 把新增内容合并进已有长期情况,不要重复
- 只保留持久的、稳定的用户属性(身份、人格、审美、价值、长期关注)
- 丢弃短暂近况信息(它属于 today/week 层)
- 同一主题以新信息为准

输出 3-6 条长期用户属性/主题。最多 400 字。不要输出 Markdown 标题,不要以 #、##、### 开头;直接输出正文列表或段落。
        """.trimIndent()
    }

    /** compile facts: 30 天摘要的 facts 段 → facts.md */
    fun buildFactsPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Merge new candidate facts into existing facts.

Principles:
- Each fact must be atomic (one fact per line)
- Merge duplicates; keep the most recent wording
- Drop facts that are transient (task-level, one-time events)
- Keep only durable user-profile facts (identity, preferences, long-term focus)
- Format: one fact per line, no bullet prefix, no headings

Output the merged facts list. Max 100 words. Do not output Markdown headings. Do not start with #, ##, or ###; output body text only.
            """.trimIndent()
        }
        return """
把新增候选 facts 合并进现有 facts。

原则:
- 每条 fact 必须原子(一行一条)
- 合并重复项,保留最新表述
- 丢弃短暂事实(任务级、一次性事件)
- 只保留持久的用户画像事实(身份、偏好、长期关注)
- 格式: 一行一条,无 bullet 前缀,无标题

输出合并后的 facts 列表。最多 200 字。不要输出 Markdown 标题,不要以 #、##、### 开头;直接输出正文列表或段落。
        """.trimIndent()
    }

    /** compile editable facts: 增量水位线版,同 compileFacts 但语义是"可信基础" */
    fun buildEditableFactsPrompt(locale: String = "zh-CN"): String = buildFactsPrompt(locale)
}
