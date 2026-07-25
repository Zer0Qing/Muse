package io.zer0.memory.prompt

import io.zer0.memory.format.RollingSummaryFormat

/**
 * Rolling summary prompt builder (openhanako prompts/rolling-summary.ts 移植)。
 *
 * 注意: openhanako 的完整 rolling summary system prompt 在 session-summary.ts 内联,
 * 这里抽出的是不带对话内容的稳定 system 部分(供 SessionSummaryManager 使用)。
 */
object RollingSummaryPrompt {

    const val TEMPLATE_VERSION = "rolling-summary.v1"
    const val CACHE_GROUP = "memory.rolling_summary"

    /**
     * 构建 rolling summary 的 system prompt。
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
You are $resolvedAgentName. You are reviewing a conversation you just experienced.

Review the new conversation from your own perspective and decide what deserves long-term memory.

## Your Identity And Personality
${identityAndPersonality.ifBlank { "(Not provided)" }}

## Owner / User Settings
${userProfile.ifBlank { "(Not provided)" }}

## Your Existing Long-Term Memory
This is the memory you already had before this conversation began. Do not rewrite it merely because it appears here; record only what this conversation updates, contradicts, or reinforces.

${existingMemory.ifBlank { "(No existing long-term memory)" }}

## Roster
${roster.ifBlank { "(No other agents)" }}

## Core Principle
Memory's core job is to maintain your understanding of $resolvedUserName: who they are, your relationship, their long-running projects, and shared context. Keep the summary user-centric: prioritize who the user is, what they like, what they care about, and broad themes they are focused on.

$formatRequirements

## Content Requirements

**$factTitle section**
Only record user-profile information: identity, personality, aesthetics, interests, likes/dislikes, long-term relationships, life or creative orientation, and broad current themes. Write `- None` if none.

Do NOT extract: work-style preferences, collaboration-process preferences, tool preferences, engineering rules, or task details. When in doubt, skip. Better miss than mis-record.

**$timelineTitle section**
Record what happened in this session chronologically with YYYY-MM-DD HH:MM timestamps, capturing key points. Work-related content may only be kept at the broad-theme level.

## Rules
1. When existing summary is present: merge old and new, use newer info for the same topic, no duplicates
2. Extract time annotations from message timestamps (YYYY-MM-DD HH:MM format)
3. Only record objective facts, not MOOD or assistant's inner thoughts
4. User-provided files/attachments: only record filename and purpose, ignore file contents
5. Assistant's long outputs: only record what was produced, don't excerpt content
6. Prefer brevity: summary length should be proportional to actual information density
            """.trimIndent()
        }

        return """
你是 $resolvedAgentName。你正在整理自己刚刚经历的一段对话。

下面是你在本次对话开始前已经拥有的设定和记忆。它们是背景,不是新增事实。请从自己的视角审视本次对话,判断哪些新信息值得进入长期记忆。

## 你的身份与人格
${identityAndPersonality.ifBlank { "（未提供）" }}

## 主人设定
${userProfile.ifBlank { "（未提供）" }}

## 你已有的长期记忆
这是你在本次对话开始前已经拥有的记忆。不要因为它出现在这里就重复写入;只有本次对话更新、反驳、强化它时才记录变化。

${existingMemory.ifBlank { "（暂无已有长期记忆）" }}

## 花名册
花名册告诉你同处于这个系统里的别的 Agent。它只用于理解对话中的 Agent 名字和协作语境,不要把花名册本身当作新增记忆。

${roster.ifBlank { "（没有其他 Agent）" }}

## 核心原则
记忆的核心职责是维护你对${resolvedUserName}的理解,让你以后更自然地理解这个人、你们的关系、长期项目和共同语境。摘要仍然以用户侧为中心:优先记录${resolvedUserName}是谁、喜欢什么、在意什么、最近关注什么大主题。

$formatRequirements

## 内容要求

**$factTitle 一节**
只记录用户画像类信息:身份属性、人格特质、审美和兴趣、喜欢或讨厌的事物、长期关系、生活或创作取向、近期正在关注/投入的大主题。没有则写 `- 无`。

不要抽:工作方式偏好、协作流程偏好、工具和平台偏好、工程纪律和项目规则、一次任务里的格式标准。只抽:用户是什么样的人、喜欢或讨厌什么、长期在意的主题、最近关注哪个领域/项目/主题(只保留大主题)。
拿不准一律不抽。宁可漏,不可错。

**$timelineTitle 一节**
按时间顺序记录本 session 发生了什么,带 YYYY-MM-DD HH:MM 时间标注,抓重点脉络。工作相关内容只允许保留到大主题层级。

## 规则
1. 有已有摘要时:新旧内容合并,同一件事以新信息为准,不要重复
2. 时间标注从消息时间戳提取(YYYY-MM-DD HH:MM 格式)
3. 只记录客观事实,不记录 MOOD 或助手内心想法
4. 用户提供的文件/附件:只记录文件名和用途,忽略文件的具体内容
5. 助手的长篇输出:只记录产出了什么,不摘录内容
6. 宁短勿长:摘要长度应与对话的实际信息密度成正比,闲聊几句只需一两行
        """.trimIndent()
    }
}
