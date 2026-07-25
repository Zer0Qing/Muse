package io.zer0.ai.registry

import io.zer0.ai.core.BuiltInTool
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.VisionCapabilities

/**
 * 模型能力注册表 DSL（移植自 RikkaHub ModelDsl.kt）。
 *
 * 基于 token 的模型 ID 匹配并带评分。支持：
 *  - 子序列 token 匹配：tokens("gpt", "4", "o")
 *  - 反向匹配：notTokens("mini")
 *  - 精确 ID 匹配：exact("gpt-4o-2024-05-13")
 *  - 正则 token：tokenRegex("^o$")
 *  - 备选项：tokens("gpt|chatgpt")
 *
 * 用法：
 * ```kotlin
 * val GPT4O = defineModel {
 *     tokens("gpt", "4", "o")
 *     visionInput()
 *     toolAbility()
 * }
 * ```
 */

// --- 公共 API ---

interface ModelSelector {
    fun match(modelId: String): Boolean
}

class ModelDefinition(
    private val matcher: TokenMatcher,
    val inputModalities: Set<String>,
    val outputModalities: Set<String>,
    val abilities: Set<ModelAbility>,
    val builtInTools: Set<BuiltInTool>,
    /** v1.0.4: 视觉 grounding 能力声明(null 表示未声明,不覆盖 Model 已有值)。 */
    val visionCapabilities: VisionCapabilities? = null,
) : ModelSelector {
    override fun match(modelId: String): Boolean {
        val tokens = tokenize(modelId)
        return matcher.score(modelId, tokens) != null
    }

    fun matchScore(modelId: String): Int? {
        val tokens = tokenize(modelId)
        return matcher.score(modelId, tokens)
    }

    internal fun matchScore(modelId: String, tokens: List<String>): Int? =
        matcher.score(modelId, tokens)
}

class ModelGroup internal constructor(
    private val members: List<ModelSelector>,
) : ModelSelector {
    override fun match(modelId: String): Boolean = members.any { it.match(modelId) }
}

fun defineModel(block: ModelDefinitionBuilder.() -> Unit): ModelDefinition =
    ModelDefinitionBuilder().apply(block).build()

fun defineGroup(block: ModelGroupBuilder.() -> Unit): ModelGroup =
    ModelGroupBuilder().apply(block).build()

fun tokenRegex(pattern: String): TokenSpec = TokenRegexSpec(pattern.toRegex(RegexOption.IGNORE_CASE))

// --- 构建器 ---

class ModelDefinitionBuilder {
    private val matchers = mutableListOf<TokenMatcher>()
    private val inputModalities = mutableSetOf("text")
    private val outputModalities = mutableSetOf("text")
    private val abilities = mutableSetOf<ModelAbility>()
    private val builtInTools = mutableSetOf<BuiltInTool>()
    /** v1.0.4: 视觉 grounding 能力。 */
    private var visionCapabilities: VisionCapabilities? = null

    fun tokens(vararg specs: String) {
        matchers += SequenceMatcher(specs.map(::parseTokenSpec))
    }

    fun tokens(vararg specs: TokenSpec) {
        matchers += SequenceMatcher(specs.toList())
    }

    fun notTokens(vararg specs: String) {
        matchers += NotSequenceMatcher(specs.map(::parseTokenSpec))
    }

    fun notTokens(vararg specs: TokenSpec) {
        matchers += NotSequenceMatcher(specs.toList())
    }

    fun exact(id: String) {
        matchers += ExactIdMatcher(id)
    }

    fun visionInput() {
        inputModalities.add("image")
    }

    fun audioInput() {
        inputModalities.add("audio")
    }

    fun videoInput() {
        inputModalities.add("video")
    }

    fun imageOutput() {
        outputModalities.add("image")
    }

    fun toolAbility() {
        abilities.add(ModelAbility.TOOL)
    }

    fun reasoningAbility() {
        abilities.add(ModelAbility.REASONING)
    }

    fun toolReasoningAbility() {
        abilities.add(ModelAbility.TOOL)
        abilities.add(ModelAbility.REASONING)
    }

    fun builtInTool(vararg tools: BuiltInTool) {
        builtInTools.addAll(tools)
    }

    /**
     * v1.0.4: 声明模型支持视觉 grounding(坐标定位)。
     *
     * @param outputFormat 坐标输出格式:"gemini"(yxyx)/"qwen"(bbox_2d)/"anchor"(visual_anchors)/"hanako"(xyxy 默认)
     */
    fun visionGrounding(outputFormat: String = "hanako") {
        visionCapabilities = VisionCapabilities(grounding = true, outputFormat = outputFormat)
    }

    fun build(): ModelDefinition {
        val matcher = when {
            matchers.isEmpty() -> MatchNone
            matchers.size == 1 -> matchers.first()
            else -> AndMatcher(matchers.toList())
        }
        return ModelDefinition(
            matcher = matcher,
            inputModalities = inputModalities.toSet(),
            outputModalities = outputModalities.toSet(),
            abilities = abilities.toSet(),
            builtInTools = builtInTools.toSet(),
            visionCapabilities = visionCapabilities,
        )
    }
}

class ModelGroupBuilder {
    private val members = mutableListOf<ModelSelector>()
    fun add(vararg models: ModelSelector) { members.addAll(models) }
    fun build(): ModelGroup = ModelGroup(members.toList())
}

// --- Token 规范 ---

sealed interface TokenSpec {
    fun matches(token: String): Boolean
}

private data class TokenAlternatives(val options: Set<String>) : TokenSpec {
    override fun matches(token: String): Boolean = options.contains(token)
}

private data class TokenRegexSpec(val regex: Regex) : TokenSpec {
    override fun matches(token: String): Boolean = regex.matches(token)
}

// --- Token 匹配器 ---

interface TokenMatcher {
    fun score(modelId: String, tokens: List<String>): Int?
}

private object MatchNone : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? = null
}

private class AndMatcher(private val matchers: List<TokenMatcher>) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? {
        var total = 0
        for (matcher in matchers) {
            val s = matcher.score(modelId, tokens) ?: return null
            total += s
        }
        return total
    }
}

private class ExactIdMatcher(private val id: String) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? =
        if (modelId.equals(id, ignoreCase = true)) EXACT_ID_BONUS + tokens.size else null
}

private class SequenceMatcher(private val specs: List<TokenSpec>) : TokenMatcher {
    override fun score(modelId: String, tokens: List<String>): Int? {
        if (specs.isEmpty()) return null
        var specIndex = 0
        for (token in tokens) {
            if (specs[specIndex].matches(token)) {
                specIndex++
                if (specIndex == specs.size) return specs.size
            }
        }
        return null
    }
}

private class NotSequenceMatcher(private val specs: List<TokenSpec>) : TokenMatcher {
    private val inner = SequenceMatcher(specs)
    override fun score(modelId: String, tokens: List<String>): Int? =
        if (inner.score(modelId, tokens) == null) 0 else null
}

// --- 辅助函数 ---

private fun parseTokenSpec(spec: String): TokenSpec {
    val options = spec.split('|')
        .map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .toSet()
    return TokenAlternatives(options)
}

private const val EXACT_ID_BONUS = 1000

/**
 * 将模型 ID 切分为字母/数字/符号片段。
 * 例如 "gpt-4o-mini" → ["gpt", "4", "o", "-", "mini"]
 */
internal fun tokenize(modelId: String): List<String> {
    val tokens = mutableListOf<String>()
    val input = modelId.lowercase()
    var index = 0
    while (index < input.length) {
        val ch = input[index]
        when {
            ch.isLetter() -> {
                val start = index
                index++
                while (index < input.length && input[index].isLetter()) index++
                tokens.add(input.substring(start, index))
            }
            ch.isDigit() -> {
                val start = index
                index++
                while (index < input.length && input[index].isDigit()) index++
                tokens.add(input.substring(start, index))
            }
            else -> {
                tokens.add(ch.toString())
                index++
            }
        }
    }
    return tokens
}
