package io.zer0.memory.prompt

/**
 * 编译提示词构建器。
 *
 * 提供四块独立编译（today / daily / week / longterm）与 editable facts 的 system prompt。
 * 输出统一为纯文本（无 markdown 标题），由 [CompiledMemoryState.normalizeLlmResult] 规范化。
 */
object CompilePrompts {

    /** compile today: 当天 sessions → today.md */
    fun buildTodayPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Condense today's conversation summaries into a short list of the user's current state and broad themes.

Rules:
- Group repeated exchanges about the same topic into one entry instead of a line-by-line log
- Use coarse time markers ("morning", "evening", or a rough HH:MM range), not minute-level precision
- The primary job of long-term memory is to maintain an accurate user model: who the user is, what they like, what matters to them, and what they are broadly focused on recently
- Work content may appear only at the broad-theme level: name the domain, project, or topic, not its internal details

May record:
- Identity, personality traits, aesthetics, interests, likes, and dislikes
- Broad themes currently occupying the user, such as "preparing to move", "learning to swim", or "writing a thesis draft"
- Changes in life, creative work, relationships, or long-term attention areas

Do NOT record:
- Execution steps, filenames, tools, commands, check order, collaboration preferences, or work details
- Methodology choices, tool preferences, format requirements, or terminology rules from individual tasks
- Specific subproblems, concrete solutions, concrete code changes, tests, or release flows
- Detailed content produced by the assistant ("wrote an article about X" is enough)
- Revisions, retries, interruptions, and resumptions — treat them as process noise

Output 3 to 5 coarse events, 1 to 2 sentences each, at most 180 words. Keep quiet days short. Do not use Markdown headings or start lines with #, ##, or ###; output body text only.
            """.trimIndent()
        }
        return """
请把今天的对话摘要整理成一份“用户近况与大主题清单”。

提炼原则：
- 同一主题或项目的多次往返归并成一条，不逐条记流水账
- 时间用主时段（“上午/傍晚”或粗略 HH:MM 区间），不需要精确到分钟
- 长期记忆的首要任务是维护用户模型：优先记录用户是谁、喜欢什么、在意什么、最近关注什么
- 工作内容只保留到大主题层级：写用户最近投入的领域/项目/主题，不写内部细节

可以记录：
- 身份、性格、审美、兴趣、喜欢或讨厌的事物
- 当前投入的大主题，例如“正在筹备搬家”“最近开始学游泳”“本周在写论文初稿”
- 生活、创作、关系或长期关注方向的变化

不要记录：
- 执行步骤、文件名、工具、命令、检查顺序、协作偏好、工作细节
- 单次任务中的方法论选择、工具偏好、格式要求、术语规则
- 具体子问题、具体方案、具体改法、具体测试或发布流程
- 助手产出的具体内容（“生成了一篇关于 X 的文章”就够了，不摘录正文）
- 来回修改、重试、被打断又恢复等过程波动

输出 3 到 5 条粗颗粒事件，每条 1 到 2 句，最多 300 字。一天平淡就写短。不要输出 Markdown 标题，不要以 #、##、### 开头；直接输出正文列表或段落。
        """.trimIndent()
    }

    /** compile daily: 已结束那天的 today 草稿/摘要 → daily/{date}.md */
    fun buildDailyPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Condense that day's timeline entries or final today draft into a two-to-three-sentence diary entry.

Positioning: this entry feeds a weekly overview; it is not a detailed log. The reader only needs a glance at what broadly happened and what the user focused on that day.

Rules:
- Group repeated exchanges about the same topic into one entry
- Keep a coarse sense of time, such as "morning", "evening", or one representative HH:MM; do not turn it into timeless topic labels
- The primary job of long-term memory is to maintain an accurate user model: who the user is, what they like, what matters to them, and what they focused on that day
- Work content may appear only at the broad-theme level

Do NOT record execution details, methodology choices, tool preferences, format requirements, specific subproblems, concrete solutions, code changes, tests, release flows, assistant output content, or process noise.

Output only two to three sentences, at most 30 words; keep quiet days shorter. Do not add a date heading (the caller adds it) and do not use Markdown headings.
            """.trimIndent()
        }
        return """
你会收到这一天的时间线条目或最终版“今日草稿”，请把它蒸馏成两三句话的简短日记条目。

定位：这是给一周概览用的一条记录，不是详细日志。读的人只需要一眼看出这一天大致发生了什么、用户在关注什么。

提炼原则：
- 同一主题或项目的多次往返归并成一条
- 保留粗时间感，例如“上午”“傍晚”或一个代表性 HH:MM；不要写成无时间锚点的主题标签
- 长期记忆的首要任务是维护用户模型：优先记录用户是谁、喜欢什么、在意什么、这天关注什么
- 工作内容只保留到大主题层级

不要记录执行步骤、文件名、工具、命令、检查顺序、协作偏好、工作细节，也不要记录方法论选择、工具偏好、格式要求、具体子问题、具体方案、具体改法、测试或发布流程、助手产出的具体内容，以及来回修改、重试、被打断又恢复等过程波动。

只输出两三句话，最多 60 字；平淡的一天写得更短。不要输出日期抬头（调用方会加上），不要使用 Markdown 标题。
        """.trimIndent()
    }

    /** compile week: 7 天滑动窗口 sessions → week.md */
    fun buildWeekPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Condense the past 7 days of conversation summaries into a weekly overview of the user's broad themes.

Positioning: at this layer the record is already coarse. It is one level above daily logs: capture what the user broadly focused on, invested time in, and what important changes happened.

Layering:
- The primary job of long-term memory is to maintain an accurate user model
- Work content may appear only at the broad-theme level
- Sustained focus themes come first ("focused on X all week", "spent several days on Y")
- Substantial personal changes, creative themes, relationship changes, or interest changes come second
- Time is vague ("early in the week", "a few days ago", "these last two days"); do not keep exact timestamps

Do NOT keep: execution steps, filenames, tools, commands, check order, collaboration preferences, work details, specific subproblems, concrete solutions, code changes, tests, release flows, task-level methodology or format choices, in-conversation revisions, temporary decisions, assistant output content, or trivial activity.

Record only what the user broadly focused on and what important changes happened this week. Output 3 to 5 themes or events, at most 240 words, without Markdown headings.
            """.trimIndent()
        }
        return """
请把过去 7 天的对话摘要整理成一份“本周用户主题概要”。

定位：这一层已经是粗线条记录，不是“每天发生的事”的集合，而是再归纳一层——用户这一周大致在关注什么、投入什么、发生了什么重要变化。

层级：
- 长期记忆的首要任务是维护用户模型：用户是谁、喜欢什么、在意什么、最近关注什么
- 工作内容只保留到大主题层级
- 持续性的关注主题（“本周持续关注 X”“这几天主要在做 Y”）放最前
- 够分量的个人近况、创作主题、关系变化、兴趣变化次之
- 时间用模糊表述（“周初/前几天/这两天”），不留精确时间戳

明确不要保留：执行步骤、文件名、工具、命令、检查顺序、协作偏好、工作细节；具体子问题、具体方案、具体改法、测试或发布流程；任务中的方法论、工具、格式选择；单次对话内的来回修改与临时决定；助手的具体产出；不重要的杂事。

只记录“用户这一周大致关注什么、发生了什么重要变化”。输出 3 到 5 条本周主题/事件，最多 400 字，不要使用 Markdown 标题。
        """.trimIndent()
    }

    /** compile longterm: week.md fold 进 longterm.md */
    fun buildLongtermPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Fold this week's additions into the existing long-term context.

The long-term context is the most stable layer. It records durable identity, personality, aesthetics, values, and long-running focus themes. It does not record transient current state or task details.

Rules:
- Merge new additions into the existing context; do not duplicate
- Keep only durable, stable user attributes
- Drop transient current-state information (it belongs in the today/week layers)
- For the same topic, newer information wins

Output 3 to 6 long-term attributes or themes, at most 240 words, without Markdown headings.
            """.trimIndent()
        }
        return """
把本周新增内容折叠进已有的长期情况。

长期情况是最稳定的一层，记录持久的身份、性格、审美、价值观和长期关注主题；不记录短暂近况或任务细节。

原则：
- 把新增内容合并进已有长期情况，不重复
- 只保留持久、稳定的用户属性
- 丢弃短暂近况（它属于 today/week 层）
- 同一主题以新信息为准

输出 3 到 6 条长期属性/主题，最多 400 字，不要使用 Markdown 标题。
        """.trimIndent()
    }

    /** compile facts: 30 天摘要的 facts 段 → facts.md */
    fun buildFactsPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (!isZh) {
            return """
Merge new candidate facts into the existing facts list.

Rules:
- Each fact must be atomic: one fact per line
- Merge duplicates and keep the original wording; do not add "the user" as a subject
- When two facts differ only by a subject, keep the shorter subject-less version
- Drop transient facts (task-level or one-time events)
- Keep only durable user-profile facts: identity, preferences, long-term focus
- Format: one fact per line, no bullet prefix, no headings

Output the merged list, at most 100 words, without Markdown headings.
            """.trimIndent()
        }
        return """
把新增候选 facts 合并进现有 facts。

原则：
- 每条 fact 必须原子：一行一条
- 合并重复项并保留原始表述；不要补“用户”主语
- 两条事实只差主语时，保留更短、不带主语的版本
- 丢弃短暂事实（任务级、一次性事件）
- 只保留持久的用户画像事实：身份、偏好、长期关注
- 格式：一行一条，无 bullet 前缀，无标题

输出合并后的列表，最多 200 字，不要使用 Markdown 标题。
        """.trimIndent()
    }

    /** compile editable facts: 增量水位线版,同 compileFacts 但语义是"可信基础" */
    fun buildEditableFactsPrompt(locale: String = "zh-CN"): String = buildFactsPrompt(locale)
}
