package io.zer0.memory.prompt

/**
 * 元事实抽取提示词构建器。
 *
 * 供 [io.zer0.memory.deep.DeepMemoryProcessor] 使用：把摘要 diff 拆成原子元事实。
 * 输出严格 JSON 数组，不带 markdown 围栏。
 */
object FactExtractionPrompt {

    const val TEMPLATE_VERSION = "fact-extraction.v2"
    const val CACHE_GROUP = "memory.extract_facts"

    fun buildSystemPrompt(
        locale: String = "zh-CN",
        hasPrevious: Boolean = false,
    ): String {
        val isZh = locale.startsWith("zh")

        if (isZh) {
            val diffInstruction = if (hasPrevious) {
                """
输入分为两部分：
1. **上次快照**：上一轮已处理的摘要
2. **当前摘要**：本轮最新摘要

请识别当前摘要相对上次快照新增或变化的内容，逐条拆成独立的元事实；上次已存在的内容不要重复提取。
                """.trimIndent()
            } else {
                "请把下面的摘要内容拆成独立的元事实。"
            }

            return """
你负责把对话摘要拆成适合长期检索的单条事实。$diffInstruction

## 工作原则

1. 只保留用户画像与近况层面的事实。
   画像包括：身份、性格、审美、兴趣、好恶、长期关系、持续关注方向。
   近况包括：用户最近投入的领域、项目、主题，例如“正在筹备搬家”“最近开始学游泳”“本周在写论文初稿”。

2. 不收录执行类细节：工作方式偏好、协作流程偏好、工具偏好、工程规则、文件名、命令、测试、发布、提交、推送等。
   如果一条内容描述的是“以后遇到类似任务应如何做”，它属于经验或技能，不属于记忆事实。
   如果一条内容只是某个主题下的具体子问题、具体方案、具体改法，也不要收录。

3. 每条事实只承载一件事，保持原子性。
   反例：“用户讨论了搬家安排并决定先整理书房”太细。
   正例：
   - “用户最近在筹备搬家”
   - “用户希望新家客厅走极简风格”

4. 标签用于检索，选辨识度高的关键词，每条 2 到 5 个。
   优先选人名、项目名、技术名词、主题类别。

5. time 字段使用 YYYY-MM-DDTHH:MM 格式，从摘要正文的时间标注与时间上下文中提取。
   只采用摘要正文明确出现的日期，或时间上下文给出的会话来源本地日期。
   若摘要只有 HH:MM，且时间上下文只有一个本地日期，则补全该日期；跨多个本地日期时填 null。
   无法确定具体时间时填 null。

6. 只记录客观事实与事件，不记录助手的内心活动。

7. importance 取 0/1/2：
   - 0（普通）：日常偏好、兴趣，记错影响很小（如“喝咖啡不加糖”“喜欢科幻”）。
   - 1（重要）：中等风险，记错会误事（如“下周三交论文初稿”“下周搬家”“正在学游泳”）。
   - 2（关键）：高风险，记错会带来身体伤害、重大经济损失或严重冒犯，例如医疗、财务、安全、核心身份信息。
   判断依据以“记错这条信息的代价”为准；不确定时标 0。

8. category 可选值：preference（偏好/好恶）、identity（身份/性格）、event（事件/计划）、
   relationship（关系）、goal（目标/梦想）、medical（医疗/健康）、other（其他）。不确定时填 other。

9. confidence 是 0.0 到 1.0 的浮点数：用户明确陈述取 1.0；明显推断取 0.5 到 0.8；高度不确定低于 0.3。

10. source 取 user_explicit（用户明确陈述）、inferred（推断）、imported（外部导入）。

11. expires_at 仅在事实有时效性时填写 ISO 8601（如“明天上午 10 点开会”）；长期有效填 null。

12. 保留原始表述，不为事实补充主语。用户说“最近在学游泳”，就写“最近在学游泳”，不要改成“用户最近在学游泳”。

13. 没有值得提取的新内容时，返回空数组 []。

14. entity_key 是实体归一化键：当事实的主语是具体的人名/称呼（如“张三”“张先生”“我妈”）时，
    把该实体最规范的名字作为 entity_key（如 entity_key="张三"）；同一实体在不同条目中必须使用
    完全相同的 entity_key。其余情况填 null。

## 输出格式

只输出 JSON 数组，不要 markdown 代码块：
[
  {"fact": "用户最近在筹备搬家", "tags": ["搬家", "近况"], "time": null, "importance": 0, "category": "event", "confidence": 0.9, "source": "inferred", "entity_key": null},
  {"fact": "下周三要提交论文初稿", "tags": ["学业", "计划"], "time": "2026-08-12T09:00", "importance": 1, "category": "goal", "confidence": 1.0, "source": "user_explicit", "entity_key": null},
  {"fact": "张先生喜欢喝美式咖啡", "tags": ["咖啡"], "time": null, "importance": 0, "category": "preference", "confidence": 1.0, "source": "user_explicit", "entity_key": "张三"}
]

注意：第 2 条示例里用户明确说了“下周三”，time 就填了对应的未来日期。
凡是明确提到未来日期/时间的事件（考试、航班、会议、截止等），time 必须填具体值，不要留 null；
只有确实没有时间信息时才填 null。time 格式 YYYY-MM-DDTHH:MM。
            """.trimIndent()
        }

        val diffInstruction = if (hasPrevious) {
            """
You will receive two inputs:
1. **Previous Snapshot**: the summary already processed last time
2. **Current Summary**: the latest full summary

Find content that is new or changed in the current summary, and split it into independent atomic facts. Do not re-extract facts already present in the previous snapshot.
            """.trimIndent()
        } else {
            "Split the following summary into independent atomic facts."
        }

        return """
You are a memory fact splitter. $diffInstruction

## Working Principles

1. Keep only user-profile and coarse current-state facts.
   Profile includes identity, personality, aesthetics, interests, likes and dislikes, long-term relationships, and sustained focus areas.
   Current state includes broad domains the user is recently engaged in, such as "preparing to move", "learning to swim", or "working on a thesis draft".

2. Do not record execution details: work-style preferences, collaboration preferences, tool preferences, engineering rules, filenames, commands, tests, releases, commits, pushes, or similar.
   If a statement describes how to handle similar tasks in the future, it belongs in the experience or skill layer, not in memory facts.
   If a statement describes a concrete subproblem, concrete solution, or concrete change inside a topic, do not record it.

3. Each fact must be atomic: one entry, one fact.
   Wrong: "The user discussed moving plans and decided to sort the study first" is too detailed.
   Correct:
   - "The user is preparing to move"
   - "The user wants a minimalist style for the new living room"

4. Tags are for retrieval. Choose distinctive keywords, 2 to 5 per fact.
   Prefer names, project names, technical terms, and topic categories.

5. The time field uses YYYY-MM-DDTHH:MM, taken from time annotations in the summary and the time context.
   Use only dates explicitly present in the summary body or source local dates from the time context.
   If the summary has HH:MM only and the time context has exactly one local date, combine them. If it spans multiple local dates, use null.
   When the exact time cannot be determined, use null.

6. Record only objective facts and events, not the assistant's inner thoughts.

7. importance takes 0/1/2:
   - 0 (normal): daily preferences or interests; being wrong is harmless (e.g. "coffee without sugar", "likes science fiction").
   - 1 (important): moderate risk; being wrong causes missed events or awkwardness (e.g. "thesis draft due next Wednesday", "moving next week", "learning to swim").
   - 2 (critical): high risk; being wrong can cause physical harm, major financial loss, or serious offense (medical, financial, safety, or core identity).
   Judge by the cost of being wrong. When unsure, use 0.

8. category values: preference, identity, event, relationship, goal, medical, other. Use other when uncertain.

9. confidence is a float from 0.0 to 1.0: 1.0 for explicit user statements, 0.5 to 0.8 for clear inference, below 0.3 for highly uncertain guesses.

10. source values: user_explicit, inferred, imported.

11. expires_at is an ISO 8601 timestamp for time-sensitive facts (e.g. "meeting at 10am tomorrow"); use null for durable facts.

12. Preserve the original wording; do not add a subject. If the user said "learning to swim", write "learning to swim", not "the user is learning to swim".

13. Return an empty array [] when there is nothing new worth extracting.

14. entity_key is the entity normalization key: when a fact's subject is a specific person's name or title (e.g. "Mr. Zhang", "my mom"), set entity_key to the canonical name of that entity (e.g. "Zhang San"); the same entity MUST use exactly the same entity_key across entries. Use null otherwise.

## Output Format

Output a strict JSON array only, without markdown code fences:
[
  {"fact": "The user is preparing to move", "tags": ["moving", "current-state"], "time": null, "importance": 0, "category": "event", "confidence": 0.9, "source": "inferred", "entity_key": null},
  {"fact": "Thesis draft is due next Wednesday", "tags": ["academic", "plan"], "time": null, "importance": 1, "category": "goal", "confidence": 1.0, "source": "user_explicit", "entity_key": null},
  {"fact": "Mr. Zhang prefers American coffee", "tags": ["coffee"], "time": null, "importance": 0, "category": "preference", "confidence": 1.0, "source": "user_explicit", "entity_key": "Zhang San"}
]
            """.trimIndent()
    }
}
