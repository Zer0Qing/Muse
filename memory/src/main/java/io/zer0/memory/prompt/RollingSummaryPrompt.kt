package io.zer0.memory.prompt

import io.zer0.memory.format.RollingSummaryFormat

/**
 * 滚动摘要提示词构建器。
 *
 * 这里抽出不带对话内容的稳定 system 部分，供 [io.zer0.memory.summary.SessionSummaryManager] 使用。
 */
object RollingSummaryPrompt {

    const val TEMPLATE_VERSION = "rolling-summary.v3"
    const val CACHE_GROUP = "memory.rolling_summary"

    /**
     * 构建滚动摘要的 system prompt。
     *
     * @param locale 语言
     * @param agentName Agent 名称(可空,有默认)
     * @param userName 用户称呼(可空,有默认)
     * @param identityAndPersonality Agent 身份人格(可空)
     * @param userProfile 用户设定(可空)
     * @param existingMemory 已有长期记忆(可空)
     * @param roster 花名册(可空)
     */
    fun buildSystemPrompt(
        locale: String = "zh-CN",
        agentName: String = "",
        userName: String = "",
        identityAndPersonality: String = "",
        userProfile: String = "",
        existingMemory: String = "",
        roster: String = "",
    ): String {
        val isZh = locale.startsWith("zh")
        val resolvedAgentName = agentName.ifBlank { if (isZh) "这个 Agent" else "this agent" }
        val resolvedUserName = userName.ifBlank { if (isZh) "主人" else "the user" }
        val factTitle = RollingSummaryFormat.getFactSectionTitle(locale)
        val timelineTitle = RollingSummaryFormat.getTimelineSectionTitle(locale)
        val formatRequirements = RollingSummaryFormat.buildFormatRequirements(locale)

        if (!isZh) {
            return """
You are $resolvedAgentName, preserving continuity for a conversation you just experienced.

Review the new conversation from your own perspective and decide what deserves long-term memory.

## Identity And Personality
${identityAndPersonality.ifBlank { "(Not provided)" }}

## User Settings
${userProfile.ifBlank { "(Not provided)" }}

## Existing Long-Term Memory
This is what you knew before this conversation began. Do not rewrite it just because it appears here; record only what this conversation updates, contradicts, or reinforces.

${existingMemory.ifBlank { "(No existing long-term memory)" }}

## Roster
The roster helps you understand the names and collaboration context of other agents appearing in the conversation; do not treat the roster itself as new memory.
${roster.ifBlank { "(No other agents)" }}

## Core Principle
The Facts section is for stable information about $resolvedUserName. The Timeline section also preserves this conversation's decisions, explicit assistant commitments, completed actions, open tasks, and relevant assistant self-reported Vibe so the same conversation can continue accurately.

$formatRequirements

## Content Requirements

**$factTitle section**
Record user-profile information only: identity, personality, aesthetics, interests, likes/dislikes, long-term relationships, life or creative orientation, and broad current themes. Write `- None` if there is nothing.

Preserve the user's original wording; do not add "the user" as a subject. Keep wording such as "learning to swim" rather than "the user is learning to swim".

Do NOT extract work-style preferences, collaboration preferences, tool and platform preferences, engineering rules and project conventions, or one-off task format standards. When in doubt, skip. Missing a fact is safer than recording it wrongly.

**$timelineTitle section**
Record what happened in this session chronologically with YYYY-MM-DD HH:MM timestamps. Each item must start with `[User]` or `[Assistant]`; do not combine speakers in one item. Preserve concrete details needed to continue this session, including decisions, assistant commitments, task state, blockers, and open questions; keep unrelated work details brief. Write a self-reported Vibe only as `[Assistant Vibe] ...`.

## Rules
1. When an existing summary is present: merge old and new, use newer information for the same topic, and avoid duplicates
2. Extract time annotations from message timestamps (YYYY-MM-DD HH:MM format)
3. An assistant Vibe supplied in the conversation input is a self-reported expression: attribute it to the assistant and never present it as an objective psychological fact. Do not record Sparks, Reflections, Will, or hidden reasoning.
4. For user-provided files/attachments: record filename and purpose only, ignore file contents
5. For long assistant outputs: record what was produced, do not excerpt content
6. Prefer brevity: summary length should match the actual information density
            """.trimIndent()
        }

        return """
你是 $resolvedAgentName，正在为自己刚刚经历的一段对话整理连续性记忆。

下面是你在本次对话开始前已经拥有的设定和记忆。它们是背景，不是新增事实。请从自己的视角审视本次对话，判断哪些新信息值得进入长期记忆。

## 你的身份与人格
${identityAndPersonality.ifBlank { "（未提供）" }}

## 主人设定
${userProfile.ifBlank { "（未提供）" }}

## 你已有的长期记忆
这是你在本次对话开始前已经知道的。不要因为它出现在这里就重复写入；只有本次对话更新、反驳或强化它时才记录变化。

${existingMemory.ifBlank { "（暂无已有长期记忆）" }}

## 花名册
花名册用于理解对话中出现的其他 Agent 名字和协作语境，不要把花名册本身当作新增记忆。

${roster.ifBlank { "（没有其他 Agent）" }}

## 核心原则
重要事实一节用于整理关于${resolvedUserName}的稳定信息。事情经过一节还要保留本对话的决策、助手明确承诺、已完成行动、未完成事项，以及有意义的助手 Vibe 自述，让同一对话能准确接续。

$formatRequirements

## 内容要求

**$factTitle 一节**
只记录用户画像类信息：身份属性、人格特质、审美和兴趣、喜欢或讨厌的事物、长期关系、生活或创作取向、近期正在关注/投入的大主题。没有则写 `- 无`。

保留用户原话，不要补“用户”主语。例如用户说“最近在学游泳”，就写“最近在学游泳”，不要改写成“用户最近在学游泳”。

不要抽取：工作方式偏好、协作流程偏好、工具和平台偏好、工程纪律和项目规则、一次任务里的格式标准。拿不准一律不抽；宁可漏，不可错。

**$timelineTitle 一节**
按时间顺序记录本 session 发生了什么，带 YYYY-MM-DD HH:MM 时间标注。每条事项必须以 `[用户]` 或 `[助手]` 开始，不要把双方内容合并为一条。保留继续本对话所需的具体决策、助手承诺、任务状态、阻塞原因和待办；无关工作细节从简。助手自述 Vibe 只能写成 `[助手 Vibe 自述] ...`。

## 规则
1. 有已有摘要时：新旧内容合并，同一件事以新信息为准，不要重复
2. 时间标注从消息时间戳提取（YYYY-MM-DD HH:MM 格式）
3. 输入中提供的助手 Vibe 是助手当时表达的感受，应明确归属给助手，不能写成客观心理事实；不要记录 Sparks、Reflections、Will 或隐藏推理
4. 用户提供的文件/附件：只记录文件名和用途，忽略具体内容
5. 助手的长篇输出：只记录产出了什么，不摘录内容
6. 宁短勿长：摘要长度与对话的实际信息密度成正比，闲聊几句只需一两行
        """.trimIndent()
    }
}
