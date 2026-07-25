package io.zer0.muse.ui

import java.util.Locale

/**
 * 模型分组工具 — 按 id 前缀正则将模型归入语义族(GPT / Claude / Gemini 等)。
 *
 * 参考 kelivo 的 lib/utils/model_grouping.dart 实现,将散乱的长模型列表
 * 收拢为有限的若干族,配合折叠 UI 让用户在 50+ 模型时仍能快速找到目标。
 *
 * 分组规则(按 id 前缀正则匹配,大小写不敏感;前缀后必须是分隔符 [-_.] 或结尾):
 *  - GPT:        gpt / openai / o1 / o3 / o4
 *  - Claude:     claude / opus / sonnet / haiku
 *  - Gemini:     gemini
 *  - DeepSeek:   deepseek
 *  - Kimi:       kimi / moonshot
 *  - Qwen:       qwen / qwq
 *  - Doubao:     doubao / skylark
 *  - GLM:        glm / chatglm
 *  - Yi:         yi(零一万物)
 *  - Baichuan:   baichuan(百川)
 *  - Hunyuan:    hunyuan / yuan(腾讯混元)
 *  - Mistral:    mistral / mixtral / codestral / pixtral / magistral
 *  - MiniMax:    minimax / abab
 *  - Grok:       grok
 *  - Embeddings: embedding / embed
 *  - Other:      其余
 *
 * 注:前缀匹配要求"前缀后是分隔符(-,_,.)或结尾",避免 "o1" 误匹配 "o123"、
 * "gpt" 误匹配 "gptxyz" 等长前缀误判。
 * v1.134 P2-3: 新增 Yi / Baichuan / Hunyuan 三组国产模型分组。
 */

/**
 * 分组规则:组名 → 前缀正则。
 * 正则形如 `^(?:前缀1|前缀2|...)(?:[-._]|$)`,从前缀开始匹配,
 * 后续必须是分隔符(-,_,.)或字符串结尾。
 */
private val GROUP_RULES: List<Pair<String, Regex>> = listOf(
    "GPT" to Regex("^(?:gpt|openai|o[134])(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Claude" to Regex("^(?:claude|opus|sonnet|haiku)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Gemini" to Regex("^(?:gemini)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "DeepSeek" to Regex("^(?:deepseek)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Kimi" to Regex("^(?:kimi|moonshot)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Qwen" to Regex("^(?:qwen|qwq)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Doubao" to Regex("^(?:doubao|skylark)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "GLM" to Regex("^(?:glm|chatglm)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    // v1.134 P2-3: 国产模型分组扩展(零一万物 / 百川 / 腾讯混元)
    "Yi" to Regex("^(?:yi)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Baichuan" to Regex("^(?:baichuan)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Hunyuan" to Regex("^(?:hunyuan|yuan)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Mistral" to Regex(
        "^(?:mistral|mixtral|codestral|pixtral|magistral)(?:[-._]|$)",
        RegexOption.IGNORE_CASE,
    ),
    "MiniMax" to Regex("^(?:minimax|abab)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Grok" to Regex("^(?:grok)(?:[-._]|$)", RegexOption.IGNORE_CASE),
    "Embeddings" to Regex("^(?:embedding|embed)(?:[-._]|$)", RegexOption.IGNORE_CASE),
)

/**
 * 分组展示顺序(未列出的组按字母序排在最后)。
 * 与 [GROUP_RULES] 顺序一致,末尾追加 "Other" 兜底组。
 */
val MODEL_GROUP_ORDER: List<String> = listOf(
    "GPT", "Claude", "Gemini", "DeepSeek", "Kimi", "Qwen", "Doubao",
    "GLM", "Yi", "Baichuan", "Hunyuan",
    "Mistral", "MiniMax", "Grok", "Embeddings", "Other",
)

/**
 * 按模型 id 返回其所属族名(如 "GPT" / "Claude" / "Other")。
 *
 * 匹配逻辑:对模型 id 从前缀开始正则匹配,要求前缀后紧跟分隔符(-,_,.)或字符串结尾,
 * 避免 "o1" 误匹配 "o123"、"gpt" 误匹配 "gptxyz" 等。
 *
 * @param modelId 模型 id(大小写不敏感)
 * @return 分组名,未匹配规则时返回 "Other"
 */
fun groupFor(modelId: String): String {
    return GROUP_RULES.firstOrNull { (_, regex) ->
        regex.containsMatchIn(modelId)
    }?.first ?: "Other"
}

/**
 * 将模型 id 列表按 [groupFor] 分组,组内保持原顺序,组间按 [MODEL_GROUP_ORDER] 排序。
 *
 * @param modelIds 模型 id 列表(已按 Provider 内部顺序排列)
 * @return 有序的 (组名, 该组模型 id 列表) 键值对
 */
fun groupModels(modelIds: List<String>): List<Pair<String, List<String>>> {
    val byGroup: Map<String, List<String>> = modelIds.groupBy { groupFor(it) }
    val known = MODEL_GROUP_ORDER.filter { it in byGroup }
    val extras = byGroup.keys
        .filter { it !in MODEL_GROUP_ORDER }
        .sortedBy { it.lowercase(Locale.US) }
    return (known + extras).map { group -> group to byGroup[group].orEmpty() }
}
