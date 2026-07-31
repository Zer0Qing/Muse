package io.zer0.ai.registry

import io.zer0.ai.core.BuiltInTool
import io.zer0.ai.core.KnownModels
import io.zer0.ai.core.KnownModels.Modality
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ModelContextWindowRegistry
import io.zer0.ai.core.ModelVerification

/**
 * 模型能力注册表（移植自 RikkaHub ModelRegistry.kt）。
 *
 * 通过 DSL 定义已知模型能力。当模型 ID 匹配时，
 * 注册表返回解析后的能力/模态/工具。
 *
 * ChatService 使用它来自动适配请求参数：
 *  - 是否发送工具（函数调用）
 *  - 是否包含图片（视觉输入）
 *  - 是否启用推理/思考
 */
object ModelRegistry {

    // ─── OpenAI ───

    private val GPT4O = defineModel {
        tokens("gpt", "4", "o")
        visionInput(); toolAbility()
    }
    private val GPT_4_1 = defineModel {
        tokens("gpt", "4", "1")
        visionInput(); toolAbility()
    }
    private val OPENAI_O_MODELS = defineModel {
        tokens(tokenRegex("^o$"), tokenRegex("^\\d+$"))
        visionInput(); toolReasoningAbility()
    }
    // v1.0.8: GPT-5 系列(旗舰 / mini / nano / codex 等)
    private val GPT_5 = defineModel {
        tokens("gpt", "5")
        notTokens(".")
        visionInput(); toolReasoningAbility()
    }
    private val GPT_5_1 = defineModel {
        tokens("gpt", "5", "1")
        visionInput(); toolReasoningAbility()
    }
    private val GPT_5_4 = defineModel {
        tokens("gpt", "5", "4")
        visionInput(); toolReasoningAbility()
    }
    private val GPT_5_CODEX = defineModel {
        tokens("gpt", "5", "codex")
        toolReasoningAbility()
    }
    private val GPT_4_TURBO = defineModel {
        tokens("gpt", "4", "turbo")
        visionInput(); toolAbility()
    }
    private val GPT_4 = defineModel {
        tokens("gpt", "4")
        notTokens("o")
        toolAbility()
    }

    // ─── Anthropic ───

    private val CLAUDE_3_5_SONNET = defineModel {
        tokens("claude", "3", "5", "sonnet")
        visionInput(); toolAbility()
    }
    private val CLAUDE_3_5_HAIKU = defineModel {
        tokens("claude", "3", "5", "haiku")
        toolAbility()
    }
    private val CLAUDE_3_7 = defineModel {
        tokens("claude", "3", "7")
        visionInput(); toolReasoningAbility()
    }
    private val CLAUDE_4 = defineModel {
        tokens("claude", "4")
        notTokens("5")
        visionInput(); toolReasoningAbility()
    }
    private val CLAUDE_4_5 = defineModel {
        tokens("claude", "4", "5")
        visionInput(); toolReasoningAbility()
    }
    private val CLAUDE_OPUS = defineModel {
        tokens("claude", "opus")
        visionInput(); toolReasoningAbility()
    }
    private val CLAUDE_SONNET = defineModel {
        tokens("claude", "sonnet")
        visionInput(); toolAbility()
    }

    // ─── Google Gemini ───

    private val GEMINI_2_0_FLASH = defineModel {
        tokens("gemini", "2", "0", "flash")
        visionInput(); toolAbility(); visionGrounding("gemini")
    }
    private val GEMINI_2_5_FLASH = defineModel {
        tokens("gemini", "2", "5", "flash")
        visionInput(); toolReasoningAbility(); visionGrounding("gemini")
    }
    private val GEMINI_2_5_PRO = defineModel {
        tokens("gemini", "2", "5", "pro")
        visionInput(); toolReasoningAbility(); visionGrounding("gemini")
    }
    private val GEMINI_1_5_PRO = defineModel {
        tokens("gemini", "1", "5", "pro")
        visionInput(); toolAbility(); visionGrounding("gemini")
    }
    private val GEMINI_1_5_FLASH = defineModel {
        tokens("gemini", "1", "5", "flash")
        visionInput(); toolAbility(); visionGrounding("gemini")
    }
    private val GEMINI_PRO = defineModel {
        tokens("gemini", "pro")
        notTokens("1")
        notTokens("2")
        notTokens("3")
        visionInput(); toolAbility()
    }
    private val GEMINI_FLASH = defineModel {
        tokens("gemini", "flash")
        notTokens("1")
        notTokens("2")
        notTokens("3")
        visionInput(); toolAbility()
    }

    // ─── DeepSeek ───

    private val DEEPSEEK_V3 = defineModel {
        tokens("deepseek", "v", "3")
        toolAbility()
    }
    private val DEEPSEEK_CHAT = defineModel {
        tokens("deepseek", "chat")
        toolAbility()
    }
    private val DEEPSEEK_R1 = defineModel {
        tokens("deepseek", "r", "1")
        reasoningAbility()
    }
    private val DEEPSEEK_REASONER = defineModel {
        tokens("deepseek", "reasoner")
        reasoningAbility()
    }
    // v1.0.8: DeepSeek V4 系列(含 flash / pro 等变体)
    private val DEEPSEEK_V4 = defineModel {
        tokens("deepseek", "v", "4")
        toolReasoningAbility()
    }

    // ─── Qwen ───

    private val QWEN_MAX = defineModel {
        tokens("qwen", "max")
        toolAbility()
    }
    private val QWEN_PLUS = defineModel {
        tokens("qwen", "plus")
        toolAbility()
    }
    private val QWEN_TURBO = defineModel {
        tokens("qwen", "turbo")
        toolAbility()
    }
    private val QWEN_VL = defineModel {
        tokens("qwen", "vl")
        visionInput(); visionGrounding("qwen")
    }
    private val QWEN_QWQ = defineModel {
        tokens("qwq")
        reasoningAbility()
    }
    // v1.0.1 (P3): Qwen2-VL 系列(开源视觉模型,中转站常见)
    // v1.0.4: 支持 grounding(qwen 格式 bbox_2d + point_2d)
    private val QWEN2_VL = defineModel {
        tokens("qwen", "2", "vl")
        visionInput(); visionGrounding("qwen")
    }

    // ─── 其他 ───

    private val GLM_4 = defineModel {
        tokens("glm", "4")
        visionInput(); toolAbility()
    }
    private val GLM_3 = defineModel {
        tokens("glm", "3")
        toolAbility()
    }
    // v1.0.1 (P3): GLM-4V 系列(智谱视觉模型,中转站常见)
    private val GLM_4V = defineModel {
        tokens("glm", "4", "v")
        visionInput(); toolAbility()
    }
    // v1.0.1 (P3): GLM-V 系列(智谱视觉模型简写,如 glm-v-4plus)
    private val GLM_V = defineModel {
        tokens("glm", "v")
        visionInput()
    }
    private val DOUBAO_PRO = defineModel {
        tokens("doubao", "pro")
        toolAbility()
    }
    // v1.0.1 (P3): Doubao Vision 系列(火山引擎视觉模型)
    private val DOUBAO_VISION = defineModel {
        tokens("doubao", "vision")
        visionInput(); toolAbility()
    }
    private val MINIMAX = defineModel {
        tokens("minimax", "abab")
        toolAbility()
    }
    private val MINIMAX_M3 = defineModel {
        tokens("minimax", "m", "3")
        visionInput(); toolAbility()
    }
    // v1.0.8: MiniMax M2.5 / M2.7 / M1 系列
    private val MINIMAX_M2_5 = defineModel {
        tokens("minimax", "m", "2", "5")
        toolAbility()
    }
    private val MINIMAX_M2_7 = defineModel {
        tokens("minimax", "m", "2", "7")
        toolAbility()
    }
    private val MINIMAX_M1 = defineModel {
        tokens("minimax", "m", "1")
        toolAbility()
    }
    private val GROK = defineModel {
        tokens("grok")
        visionInput(); toolAbility()
    }
    private val KIMI = defineModel {
        tokens("kimi", "moonshot")
        toolAbility()
    }
    // v1.0.8: Kimi K2 系列(如 kimi-k2, kimi-k2.5, kimi-k2.7 等)
    private val KIMI_K2 = defineModel {
        tokens("kimi", "k", "2")
        toolReasoningAbility()
    }
    // v1.0.53: Kimi K2.6 — 多模态版本(视觉+文本输入),精确规则分数高于 KIMI_K2,不会误伤 k2/k2.5
    private val KIMI_K2_6 = defineModel {
        tokens("kimi", "k", "2", "6")
        visionInput(); toolReasoningAbility()
    }
    // v1.0.1 (P3): Kimi Vision(Moonshot 视觉模型,如 moonshot-v1-8k-vision-preview)
    private val KIMI_VISION = defineModel {
        tokens("kimi", "vision")
        visionInput()
    }
    private val YI = defineModel {
        tokens("yi")
        visionInput()
    }
    private val LLAMA_3 = defineModel {
        tokens("llama", "3")
        toolAbility()
    }
    private val MISTRAL_LARGE = defineModel {
        tokens("mistral", "large")
        visionInput(); toolAbility()
    }
    private val MISTRAL = defineModel {
        tokens("mistral")
        notTokens("large")
        toolAbility()
    }
    // v1.0.1 (P3): InternVL 系列(开源视觉模型,OpenRouter/HuggingFace 常见)
    private val INTERN_VL = defineModel {
        tokens("intern", "vl")
        visionInput()
    }
    // v1.0.1 (P3): CogVLM 系列(清华开源视觉模型)
    private val COG_VLM = defineModel {
        tokens("cog", "vlm")
        visionInput()
    }
    // v1.0.1 (P3): Step-VL 系列(阶跃星辰视觉模型)
    private val STEP_VL = defineModel {
        tokens("step", "vl")
        visionInput()
    }
    // v1.0.1 (P3): LLaVA 系列(开源视觉模型)
    private val LLAVA = defineModel {
        tokens("llava")
        visionInput()
    }
    // v1.0.1 (P3): Pixtral 系列(Mistral 视觉模型)
    private val PIXTRAL = defineModel {
        tokens("pixtral")
        visionInput()
    }

    // ─── 全部模型列表 ───

    private val ALL_MODELS = listOf(
        GPT4O, GPT_4_1, OPENAI_O_MODELS, GPT_5, GPT_5_1, GPT_5_4, GPT_5_CODEX,
        GPT_4_TURBO, GPT_4,
        CLAUDE_3_5_SONNET, CLAUDE_3_5_HAIKU, CLAUDE_3_7, CLAUDE_4, CLAUDE_4_5,
        CLAUDE_OPUS, CLAUDE_SONNET,
        GEMINI_2_0_FLASH, GEMINI_2_5_FLASH, GEMINI_2_5_PRO,
        GEMINI_1_5_PRO, GEMINI_1_5_FLASH, GEMINI_PRO, GEMINI_FLASH,
        DEEPSEEK_V3, DEEPSEEK_CHAT, DEEPSEEK_R1, DEEPSEEK_REASONER, DEEPSEEK_V4,
        QWEN_MAX, QWEN_PLUS, QWEN_TURBO, QWEN_VL, QWEN2_VL, QWEN_QWQ,
        GLM_4, GLM_3, GLM_4V, GLM_V,
        DOUBAO_PRO, DOUBAO_VISION,
        MINIMAX, MINIMAX_M3, MINIMAX_M2_5, MINIMAX_M2_7, MINIMAX_M1,
        GROK, KIMI, KIMI_K2, KIMI_K2_6, KIMI_VISION, YI,
        LLAMA_3, MISTRAL_LARGE, MISTRAL,
        // v1.0.1 (P3): 开源/中转站常见视觉模型
        INTERN_VL, COG_VLM, STEP_VL, LLAVA, PIXTRAL,
    )

    /**
     * 解析给定模型 ID 的能力。
     * 返回按分数排序的最佳匹配定义。
     *
     * v1.135: 先按原始 modelId 匹配;未命中时剥掉中转/聚合平台前缀再试一次,
     * 解决 `opencode-go/deepseek-v3` 这类 ID 无法识别能力的问题。
     */
    fun resolveDefinitions(modelId: String): List<ModelDefinition> {
        val result = resolveDefinitionsInternal(modelId)
        if (result.isNotEmpty()) return result
        val bare = bareModelId(modelId)
        if (bare != modelId) return resolveDefinitionsInternal(bare)
        return emptyList()
    }

    private fun resolveDefinitionsInternal(modelId: String): List<ModelDefinition> {
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
     * 剥掉常见中转/聚合平台前缀(如 openrouter/、opencode-go/)。
     * 若不含前缀,则返回最后一个 `/` 之后的部分(兜底)。
     *
     * v1.0.1 (P3): 补全国内常见中转站前缀(siliconflow/、dashscope/、baichuan/、lingyi/ 等),
     *  兜底逻辑(substringAfterLast("/"))其实已能处理任意前缀,但显式列出可避免
     *  某些带版本号前缀(如 "accounts/fireworks/models/")被错误剥离。
     */
    private fun bareModelId(modelId: String): String {
        val raw = modelId.trim().lowercase()
        val prefixes = listOf(
            // 海外聚合站
            "openrouter/", "opencode-go/", "anthropic/", "google/", "openai/",
            "meta-llama/", "mistralai/", "nousresearch/", "deepinfra/", "togethercomputer/",
            "accounts/fireworks/models/", "presets/",
            // v1.0.1 (P3): 国内中转站 / 聚合站
            "siliconflow/", "dashscope/", "baichuan/", "lingyi/", "lingyiwanwu/",
            "stepfun/", "zhipu/", "bigmodel/", "minimax/", "moonshot/",
            "doubao/", "volcengine/", "ark/", "hunyuan/", "qwen/", "aliyun/",
        )
        for (prefix in prefixes) {
            if (raw.startsWith(prefix)) return raw.removePrefix(prefix)
        }
        return raw.substringAfterLast("/").takeIf { it.isNotBlank() } ?: raw
    }

    /**
     * 解析模型 ID 对应的能力。
     */
    fun resolveAbilities(modelId: String): Set<ModelAbility> =
        resolveDefinitions(modelId).flatMap { it.abilities }.toSet()

    /**
     * 解析模型 ID 对应的输入模态。
     */
    fun resolveInputModalities(modelId: String): Set<String> {
        val defs = resolveDefinitions(modelId)
        if (defs.isEmpty()) return setOf("text")
        return defs.flatMap { it.inputModalities }.toSet()
    }

    /**
     * 解析模型 ID 对应的输出模态。
     */
    fun resolveOutputModalities(modelId: String): Set<String> {
        val defs = resolveDefinitions(modelId)
        if (defs.isEmpty()) return setOf("text")
        return defs.flatMap { it.outputModalities }.toSet()
    }

    /**
     * 检查模型是否支持视觉输入。
     */
    fun supportsVision(modelId: String): Boolean =
        "image" in resolveInputModalities(modelId)

    /**
     * 检查模型是否支持工具调用。
     */
    fun supportsToolCalling(modelId: String): Boolean =
        ModelAbility.TOOL in resolveAbilities(modelId)

    /**
     * 检查模型是否支持推理/思考。
     */
    fun supportsReasoning(modelId: String): Boolean =
        ModelAbility.REASONING in resolveAbilities(modelId)

    /**
     * 用注册表解析出的能力增强 [Model]。
     * 仅填充尚未显式设置的字段。
     *
     * v1.0.8: 增强兜底链路 — 当 token 规则未命中时,回退到 [KnownModels] 与
     * [ModelContextWindowRegistry],补全 contextWindow / maxOutputTokens / modalities / abilities。
     *
     * v1.0.8 (7.4): 中转站误标检测 — 即使 registry 未命中(defs 为空),也用 KnownModels
     *  作为权威来源覆盖上游的错误声明:
     *  - 上游声明 supportsVision=true 但 KnownModels 明确标记为纯文本模型(inputModalities={text})
     *    → 以 KnownModels 为准,移除 image 模态,supportsVision=false
     *  - 上游声明 supportsVideo=true 但 KnownModels 未标记 video(outputModalities 不含 video)
     *    → 忽略上游声明,supportsVideo=false
     *  仅在 KnownModels 有明确声明时触发(避免对未知模型误覆盖)。
     */
    fun enhanceModel(model: Model): Model {
        val defs = resolveDefinitions(model.id)
        val knownInfo = KnownModels.lookup(model.id)

        val resolvedAbilities = defs.flatMap { it.abilities }.toSet()
        val resolvedInput = defs.flatMap { it.inputModalities }.toSet()
        val resolvedOutput = defs.flatMap { it.outputModalities }.toSet()
        val resolvedTools = defs.flatMap { it.builtInTools }.toSet()
        // v1.0.4: 取首个非 null 的 visionCapabilities(Gemini/Qwen-VL 等 grounding 模型)
        val resolvedVisionCaps = defs.firstNotNullOfOrNull { it.visionCapabilities }

        // abilities: 模型已有 > registry > KnownModels
        val newAbilities = when {
            model.abilities.isNotEmpty() -> model.abilities
            resolvedAbilities.isNotEmpty() -> resolvedAbilities
            !knownInfo?.abilities.isNullOrEmpty() -> knownInfo.abilities
            else -> emptySet()
        }

        // inputModalities: 当 registry 命中已知模型时,以 registry 为权威(覆盖上游/中转站的错误声明);
        // 未命中时保持原逻辑(模型已有 > KnownModels > 默认)。
        // v1.137: 修复中转站错误标记文本模型(如 DeepSeek V4)为 vision,
        // 导致视觉辅助被跳过、图片直发纯文本模型触发 400 或被当作空白的问题。
        // v1.0.8 (7.4): 进一步加强 — 即使上游声明 inputModalities 含 image(非纯 text),
        //  只要 KnownModels 明确标记为纯文本(inputModalities={text}),覆盖上游声明。
        val newInput = when {
            defs.isNotEmpty() -> resolvedInput
            model.inputModalities.size == 1 && "text" in model.inputModalities -> {
                val knownInput = knownInfo?.inputModalities?.map { it.wireName }?.toSet()
                when {
                    !knownInput.isNullOrEmpty() -> knownInput
                    else -> model.inputModalities
                }
            }
            // v1.0.8 (7.4): 中转站误标检测 — 上游声明含 image 但 KnownModels 明确标记为纯文本
            else -> {
                val knownInput = knownInfo?.inputModalities?.map { it.wireName }?.toSet()
                if (!knownInput.isNullOrEmpty() && "image" !in knownInput) {
                    // KnownModels 明确声明该模型为纯文本(不含 image),覆盖上游误标
                    knownInput
                } else {
                    model.inputModalities
                }
            }
        }

        // outputModalities: 同 inputModalities 逻辑
        // v1.0.8 (7.4): 中转站误标检测 — 上游声明含 video 但 KnownModels 未标记 video
        val newOutput = when {
            defs.isNotEmpty() -> resolvedOutput
            model.outputModalities.size == 1 && "text" in model.outputModalities -> {
                val knownOutput = knownInfo?.outputModalities?.map { it.wireName }?.toSet()
                when {
                    !knownOutput.isNullOrEmpty() -> knownOutput
                    else -> model.outputModalities
                }
            }
            // v1.0.8 (7.4): 中转站误标检测 — 上游声明含 video 但 KnownModels 不含 video
            else -> {
                val knownOutput = knownInfo?.outputModalities?.map { it.wireName }?.toSet()
                if (!knownOutput.isNullOrEmpty() && "video" !in knownOutput && "video" in model.outputModalities) {
                    // KnownModels 明确声明该模型输出不含 video,忽略上游误标
                    knownOutput
                } else {
                    model.outputModalities
                }
            }
        }

        // contextWindow: 模型已有 > KnownModels > ModelContextWindowRegistry
        val newContextWindow = model.contextWindow
            ?: knownInfo?.contextWindow
            ?: ModelContextWindowRegistry.lookup(model.id)

        // maxOutputTokens: 模型已有 > KnownModels
        val newMaxOutputTokens = model.maxOutputTokens ?: knownInfo?.maxOutputTokens

        // v1.0.53: 计算数据可信度 — VERIFIED / SUSPICIOUS / UNVERIFIED
        // - 命中本地规格文档(knownInfo 非空 或 defs 非空)即标 VERIFIED 起步
        // - 检测上游声明的异常字段,有异常则降级为 SUSPICIOUS
        // - 未命中本地文档则 UNVERIFIED(保持默认值)
        val hitLocalRegistry = knownInfo != null || defs.isNotEmpty()
        val verification = if (!hitLocalRegistry) {
            ModelVerification.UNVERIFIED
        } else {
            // 异常检测:上游声明与本地规格文档冲突的字段
            var suspicious = false
            // 异常1: 上游 contextWindow 为 0 或负数(明显错误)
            if (model.contextWindow != null && model.contextWindow <= 0) suspicious = true
            // 异常2: 上游声明 supportsVision=true 但 KnownModels 标记为纯文本
            if (model.supportsVision && knownInfo?.inputModalities?.isNotEmpty() == true &&
                Modality.IMAGE !in knownInfo.inputModalities
            ) suspicious = true
            // 异常3: 上游声明 supportsVideo=true 但 KnownModels 未标记 video 输出
            if (model.supportsVideo && knownInfo?.outputModalities?.isNotEmpty() == true &&
                Modality.VIDEO !in knownInfo.outputModalities
            ) suspicious = true
            // 异常4: 上游声明 maxOutputTokens=0(明显错误)
            if (model.maxOutputTokens != null && model.maxOutputTokens <= 0) suspicious = true

            if (suspicious) ModelVerification.SUSPICIOUS else ModelVerification.VERIFIED
        }

        return model.copy(
            abilities = newAbilities,
            inputModalities = newInput,
            outputModalities = newOutput,
            tools = if (model.tools.isEmpty()) resolvedTools else model.tools,
            // v1.137: registry 命中时 supportsVision/supportsVideo 完全由 newInput/newOutput 派生,
            // 不保留上游可能错误的标记(中转站常见把纯文本模型误标为 vision)。
            // 未命中时保持原逻辑(上游 > newInput)。
            // v1.0.8 (7.4): 进一步加强 — 即使 registry 未命中(defs 为空),
            //  若 KnownModels 明确声明不含 image/video,也覆盖上游误标的 supportsVision/supportsVideo。
            supportsVision = when {
                defs.isNotEmpty() -> "image" in newInput
                // v1.0.8 (7.4): KnownModels 明确声明为纯文本时,以上游 supportsVision 为 false
                knownInfo?.inputModalities?.isNotEmpty() == true &&
                    "image" !in (knownInfo.inputModalities.map { it.wireName }.toSet()) -> false
                else -> model.supportsVision || "image" in newInput
            },
            supportsVideo = when {
                defs.isNotEmpty() -> "video" in newOutput
                // v1.0.8 (7.4): KnownModels 明确声明输出不含 video 时,忽略上游 supportsVideo
                knownInfo?.outputModalities?.isNotEmpty() == true &&
                    "video" !in (knownInfo.outputModalities.map { it.wireName }.toSet()) -> false
                else -> model.supportsVideo || "video" in newOutput
            },
            // v1.0.4: 仅当模型未显式声明时才用 registry 解析的值
            visionCapabilities = model.visionCapabilities ?: resolvedVisionCaps,
            contextWindow = newContextWindow,
            maxOutputTokens = newMaxOutputTokens,
            // v1.0.53: 填充数据可信度标注,供 UI 提示用户
            verification = verification,
        )
    }
}
