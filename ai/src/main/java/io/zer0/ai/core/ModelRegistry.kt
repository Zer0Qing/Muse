package io.zer0.ai.core

/**
 * v1.97: 模型能力注册表 — 基于模型名 token 匹配自动推导 modalities/abilities。
 *
 * 参考 rikkahub 的 ModelRegistry 设计,核心思路:
 *  - 将 modelId 切成 token 序列(连续字母 / 连续数字 / 单字符)
 *  - 用 `defineModel { tokens(...); notTokens(...); exact(...); input(...); output(...); ability(...) }`
 *    声明每个模型族的匹配规则与能力
 *  - 匹配采用"有序子序列"策略(允许 token 间穿插其他 token),按匹配分数择优
 *  - 应用层通过 [lookupInputModalities] / [lookupOutputModalities] / [lookupAbilities] /
 *    [enrich] 四个 API 获取推导结果
 *
 * 与 [ModelContextWindowRegistry] 配合使用:前者推导能力,后者推导上下文窗口。
 *
 * 注意:本注册表只覆盖常见模型族;未命中时返回空集合(由调用方决定兜底策略)。
 */
object ModelRegistry {

    // ──────────────────────────────────────────────────────────────
    // 一、Tokenizer:把 modelId 切成小写 token 序列
    // ──────────────────────────────────────────────────────────────

    /**
     * 切分规则:连续字母归一段,连续数字归一段,其他字符单独成 token;全部小写。
     *
     * 示例:
     *  - "gpt-4o" → ["gpt", "-", "4", "o"]
     *  - "gpt-5.1" → ["gpt", "-", "5", ".", "1"]
     *  - "claude-sonnet-4.5-20250929" → ["claude", "-", "sonnet", "-", "4", ".", "5", "-", "20250929"]
     *  - "o3-mini" → ["o", "-", "3", "-", "mini"]
     */
    private fun tokenize(modelId: String): List<String> {
        val tokens = mutableListOf<String>()
        val input = modelId.lowercase()
        var index = 0
        while (index < input.length) {
            val ch = input[index]
            when {
                ch.isLetter() -> {
                    val start = index
                    while (index < input.length && input[index].isLetter()) index++
                    tokens.add(input.substring(start, index))
                }
                ch.isDigit() -> {
                    val start = index
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

    // ──────────────────────────────────────────────────────────────
    // 二、TokenSpec:单 token 匹配原语(字面量"或"集合 / 正则)
    // ──────────────────────────────────────────────────────────────

    sealed interface TokenSpec {
        fun matches(token: String): Boolean
    }

    /** 字面量"或"集合,通过 "a|b|c" 解析。 */
    private data class TokenAlternatives(val options: Set<String>) : TokenSpec {
        override fun matches(token: String): Boolean = options.contains(token)
    }

    /** 正则匹配。 */
    private data class TokenRegex(val regex: Regex) : TokenSpec {
        override fun matches(token: String): Boolean = regex.matches(token)
    }

    private fun parseTokenSpec(spec: String): TokenSpec {
        val options = spec.split('|')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return TokenAlternatives(options)
    }

    // ──────────────────────────────────────────────────────────────
    // 三、TokenMatcher:打分式匹配器(null 表示不匹配)
    // ──────────────────────────────────────────────────────────────

    /**
     * 可匹配的模型族(单个定义或分组)。
     * 外部代码通过 [match] 判断 modelId 是否属于该族,无需关心内部实现。
     */
    fun interface Matchable {
        fun match(modelId: String): Boolean
    }

    private const val EXACT_ID_BONUS = 1000

    internal interface TokenMatcher {
        fun score(modelId: String, tokens: List<String>): Int?
    }

    private object MatchNone : TokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? = null
    }

    /** 多个 matcher 取 AND(任一不匹配则整体不匹配)。 */
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

    /** modelId 完全相等(忽略大小写),得分 = EXACT_ID_BONUS + tokens.size。 */
    private class ExactIdMatcher(private val id: String) : TokenMatcher {
        override fun score(modelId: String, tokens: List<String>): Int? {
            return if (modelId.equals(id, ignoreCase = true)) EXACT_ID_BONUS + tokens.size else null
        }
    }

    /**
     * 顺序子序列匹配:tokens 中必须按顺序出现 specs 中的每个 spec,但中间允许穿插其他 token。
     * 命中得分 = specs.size(越长越具体越优先)。
     */
    private class TokenSequenceMatcher(private val specs: List<TokenSpec>) : TokenMatcher {
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

    /** 负向断言:序列不出现则得 0 分,出现则不匹配。 */
    private class NotTokenSequenceMatcher(private val specs: List<TokenSpec>) : TokenMatcher {
        private val matcher = TokenSequenceMatcher(specs)
        override fun score(modelId: String, tokens: List<String>): Int? {
            return if (matcher.score(modelId, tokens) == null) 0 else null
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 四、ModelDefinition + DSL Builder
    // ──────────────────────────────────────────────────────────────

    /**
     * 单个模型族定义。
     * @param matcher 主匹配器(决定是否命中 + 分数)
     * @param inputModalities 命中时的输入模态(如 ["text"] / ["text","image"])
     * @param outputModalities 命中时的输出模态
     * @param abilities 命中时的能力集合(TOOL / REASONING)
     */
    internal class ModelDefinition(
        private val matcher: TokenMatcher,
        val inputModalities: Set<String>,
        val outputModalities: Set<String>,
        val abilities: Set<ModelAbility>,
    ) : Matchable {
        fun matchScore(modelId: String): Int? = matcher.score(modelId, modelId.lowercase().let { tokenize(it) })
        override fun match(modelId: String): Boolean = matchScore(modelId) != null
    }

    /** 模型族分组(任一成员命中即视为命中)。 */
    internal class ModelGroup(internal val members: List<ModelDefinition>) : Matchable {
        override fun match(modelId: String): Boolean = members.any { it.match(modelId) }
    }

    /** DSL builder。 */
    private class ModelDefinitionBuilder {
        private val matchers = mutableListOf<TokenMatcher>()
        private val inputModalities = mutableSetOf("text")
        private val outputModalities = mutableSetOf("text")
        private val abilities = mutableSetOf<ModelAbility>()

        /** 顺序匹配 token 序列(字面量"或"语法,如 "a|b|c")。 */
        fun tokens(vararg specs: String) {
            matchers.add(TokenSequenceMatcher(specs.map { parseTokenSpec(it) }))
        }

        /** 顺序匹配 token 序列(支持正则)。 */
        fun tokens(vararg specs: TokenSpec) {
            matchers.add(TokenSequenceMatcher(specs.toList()))
        }

        /** 负向断言:token 序列不出现。 */
        fun notTokens(vararg specs: String) {
            matchers.add(NotTokenSequenceMatcher(specs.map { parseTokenSpec(it) }))
        }

        /** modelId 完全相等(忽略大小写)。 */
        fun exact(id: String) {
            matchers.add(ExactIdMatcher(id))
        }

        /** 输入模态(默认 ["text"])。 */
        fun input(vararg modalities: String) {
            inputModalities.clear()
            inputModalities.addAll(modalities)
        }

        /** 输出模态(默认 ["text"])。 */
        fun output(vararg modalities: String) {
            outputModalities.clear()
            outputModalities.addAll(modalities)
        }

        /** 能力(TOOL / REASONING)。 */
        fun ability(vararg abilities: ModelAbility) {
            this.abilities.addAll(abilities)
        }

        // ── 便捷组合 ──
        fun visionInput() = input("text", "image")
        fun imageOutput() = output("text", "image")
        fun toolAbility() = ability(ModelAbility.TOOL)
        fun reasoningAbility() = ability(ModelAbility.REASONING)
        fun toolReasoningAbility() = ability(ModelAbility.TOOL, ModelAbility.REASONING)

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
            )
        }
    }

    private class ModelGroupBuilder {
        private val members = mutableListOf<ModelDefinition>()
        /** 添加成员,接受 ModelDefinition 或嵌套 ModelGroup(自动展开)。 */
        fun add(vararg items: Matchable) {
            items.forEach { item ->
                when (item) {
                    is ModelDefinition -> members.add(item)
                    is ModelGroup -> members.addAll(item.members)
                    else -> error("add(Matchable) 仅接受 ModelDefinition/ModelGroup,收到: $item")
                }
            }
        }
        fun build(): ModelGroup = ModelGroup(members.toList())
    }

    // ── DSL 入口 ──
    private fun defineModel(block: ModelDefinitionBuilder.() -> Unit): ModelDefinition =
        ModelDefinitionBuilder().apply(block).build()

    private fun defineGroup(block: ModelGroupBuilder.() -> Unit): ModelGroup =
        ModelGroupBuilder().apply(block).build()

    /** 正则 TokenSpec。 */
    fun tokenRegex(pattern: String): TokenSpec = TokenRegex(pattern.toRegex(RegexOption.IGNORE_CASE))

    // ──────────────────────────────────────────────────────────────
    // 五、模型族定义清单(参考 rikkahub,涵盖主流模型)
    // ──────────────────────────────────────────────────────────────

    // ── OpenAI 系列 ──
    private val GPT4O = defineModel {
        tokens("gpt", "4", "o")
        visionInput()
        toolAbility()
    }
    private val GPT_4_1 = defineModel {
        tokens("gpt", "4", "1")
        visionInput()
        toolAbility()
    }
    /** OpenAI o 系列推理模型(o1/o3/o4-mini 等)。 */
    private val _OPENAI_O_MODELS = defineModel {
        tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))
        visionInput()
        toolReasoningAbility()
    }
    val OPENAI_O_MODELS: Matchable = _OPENAI_O_MODELS
    private val GPT_OSS = defineModel {
        tokens("gpt", "oss")
        input("text")
        toolReasoningAbility()
    }
    private val GPT_5 = defineModel {
        tokens("gpt", "5")
        // 排除带小数点的细分版本(如 gpt-5.1)和 chat 变体
        notTokens("gpt", "5", ".")
        notTokens("gpt", "5", "chat")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_1 = defineModel {
        tokens("gpt", "5", "1")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_2 = defineModel {
        tokens("gpt", "5", "2")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_3 = defineModel {
        tokens("gpt", "5", "3")
        visionInput()
        toolAbility()
    }
    private val GPT_5_4 = defineModel {
        tokens("gpt", "5", "4")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_4_MINI = defineModel {
        tokens("gpt", "5", "4", "mini")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_4_NANO = defineModel {
        tokens("gpt", "5", "4", "nano")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_5 = defineModel {
        tokens("gpt", "5", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val GPT_5_6 = defineModel {
        tokens("gpt", "5", "6")
        visionInput()
        toolReasoningAbility()
    }
    val GPT_SERIES: Matchable = defineGroup {
        add(
            GPT4O, GPT_4_1, _OPENAI_O_MODELS, GPT_OSS,
            GPT_5, GPT_5_1, GPT_5_2, GPT_5_3, GPT_5_4, GPT_5_4_MINI, GPT_5_4_NANO,
            GPT_5_5, GPT_5_6,
        )
    }

    // ── Google Gemini 系列 ──
    private val GEMINI_20_FLASH = defineModel {
        tokens("gemini", "2", "0", "flash")
        visionInput()
        toolAbility()
    }
    private val GEMINI_2_5_FLASH = defineModel {
        tokens("gemini", "2", "5", "flash")
        notTokens("image")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_2_5_PRO = defineModel {
        tokens("gemini", "2", "5", "pro")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_2_5_IMAGE = defineModel {
        tokens("gemini", "2", "5", "flash", "image")
        visionInput()
        imageOutput()
    }
    private val GEMINI_3_PRO_IMAGE = defineModel {
        tokens("gemini", "3", "pro", "image")
        visionInput()
        imageOutput()
    }
    private val GEMINI_NANO_BANANA = defineModel {
        tokens("nano", "banana")
        visionInput()
        imageOutput()
    }
    private val GEMINI_3_PRO = defineModel {
        tokens("gemini", "3", "pro")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_3_FLASH = defineModel {
        tokens("gemini", "3", "flash")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_3_1_PRO_PREVIEW = defineModel {
        tokens("gemini", "3", "1", "pro", "preview")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_3_1_FLASH_IMAGE = defineModel {
        tokens("gemini", "3", "1", "flash", "image")
        visionInput()
        imageOutput()
        reasoningAbility()
    }
    private val GEMINI_3_5 = defineModel {
        tokens("gemini", "3", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_FLASH_LATEST = defineModel {
        exact("gemini-flash-latest")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_PRO_LATEST = defineModel {
        exact("gemini-pro-latest")
        visionInput()
        toolReasoningAbility()
    }
    private val GEMINI_LATEST = defineGroup {
        add(GEMINI_FLASH_LATEST, GEMINI_PRO_LATEST)
    }
    private val GEMINI_3_SERIES = defineGroup {
        add(GEMINI_3_PRO, GEMINI_3_FLASH, GEMINI_3_1_PRO_PREVIEW, GEMINI_3_5)
    }
    val GEMINI_SERIES: Matchable = defineGroup {
        add(GEMINI_20_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO)
        add(GEMINI_3_SERIES, GEMINI_LATEST)
    }

    // ── Anthropic Claude 系列 ──
    private val CLAUDE_SONNET_3_5 = defineModel {
        tokens("claude", "3", "5", "sonnet")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_SONNET_3_7 = defineModel {
        tokens("claude", "3", "7", "sonnet")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_4 = defineModel {
        tokens("claude", "4")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_4_5 = defineModel {
        tokens("claude", "4", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_SONNET_4_6 = defineModel {
        tokens("claude", "sonnet", "4", "6")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_OPUS_4_6 = defineModel {
        tokens("claude", "opus", "4", "6")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_OPUS_4_7 = defineModel {
        tokens("claude", "opus", "4", "7")
        visionInput()
        toolReasoningAbility()
    }
    private val CLAUDE_OPUS_4_8 = defineModel {
        tokens("claude", "opus", "4", "8")
        visionInput()
        toolReasoningAbility()
    }
    val CLAUDE_SERIES: Matchable = defineGroup {
        add(
            CLAUDE_SONNET_3_5, CLAUDE_SONNET_3_7, CLAUDE_4, CLAUDE_4_5,
            CLAUDE_SONNET_4_6, CLAUDE_OPUS_4_6, CLAUDE_OPUS_4_7, CLAUDE_OPUS_4_8,
        )
    }

    // ── DeepSeek 系列 ──
    private val DEEPSEEK_V3_MODEL = defineModel {
        tokens("deepseek", "v", "3")
        input("text")
        toolAbility()
    }
    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        input("text")
        toolAbility()
    }
    private val DEEPSEEK_R1_MODEL = defineModel {
        tokens("deepseek", "r", "1")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_V3_1 = defineModel {
        tokens("deepseek", "v", "3", "1")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_V3_2 = defineModel {
        tokens("deepseek", "v", "3", "2")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_V4_FLASH = defineModel {
        tokens("deepseek", "v", "4", "flash")
        input("text")
        toolReasoningAbility()
    }
    private val DEEPSEEK_V4_PRO = defineModel {
        tokens("deepseek", "v", "4", "pro")
        input("text")
        toolReasoningAbility()
    }
    val DEEPSEEK_V3: Matchable = defineGroup { add(DEEPSEEK_V3_MODEL, DEEPSEEK_CHAT) }
    val DEEPSEEK_R1: Matchable = defineGroup { add(DEEPSEEK_R1_MODEL, DEEPSEEK_REASONER) }
    val DEEPSEEK_SERIES: Matchable = defineGroup {
        add(
            DEEPSEEK_V3_MODEL, DEEPSEEK_CHAT, DEEPSEEK_R1_MODEL, DEEPSEEK_REASONER,
            DEEPSEEK_V3_1, DEEPSEEK_V3_2, DEEPSEEK_V4_FLASH, DEEPSEEK_V4_PRO,
        )
    }

    // ── 通义 Qwen 系列 ──
    private val QWEN_3 = defineModel {
        tokens("qwen", "3")
        input("text")
        toolReasoningAbility()
    }
    private val QWEN_3_5 = defineModel {
        tokens("qwen", "3", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val QWEN_3_6 = defineModel {
        tokens("qwen", "3", "6")
        visionInput()
        toolReasoningAbility()
    }
    private val QWEN_3_7 = defineModel {
        tokens("qwen", "3", "7")
        visionInput()
        toolReasoningAbility()
    }
    /** Qwen-MT 翻译专用模型,无工具/推理能力。 */
    private val _QWEN_MT = defineModel {
        tokens("qwen", "mt")
        input("text")
    }
    val QWEN_MT: Matchable = _QWEN_MT
    val QWEN_SERIES: Matchable = defineGroup {
        add(QWEN_3, QWEN_3_5, QWEN_3_6, QWEN_3_7, _QWEN_MT)
    }

    // ── 豆包 Doubao ──
    private val DOUBAO_1_6 = defineModel {
        tokens("doubao", "1", "6")
        visionInput()
        toolReasoningAbility()
    }
    private val DOUBAO_1_8 = defineModel {
        tokens("doubao", "1", "8")
        visionInput()
        toolReasoningAbility()
    }
    val DOUBAO_SERIES: Matchable = defineGroup { add(DOUBAO_1_6, DOUBAO_1_8) }

    // ── xAI Grok ──
    private val GROK_4 = defineModel {
        tokens("grok", "4")
        visionInput()
        toolReasoningAbility()
    }
    val GROK_SERIES: Matchable = defineGroup { add(GROK_4) }

    // ── 月之暗面 Kimi ──
    private val KIMI_K2 = defineModel {
        tokens("kimi", "k", "2")
        input("text")
        toolReasoningAbility()
    }
    private val KIMI_K2_5 = defineModel {
        tokens("kimi", "k", "2", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val KIMI_K2_6 = defineModel {
        tokens("kimi", "k", "2", "6")
        visionInput()
        toolReasoningAbility()
    }
    val KIMI_SERIES: Matchable = defineGroup { add(KIMI_K2, KIMI_K2_5, KIMI_K2_6) }

    // ── 阶跃 Step ──
    private val STEP_3 = defineModel {
        tokens("step", "3")
        visionInput()
        toolReasoningAbility()
    }
    private val STEP_3_7_FLASH = defineModel {
        tokens("step", "3", "7", "flash")
        visionInput()
        toolReasoningAbility()
    }
    val STEP_SERIES: Matchable = defineGroup { add(STEP_3, STEP_3_7_FLASH) }

    // ── Intern ──
    private val INTERN_S1 = defineModel {
        tokens("intern", "s", "1")
        visionInput()
        toolReasoningAbility()
    }
    val INTERN_SERIES: Matchable = defineGroup { add(INTERN_S1) }

    // ── 智谱 GLM ──
    private val GLM_4_5 = defineModel {
        tokens("glm", "4", "5")
        input("text")
        toolReasoningAbility()
    }
    private val GLM_4_6 = defineModel {
        tokens("glm", "4", "6")
        input("text")
        toolReasoningAbility()
    }
    private val GLM_4_7 = defineModel {
        tokens("glm", "4", "7")
        input("text")
        toolReasoningAbility()
    }
    private val GLM_5 = defineModel {
        tokens("glm", "5")
        input("text")
        toolReasoningAbility()
    }
    private val GLM_5_1 = defineModel {
        tokens("glm", "5", "1")
        input("text")
        toolReasoningAbility()
    }
    val GLM_SERIES: Matchable = defineGroup {
        add(GLM_4_5, GLM_4_6, GLM_4_7, GLM_5, GLM_5_1)
    }

    // ── MiniMax ──
    private val MINIMAX_M2 = defineModel {
        tokens("minimax", "m", "2")
        input("text")
        toolReasoningAbility()
    }
    private val MINIMAX_M2_5 = defineModel {
        tokens("minimax", "m", "2", "5")
        input("text")
        toolReasoningAbility()
    }
    private val MINIMAX_M2_7 = defineModel {
        tokens("minimax", "m", "2", "7")
        input("text")
        toolReasoningAbility()
    }
    private val MINIMAX_M3 = defineModel {
        tokens("minimax", "m", "3")
        visionInput()
        toolReasoningAbility()
    }
    val MINIMAX_SERIES: Matchable = defineGroup {
        add(MINIMAX_M2, MINIMAX_M2_5, MINIMAX_M2_7, MINIMAX_M3)
    }

    // ── 小米 MiMo ──
    private val XIAOMI_MIMO_V2 = defineModel {
        tokens("mimo", "v", "2")
        input("text")
        toolReasoningAbility()
    }
    private val XIAOMI_MIMO_V2_PRO = defineModel {
        tokens("mimo", "v", "2", "pro")
        input("text")
        toolReasoningAbility()
    }
    private val XIAOMI_MIMO_V2_5 = defineModel {
        tokens("mimo", "v", "2", "5")
        visionInput()
        toolReasoningAbility()
    }
    private val XIAOMI_MIMO_V2_5_PRO = defineModel {
        tokens("mimo", "v", "2", "5", "pro")
        input("text")
        toolReasoningAbility()
    }
    val MIMO_SERIES: Matchable = defineGroup {
        add(XIAOMI_MIMO_V2, XIAOMI_MIMO_V2_PRO, XIAOMI_MIMO_V2_5, XIAOMI_MIMO_V2_5_PRO)
    }

    // ──────────────────────────────────────────────────────────────
    // 六、ALL_MODELS 主清单(resolveModels 按声明顺序遍历)
    // ──────────────────────────────────────────────────────────────

    private val ALL_MODELS: List<ModelDefinition> = listOf(
        // OpenAI
        GPT4O, GPT_4_1, _OPENAI_O_MODELS, GPT_OSS,
        GPT_5, GPT_5_1, GPT_5_2, GPT_5_3, GPT_5_4, GPT_5_4_MINI, GPT_5_4_NANO,
        GPT_5_5, GPT_5_6,
        // Gemini
        GEMINI_20_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO, GEMINI_2_5_IMAGE,
        GEMINI_3_PRO_IMAGE, GEMINI_NANO_BANANA, GEMINI_3_PRO, GEMINI_3_FLASH,
        GEMINI_3_1_PRO_PREVIEW, GEMINI_3_1_FLASH_IMAGE, GEMINI_3_5,
        GEMINI_FLASH_LATEST, GEMINI_PRO_LATEST,
        // Claude
        CLAUDE_SONNET_3_5, CLAUDE_SONNET_3_7, CLAUDE_4, CLAUDE_4_5,
        CLAUDE_SONNET_4_6, CLAUDE_OPUS_4_6, CLAUDE_OPUS_4_7, CLAUDE_OPUS_4_8,
        // DeepSeek
        DEEPSEEK_V3_MODEL, DEEPSEEK_CHAT, DEEPSEEK_R1_MODEL, DEEPSEEK_REASONER,
        DEEPSEEK_V3_1, DEEPSEEK_V3_2, DEEPSEEK_V4_FLASH, DEEPSEEK_V4_PRO,
        // Qwen
        QWEN_3, QWEN_3_5, QWEN_3_6, QWEN_3_7, _QWEN_MT,
        // 豆包 / Grok / Kimi / Step / Intern / GLM / MiniMax / MiMo
        DOUBAO_1_6, DOUBAO_1_8, GROK_4,
        KIMI_K2, KIMI_K2_5, KIMI_K2_6,
        STEP_3, STEP_3_7_FLASH, INTERN_S1,
        GLM_4_5, GLM_4_6, GLM_4_7, GLM_5, GLM_5_1,
        MINIMAX_M2, MINIMAX_M2_5, MINIMAX_M2_7, MINIMAX_M3,
        XIAOMI_MIMO_V2, XIAOMI_MIMO_V2_PRO, XIAOMI_MIMO_V2_5, XIAOMI_MIMO_V2_5_PRO,
    )

    // ──────────────────────────────────────────────────────────────
    // 七、解析入口(对外 API)
    // ──────────────────────────────────────────────────────────────

    /**
     * 取所有命中定义中分数最高的(平局时合并能力)。
     * 未命中返回空列表。
     */
    private fun resolveModels(modelId: String): List<ModelDefinition> {
        if (modelId.isBlank()) return emptyList()
        var bestScore: Int? = null
        val matches = mutableListOf<ModelDefinition>()
        for (model in ALL_MODELS) {
            val score = model.matchScore(modelId) ?: continue
            when {
                bestScore == null || score > bestScore -> {
                    bestScore = score
                    matches.clear()
                    matches.add(model)
                }
                score == bestScore -> matches.add(model)
            }
        }
        return matches
    }

    /**
     * 查询输入模态。未命中返回空集合(由调用方决定兜底,通常默认 ["text"])。
     */
    fun lookupInputModalities(modelId: String): Set<String> =
        resolveModels(modelId).flatMap { it.inputModalities }.toSet()

    /**
     * 查询输出模态。未命中返回空集合。
     */
    fun lookupOutputModalities(modelId: String): Set<String> =
        resolveModels(modelId).flatMap { it.outputModalities }.toSet()

    /**
     * 查询能力。未命中返回空集合。
     * 返回顺序固定:TOOL 在前,REASONING 在后。
     */
    fun lookupAbilities(modelId: String): Set<ModelAbility> {
        val abilities = resolveModels(modelId).flatMap { it.abilities }.toSet()
        return buildSet {
            if (ModelAbility.TOOL in abilities) add(ModelAbility.TOOL)
            if (ModelAbility.REASONING in abilities) add(ModelAbility.REASONING)
        }
    }

    /**
     * 判断 modelId 是否支持视觉输入。
     */
    fun supportsVisionInput(modelId: String): Boolean =
        "image" in lookupInputModalities(modelId)

    /**
     * 判断 modelId 是否支持图像输出(绘图模型)。
     */
    fun supportsImageOutput(modelId: String): Boolean =
        "image" in lookupOutputModalities(modelId)

    /**
     * 判断 modelId 是否支持工具调用。
     */
    fun supportsToolCalling(modelId: String): Boolean =
        ModelAbility.TOOL in lookupAbilities(modelId)

    /**
     * 判断 modelId 是否支持推理链。
     */
    fun supportsReasoning(modelId: String): Boolean =
        ModelAbility.REASONING in lookupAbilities(modelId)

    /**
     * 判断 modelId 是否匹配指定模型族(如 [GPT_SERIES] / [CLAUDE_SERIES] / [GEMINI_SERIES])。
     */
    fun matches(modelId: String, group: Matchable): Boolean {
        if (modelId.isBlank()) return false
        return group.match(modelId)
    }

    // ──────────────────────────────────────────────────────────────
    // 八、Model 便捷 enrich(填补缺失字段,保留已有手动设置)
    // ──────────────────────────────────────────────────────────────

    /**
     * 给定一个 [Model](通常只有 id/name),根据注册表自动补全:
     *  - [Model.abilities]:若为空,从注册表填充
     *  - [Model.inputModalities]:若仅含默认 "text",从注册表填充(可能加 "image")
     *  - [Model.outputModalities]:若仅含默认 "text",从注册表填充(可能加 "image"/"video")
     *  - [Model.supportsVision]:若注册表判定支持图片输入,设为 true
     *  - [Model.supportsVideo]:若 outputModalities 含 "video",设为 true
     *  - [Model.contextWindow]:若为空,委托 [ModelContextWindowRegistry] 兜底
     *  - [Model.maxOutputTokens]:若为空,优先从 [KnownModels] 兜底
     *
     * 补全优先级(每个字段独立判断,空值才向下走):
     *  1. [Model] 自身已有字段(用户/Provider 显式设置)
     *  2. [KnownModels.lookup] 兜底补全(含 contextWindow / maxOutputTokens / modalities / abilities)
     *  3. [ModelRegistry] token 推断 + [ModelContextWindowRegistry] 上下文窗口兜底
     *
     * 已显式设置过(非空/非默认值)的字段不会被覆盖,避免破坏用户手动调整。
     *
     * v1.0.8: 增加 supportsVideo 补全,并调整 abilities 优先级为 Model > Registry > KnownModels,
     * 与 [io.zer0.ai.registry.ModelRegistry.enhanceModel] 保持一致。
     */
    fun enrich(model: Model): Model {
        val id = model.id
        if (id.isBlank()) return model

        // 二级兜底:先查 KnownModels 词典(离线元信息表)
        val knownInfo = KnownModels.lookup(id)

        val detectedAbilities = lookupAbilities(id)
        val detectedInput = lookupInputModalities(id)
        val detectedOutput = lookupOutputModalities(id)

        // abilities: Model 已有 → Registry → KnownModels
        val newAbilities = when {
            model.abilities.isNotEmpty() -> model.abilities
            detectedAbilities.isNotEmpty() -> detectedAbilities
            !knownInfo?.abilities.isNullOrEmpty() -> knownInfo.abilities
            else -> emptySet()
        }

        // inputModalities: 仅当为默认 ["text"] 时才尝试覆盖
        val newInput = if (model.inputModalities.size == 1 && "text" in model.inputModalities) {
            val knownInput = knownInfo?.inputModalities?.map { it.wireName }?.toSet()
            when {
                !knownInput.isNullOrEmpty() -> knownInput
                detectedInput.isNotEmpty() -> detectedInput
                else -> model.inputModalities
            }
        } else {
            model.inputModalities
        }

        // outputModalities: 同上
        val newOutput = if (model.outputModalities.size == 1 && "text" in model.outputModalities) {
            val knownOutput = knownInfo?.outputModalities?.map { it.wireName }?.toSet()
            when {
                !knownOutput.isNullOrEmpty() -> knownOutput
                detectedOutput.isNotEmpty() -> detectedOutput
                else -> model.outputModalities
            }
        } else {
            model.outputModalities
        }

        // supportsVision: 若已显式 true 则保留,否则按检测补全
        val newSupportsVision = model.supportsVision || ("image" in newInput)
        // v1.0.8: 同步 supportsVideo 与 outputModalities
        val newSupportsVideo = model.supportsVideo || ("video" in newOutput)

        // contextWindow: Model 已有 → KnownModels → ModelContextWindowRegistry
        val newContextWindow = model.contextWindow
            ?: knownInfo?.contextWindow
            ?: ModelContextWindowRegistry.lookup(id)

        // maxOutputTokens: Model 已有 → KnownModels(无其他兜底来源)
        val newMaxOutputTokens = model.maxOutputTokens ?: knownInfo?.maxOutputTokens

        return model.copy(
            abilities = newAbilities,
            inputModalities = newInput,
            outputModalities = newOutput,
            supportsVision = newSupportsVision,
            supportsVideo = newSupportsVideo,
            contextWindow = newContextWindow,
            maxOutputTokens = newMaxOutputTokens,
        )
    }
}
