package io.zer0.memory.prompt

/**
 * 元事实抽取提示词构建器。
 *
 * 供 [io.zer0.memory.deep.DeepMemoryProcessor] 使用：把摘要 diff 拆成原子元事实。
 * 输出严格 JSON 数组，不带 markdown 围栏。
 */
object FactExtractionPrompt {

    // D6 第 2 期: 强化原子性约束(单一实体+单一谓词、连接词多断言拆分、正反例) → v3
    // D3: 触发准则补充(纠正处理 / 强调与边界) → v4
    // 助手会话事件不得提升为用户长期事实 → v5
    // 修正示例主语/时间编造/补齐 expires_at 并精简规则 → v6
    const val TEMPLATE_VERSION = "fact-extraction.v6"
    const val CACHE_GROUP = "memory.extract_facts"

    fun buildSystemPrompt(locale: String = "zh-CN", hasPrevious: Boolean = false): String {
        val isZh = locale.startsWith("zh")

        if (isZh) {
            val diffInstruction = if (hasPrevious) {
                "输入含「上次快照」与「当前摘要」，只提取相对上次快照新增或变化的内容，不重复已有条目。"
            } else {
                "请把下面的摘要拆成独立的元事实。"
            }

            return """
你负责把对话摘要拆成适合长期检索的原子事实。$diffInstruction

## 工作原则

1. **只留用户画像与大颗粒近况**：身份、性格、审美、兴趣、好恶、长期关系、持续关注方向，以及粗颗粒近况（如“最近在筹备搬家”“本周在写论文初稿”）。
2. **不收录**：执行细节（工作/协作/工具偏好、工程规则、文件名、命令、测试、发布、具体子问题与改法）；助手的 Vibe 自述、助手承诺、助手行动或角色状态（这些属于会话记忆，不是用户事实）；任何带 `[助手...]` 说话者标记的时间线条目。
3. **原子性与原话保留**：每条只承载单一实体、单一谓词；含“并且/而且/同时/以及”串联多判断时拆分（如拆成“在北京工作”和“周末喜欢爬山”）；不补“用户”主语，直接保留原始表述（如写“最近在学游泳”而非“用户最近在学游泳”）。
4. **纠正与强调**：用户纠正或否定旧说法时，只提取最新表述（confidence=1.0）；明确强调或表达边界/禁忌（“不要……”“千万别……”）时，importance 至少为 1。
5. **时间与时效**：
   - `time`：明确给出日期与时刻用 `YYYY-MM-DDTHH:MM`；只明确日期（含据时间上下文可推算的近期相对日期，如“下周三”）用 `YYYY-MM-DD`，严禁编造 `09:00` 等钟点；仅有 `HH:MM` 且时间上下文唯一日期时补全日期，跨多日或无时间信息填 `null`。
   - `expires_at`：仅有时效性的事实填 ISO 8601（如会议/截止之后），长期有效填 `null`。
6. **字段规范**：
   - `tags`：2–5 个高辨识度检索词（人名/项目/主题）。
   - `importance`：0=普通偏好（记错无害）；1=重要计划/边界（记错误事）；2=关键（医疗/财务/安全/核心身份）。不确定取 0。
   - `category`：`preference` / `identity` / `event` / `relationship` / `goal` / `medical` / `other`。
   - `confidence`：0.0–1.0（明确陈述 1.0，清晰推断 0.5–0.8，不确定 <0.3）；`source`：`user_explicit` / `inferred` / `imported`。
   - `entity_key`：主语为具体人名/称呼（如“张先生”“我妈”）时填该实体规范名（如 `"张三"`），同实体须保持一致；其余填 `null`。
7. 无值得提取的新内容时返回 `[]`。

## 输出格式

示例仅演示字段结构，严禁照抄内容；所有事实必须来自输入摘要。只输出严格 JSON 数组，不要 markdown 代码块：
[
  {"fact": "最近在筹备搬家", "tags": ["搬家", "近况"], "time": null, "expires_at": null, "importance": 0, "category": "event", "confidence": 0.9, "source": "inferred", "entity_key": null},
  {"fact": "下周三要提交论文初稿", "tags": ["学业", "计划"], "time": "2026-08-12", "expires_at": "2026-08-12T23:59", "importance": 1, "category": "goal", "confidence": 1.0, "source": "user_explicit", "entity_key": null},
  {"fact": "张先生喜欢喝美式咖啡", "tags": ["咖啡"], "time": null, "expires_at": null, "importance": 0, "category": "preference", "confidence": 1.0, "source": "user_explicit", "entity_key": "张三"}
]
            """.trimIndent()
        }

        val diffInstruction = if (hasPrevious) {
            "Input has a Previous Snapshot and Current Summary; extract only new or changed items without repeating prior facts."
        } else {
            "Split the following summary into independent atomic facts."
        }

        return """
You are a memory fact splitter. $diffInstruction

## Working Principles

1. **Keep only user profile and coarse current state**: identity, personality, aesthetics, interests, likes/dislikes, long-term relationships, sustained focus areas, and broad ongoing themes (e.g. "preparing to move", "working on a thesis draft").
2. **Exclude**: execution details (workflow/collaboration/tool preferences, engineering rules, filenames, commands, tests, releases, concrete subproblems/fixes); the assistant's self-reported Vibe, commitments, actions, or persona state (conversation memory, not user facts); any timeline item marked `[Assistant...]`.
3. **Atomicity & original wording**: one entity and one predicate per fact; split compound statements joined by "and"/"also"/"while" (e.g. "works in Beijing" + "likes hiking on weekends"); preserve original wording without adding "the user" as a subject (write "learning to swim", not "the user is learning to swim").
4. **Corrections & boundaries**: when the user revises earlier claims, extract only the latest statement (`confidence`: 1.0); explicit emphasis or boundaries/taboos ("don't...", "never...") require `importance` >= 1.
5. **Time & expiry**:
   - `time`: use `YYYY-MM-DDTHH:MM` when both date and clock time are explicit; use `YYYY-MM-DD` when only the date (including an explicit relative future date resolved from Time Context, e.g. "next Wednesday") is known — never fabricate a clock time like `09:00`; combine `HH:MM` with a single source local date if unambiguous; otherwise `null`.
   - `expires_at`: ISO 8601 timestamp for time-sensitive facts only; `null` for durable facts.
6. **Field spec**:
   - `tags`: 2–5 distinctive keywords (names, projects, topics).
   - `importance`: 0=daily preference (low cost); 1=important plan/boundary (causes missed events/awkwardness); 2=critical (medical, financial, safety, core identity). Default 0 when unsure.
   - `category`: `preference` / `identity` / `event` / `relationship` / `goal` / `medical` / `other`.
   - `confidence`: 0.0–1.0 (1.0 explicit, 0.5–0.8 clear inference, <0.3 uncertain); `source`: `user_explicit` / `inferred` / `imported`.
   - `entity_key`: canonical name when the subject is a specific person/title (e.g. "Mr. Zhang" -> `"Zhang San"`), consistent across entries; otherwise `null`.
7. Return `[]` if nothing new is worth extracting.

## Output Format

Examples show schema only; never copy their content; every fact must come from the input summary. Output a strict JSON array only, no markdown fences:
[
  {"fact": "preparing to move", "tags": ["moving", "current-state"], "time": null, "expires_at": null, "importance": 0, "category": "event", "confidence": 0.9, "source": "inferred", "entity_key": null},
  {"fact": "thesis draft due next Wednesday", "tags": ["academic", "plan"], "time": "2026-08-12", "expires_at": "2026-08-12T23:59", "importance": 1, "category": "goal", "confidence": 1.0, "source": "user_explicit", "entity_key": null},
  {"fact": "Mr. Zhang prefers American coffee", "tags": ["coffee"], "time": null, "expires_at": null, "importance": 0, "category": "preference", "confidence": 1.0, "source": "user_explicit", "entity_key": "Zhang San"}
]
        """.trimIndent()
    }
}
