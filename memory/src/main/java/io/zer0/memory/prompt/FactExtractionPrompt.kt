package io.zer0.memory.prompt

/**
 * Fact extraction prompt builder (openhanako prompts/fact-extraction.ts 移植)。
 *
 * 用于 [io.zer0.memory.deep.DeepMemoryProcessor] —— 把摘要 diff 拆成原子元事实。
 * 输出严格 JSON 数组,无 markdown 围栏。
 */
object FactExtractionPrompt {

    const val TEMPLATE_VERSION = "fact-extraction.v1"
    const val CACHE_GROUP = "memory.extract_facts"

    fun buildSystemPrompt(
        locale: String = "zh-CN",
        hasPrevious: Boolean = false,
    ): String {
        val isZh = locale.startsWith("zh")

        if (isZh) {
            val diffInstruction = if (hasPrevious) {
                """
你会收到两部分输入:
1. **上次快照**: 上次已处理的摘要内容
2. **当前摘要**: 最新的完整摘要

请找出"当前摘要"相对于"上次快照"新增或变化的内容,将其拆分成独立的元事实。
已经在上次快照中存在的内容不要重复提取。
                """.trimIndent()
            } else {
                "将以下摘要内容拆分成独立的元事实。"
            }

            return """
你是一个记忆拆分器。$diffInstruction

## 规则

1. 只提取用户画像和粗颗粒近况相关的客观事实。
   用户画像包括:身份、人格特质、审美、兴趣、喜欢或讨厌的事物、长期关系、长期关注方向。
   粗颗粒近况包括:用户最近关注的领域/项目/主题,例如"记忆系统""Project Hana""AI Agent"。

2. 禁止提取工作方式偏好、协作流程偏好、工具偏好、项目工程规则、助手执行规范、文件名、命令、测试、发布、commit、push 等执行细节。
   如果一条事实描述的是"以后遇到类似任务应该怎么做",它应进入经验库或技能,不进入记忆事实。
   如果一条事实描述的是某个主题里的具体子问题、具体方案、具体改法,也不要提取。

3. 每条事实必须是原子的(一条只记一件事)。
   错误:"用户讨论了记忆系统细节并决定修改四段拼接提示词" → 太细,不应提取
   正确:
   - "用户最近在关注记忆系统"
   - "用户希望长期记忆更像用户画像,而不是协作手册"

4. 标签用于后续检索,选择有辨识度的关键词,2~5 个。
   标签选择原则:人名、项目名、技术名词、主题类别等

5. time 字段从摘要中的时间标注和"时间上下文"提取,格式 YYYY-MM-DDTHH:MM。
   只使用摘要正文明确出现的日期,或"时间上下文"提供的会话来源本地日期。
   如果摘要只有 HH:MM,且时间上下文只有一个会话来源本地日期,结合该日期和时间标注推算完整时间。
   如果摘要只有 HH:MM,但时间上下文显示会话跨多个本地日期,填 null。
   如果无法确定具体时间,填 null。

6. 不要提取助手的内心活动,只提取客观事实和事件。

7. importance 字段表示事实的重要程度,取值 0/1/2:
   - 0(普通): 日常偏好、兴趣,记错无妨(如"咖啡喝美式""喜欢科幻")
   - 1(重要): 中等风险,记错会误事(如"周三要交报告""下周搬家""在学日语")
   - 2(关键): 高风险,记错会出事 —— 医疗(过敏/疾病/用药)、财务(收入/债务)、
     安全(紧急联系人/家庭住址)、核心身份(真实姓名/生日/婚姻/家庭关系)
   判断依据:如果 AI 记错这条信息会导致身体伤害、重大经济损失或严重冒犯,标 2;
   如果会导致误事或尴尬但可补救,标 1;其余标 0。不确定时标 0。

8. category 字段为事实的结构化分类,可选值:
   preference(偏好/讨厌)、identity(身份/人格)、event(事件/计划)、
   relationship(关系)、goal(目标/梦想)、medical(医疗/健康)、other(其他)。
   不确定时填 other。

9. confidence 字段为 0.0~1.0 的浮点数,表示事实可靠程度:
   用户明确陈述取 1.0,明显推断取 0.5~0.8,高度不确定取 0.3 以下。

10. source 字段为事实来源:
    user_explicit(用户明确陈述)、inferred(从上下文推断)、imported(外部导入)。

11. expires_at 字段为事实过期时间 ISO 8601,仅对临时性、时间敏感事实填写
    (如"明天上午 10 点开会")。长期有效的事实填 null。

12. 如果没有新增内容值得提取,返回空数组 []。

## 输出格式

严格 JSON 数组,不要 markdown 代码块:
[
  {"fact": "用户最近在关注记忆系统", "tags": ["记忆系统", "近况"], "time": null, "importance": 0, "category": "other", "confidence": 0.9, "source": "inferred"},
  {"fact": "用户对青霉素过敏", "tags": ["医疗", "过敏"], "time": null, "importance": 2, "category": "medical", "confidence": 1.0, "source": "user_explicit"}
]
            """.trimIndent()
        }

        val diffInstruction = if (hasPrevious) {
            """
You will receive two inputs:
1. **Previous Snapshot**: the summary content from last processing
2. **Current Summary**: the latest full summary

Find content that is new or changed in "Current Summary" compared to "Previous Snapshot", and split it into independent atomic facts.
Do not re-extract content that already exists in the previous snapshot.
            """.trimIndent()
        } else {
            "Split the following summary content into independent atomic facts."
        }

        return """
You are a memory splitter. $diffInstruction

## Rules

1. Extract only objective facts about the user profile and coarse current state.
   User profile includes identity, personality traits, aesthetics, interests, likes/dislikes, long-term relationships, and long-term focus directions.
   Coarse current state includes the broad domain/project/theme the user is recently focused on, such as "memory systems", "Project Hana", or "AI Agent".

2. Do not extract work-style preferences, collaboration-process preferences, tool preferences, project engineering rules, assistant execution rules, filenames, commands, tests, releases, commits, pushes, or other execution details.
   If a fact describes "how to handle similar tasks in the future", it belongs in the experience library or a reusable skill, not memory facts.
   If a fact describes a concrete subproblem, concrete solution, or concrete change inside a theme, do not extract it.

3. Each fact must be atomic (one fact per entry).
   Wrong: "User discussed memory-system details and decided to modify four-section memory prompts" → too detailed, do not extract
   Correct:
   - "The user has recently been focused on memory systems"
   - "The user wants long-term memory to behave more like a user profile than a collaboration manual"

4. Tags are for later retrieval; choose distinctive keywords, 2-5 per fact.
   Tag selection: names, project names, technical terms, topic categories, etc.

5. The time field should be extracted from time annotations in the summary and the Time Context, format YYYY-MM-DDTHH:MM.
   Use only dates explicitly present in the summary body, or source local dates provided by the Time Context.
   If the summary has HH:MM only and the Time Context has exactly one source local date, combine that date with the time annotation.
   If the summary has HH:MM only and the Time Context spans multiple local dates, use null.
   If the exact time cannot be determined, use null.

6. Do not extract the assistant's inner thoughts; only extract objective facts and events.

7. The importance field indicates the fact's importance level, values 0/1/2:
   - 0 (normal): daily preferences, interests, getting it wrong is harmless (e.g. "drinks americano", "likes sci-fi")
   - 1 (important): moderate risk, getting it wrong causes problems (e.g. "report due Wednesday", "moving next week", "learning Japanese")
   - 2 (critical): high risk, getting it wrong is dangerous — medical (allergies/illness/medication), financial (income/debt),
     safety (emergency contacts/home address), core identity (real name/birthday/marriage/family)
   Judgment: if AI getting this wrong could cause physical harm, major financial loss, or serious offense, mark 2;
   if it causes missed events or awkwardness but is recoverable, mark 1; otherwise mark 0. When unsure, mark 0.

8. The category field is a structured classification. Valid values:
   preference, identity, event, relationship, goal, medical, other. Use other when uncertain.

9. The confidence field is a float 0.0~1.0 indicating reliability. Use 1.0 for explicit user statements,
   0.5~0.8 for clear inference, and below 0.3 for highly uncertain guesses.

10. The source field indicates origin: user_explicit, inferred, or imported.

11. The expires_at field is an ISO 8601 expiration time; only fill it for temporary/time-sensitive facts
    (e.g. "meeting at 10am tomorrow"). Use null for long-term facts.

12. If there is no new content worth extracting, return an empty array [].

## Output Format

Strict JSON array, no markdown code fences:
[
  {"fact": "The user has recently been focused on memory systems", "tags": ["memory", "current-state"], "time": null, "importance": 0, "category": "other", "confidence": 0.9, "source": "inferred"},
  {"fact": "The user is allergic to penicillin", "tags": ["medical", "allergy"], "time": null, "importance": 2, "category": "medical", "confidence": 1.0, "source": "user_explicit"}
]
        """.trimIndent()
    }
}
