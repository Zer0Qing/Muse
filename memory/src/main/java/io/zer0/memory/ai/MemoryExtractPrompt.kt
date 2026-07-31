package io.zer0.memory.ai

/**
 * v1.0.52 P2-3: 记忆 AI 提取提示词构建器(借鉴 Operit MemoryLibrary prompt)。
 *
 * 用于 [MemoryAutoSaveScheduler.extractEntities] —— 从对话历史中提取结构化记忆:
 *  - 实体(原子事实)+ 实体间关系(知识图谱边)
 *  - 对已有事实的更新 + 相似记忆合并
 *  - 用户画像 Markdown 更新
 *
 * 输出严格 JSON 对象(符合 [ParsedAnalysis] 结构),无 markdown 围栏。
 *
 * 与 [io.zer0.memory.prompt.FactExtractionPrompt] 的区别:
 *  - FactExtractionPrompt: 从摘要 diff 提取原子事实(无关系/合并/画像)
 *  - MemoryExtractPrompt: 从完整对话提取结构化记忆(含关系/合并/画像),功能更全
 * 两者可共存:FactExtraction 用于 daily deep pipeline,MemoryExtract 用于实时 autoSave
 */
object MemoryExtractPrompt {

    const val TEMPLATE_VERSION = "memory-extract.v1"
    const val CACHE_GROUP = "memory.extract_analysis"

    /**
     * 构建 system prompt。
     *
     * @param locale 语言(zh-CN / en-US ...)
     * @param existingFactsPreview 已有事实预览(可选,用于去重/合并/更新参考)
     *        格式: "- {title}: {content}" 每行一条,最多 20 条
     */
    fun buildSystemPrompt(
        locale: String = "zh-CN",
        existingFactsPreview: String? = null,
    ): String {
        val isZh = locale.startsWith("zh")

        val existingSection = if (!existingFactsPreview.isNullOrBlank()) {
            if (isZh) {
                """
## 已有记忆(用于去重/合并/更新参考)

$existingFactsPreview

提取时请检查与已有记忆的重复/冲突:
 - 如果新实体与已有记忆语义重复,放入 mergedEntities 合并
 - 如果新实体纠正/更新了已有记忆,放入 updatedEntities
 - 如果与已有记忆无重复,放入 extractedEntities
                """.trimIndent()
            } else {
                """
## Existing Memories (for dedup/merge/update reference)

$existingFactsPreview

Check for duplicates/conflicts with existing memories when extracting:
 - If a new entity semantically duplicates an existing memory, put it in mergedEntities
 - If a new entity corrects/updates an existing memory, put it in updatedEntities
 - If no duplicate with existing, put it in extractedEntities
                """.trimIndent()
            }
        } else ""

        if (isZh) {
            return """
你是一个记忆分析师。分析用户与助手的对话,提取结构化记忆。

$existingSection

## 提取规则

1. mainProblem: 用一句话总结本次对话的主问题(用户想解决什么),可选。

2. extractedEntities: 新提取的原子事实(每条只记一件事)。
   - title: 简短标题(≤20字,用于展示和匹配)
   - content: 事实正文(保留原始表述,不加主语)
   - credibility: 0.0~1.0,用户明确陈述取 1.0,推断取 0.5~0.8
   - importance: 0.0~1.0,<0.4 普通,0.4~0.7 重要,>0.7 关键(医疗/财务/安全)
   - folderPath: 分类(preference/identity/event/relationship/goal/medical/other)
   - tags: 2~5 个有辨识度的关键词

3. links: 实体间关系(知识图谱边)。
   - sourceTitle / targetTitle 必须与某个 entity 的 title 一致
   - linkType: causes(因果)/ explains(解释)/ part_of(包含)/ related_to(关联)/ contradicts(矛盾)
   - weight: 0.0~1.0 关系强度

4. updatedEntities: 对已有记忆的更新(按 matchTitle 模糊匹配)。
   - matchTitle: 已有记忆的 title(可部分匹配)
   - newContent: 替换后的正文
   - newImportance / newFolderPath / newTags: 可选更新(不更新填 null)

5. mergedEntities: 相似记忆合并(去重)。
   - sourceTitles: 要合并的已有/新记忆 title 列表
   - mergedTitle / mergedContent: 合并后的新标题和正文

6. profileMarkdown: 用户画像 Markdown 更新(可选,覆盖式)。
   包含身份/人格/兴趣/长期目标等,格式如:
   ## 身份
   - 姓名: 张三
   ## 兴趣
   - 记忆系统、AI Agent

7. 禁止提取:
   - 工作流程偏好、工具偏好、工程规则、执行细节
   - 助手的内心活动、临时调试信息
   - 一次性任务细节(如"修改了 xxx 文件第 y 行")

8. 如果对话无值得记忆的内容,返回空结构(所有数组为空,mainProblem 为 null)。

## 输出格式

严格 JSON 对象,不要 markdown 代码块:
{
  "mainProblem": {"title": "...", "content": "...", "credibility": 0.8, "importance": 0.5, "folderPath": "event", "tags": ["..."]},
  "extractedEntities": [
    {"title": "对青霉素过敏", "content": "对青霉素过敏", "credibility": 1.0, "importance": 0.9, "folderPath": "medical", "tags": ["医疗","过敏"]}
  ],
  "links": [
    {"sourceTitle": "对青霉素过敏", "targetTitle": "用阿莫西林治疗", "linkType": "contradicts", "weight": 0.9}
  ],
  "updatedEntities": [],
  "mergedEntities": [],
  "profileMarkdown": null
}
            """.trimIndent()
        }

        return """
You are a memory analyst. Analyze the conversation between the user and the assistant, and extract structured memories.

$existingSection

## Extraction Rules

1. mainProblem: A one-sentence summary of the main problem in this conversation (what the user wanted to solve), optional.

2. extractedEntities: Newly extracted atomic facts (one fact per entry).
   - title: Short title (≤20 chars, for display and matching)
   - content: Fact body (preserve original wording, do not add subject)
   - credibility: 0.0~1.0, 1.0 for explicit user statements, 0.5~0.8 for inference
   - importance: 0.0~1.0, <0.4 normal, 0.4~0.7 important, >0.7 critical (medical/financial/safety)
   - folderPath: Category (preference/identity/event/relationship/goal/medical/other)
   - tags: 2-5 distinctive keywords

3. links: Relationships between entities (knowledge graph edges).
   - sourceTitle / targetTitle must match an entity's title
   - linkType: causes / explains / part_of / related_to / contradicts
   - weight: 0.0~1.0 relationship strength

4. updatedEntities: Updates to existing memories (matched by matchTitle, fuzzy match allowed).
   - matchTitle: Title of existing memory (partial match allowed)
   - newContent: Replacement body
   - newImportance / newFolderPath / newTags: Optional updates (null to skip)

5. mergedEntities: Similar memory merges (deduplication).
   - sourceTitles: List of existing/new memory titles to merge
   - mergedTitle / mergedContent: New title and body after merge

6. profileMarkdown: User profile Markdown update (optional, overwrites).
   Includes identity/personality/interests/long-term goals, formatted like:
   ## Identity
   - Name: John
   ## Interests
   - Memory systems, AI Agents

7. Do NOT extract:
   - Workflow preferences, tool preferences, engineering rules, execution details
   - Assistant's inner thoughts, temporary debugging info
   - One-off task details (e.g. "modified line Y of file X")

8. If nothing in the conversation is worth remembering, return an empty structure (all arrays empty, mainProblem null).

## Output Format

Strict JSON object, no markdown code fences:
{
  "mainProblem": {"title": "...", "content": "...", "credibility": 0.8, "importance": 0.5, "folderPath": "event", "tags": ["..."]},
  "extractedEntities": [
    {"title": "allergic to penicillin", "content": "allergic to penicillin", "credibility": 1.0, "importance": 0.9, "folderPath": "medical", "tags": ["medical","allergy"]}
  ],
  "links": [
    {"sourceTitle": "allergic to penicillin", "targetTitle": "treated with amoxicillin", "linkType": "contradicts", "weight": 0.9}
  ],
  "updatedEntities": [],
  "mergedEntities": [],
  "profileMarkdown": null
}
        """.trimIndent()
    }

    /**
     * 构建 autoCategorize(自动分类未分类记忆)的 system prompt。
     *
     * 用于 [MemoryAutoSaveScheduler.autoCategorizeMemories] —— 批量为 folderPath 为空的
     * 记忆分配分类。
     *
     * @param locale 语言
     */
    fun buildCategorizeSystemPrompt(locale: String = "zh-CN"): String {
        val isZh = locale.startsWith("zh")
        if (isZh) {
            return """
你是一个记忆分类器。为每条未分类记忆分配 folderPath(分类目录)。

## 分类规则

folderPath 取值:
 - preference: 偏好/讨厌(如"喜欢美式咖啡""讨厌香菜")
 - identity: 身份/人格(如"姓名张三""性格内向")
 - event: 事件/计划(如"周三交报告""下周搬家")
 - relationship: 关系(如"妻子叫李四""同事王五")
 - goal: 目标/梦想(如"想学日语""计划跑步减肥")
 - medical: 医疗/健康(如"青霉素过敏""高血压")
 - other: 其他(无法归入以上类别)

## 输出格式

严格 JSON 数组,每条包含原 title 和分配的 folderPath:
[
  {"title": "...", "folderPath": "preference"},
  {"title": "...", "folderPath": "medical"}
]

不要 markdown 代码块。如果无法分类,分配 "other"。
            """.trimIndent()
        }
        return """
You are a memory categorizer. Assign a folderPath (category directory) to each uncategorized memory.

## Categories

folderPath values:
 - preference: Likes/dislikes (e.g. "likes americano", "hates cilantro")
 - identity: Identity/personality (e.g. "name is John", "introverted")
 - event: Events/plans (e.g. "report due Wednesday", "moving next week")
 - relationship: Relationships (e.g. "wife is Jane", "coworker Bob")
 - goal: Goals/dreams (e.g. "wants to learn Japanese", "plans to run for weight loss")
 - medical: Medical/health (e.g. "allergic to penicillin", "high blood pressure")
 - other: Other (cannot fit above categories)

## Output Format

Strict JSON array, each entry contains the original title and assigned folderPath:
[
  {"title": "...", "folderPath": "preference"},
  {"title": "...", "folderPath": "medical"}
]

No markdown code fences. If unable to categorize, assign "other".
        """.trimIndent()
    }
}
