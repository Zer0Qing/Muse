package io.zer0.ai.core

/**
 * 已知模型元信息词典(离线兜底)。
 *
 * 按 既有实现 项目的 known-models.ts 设计思路:本地维护一份主流模型的元信息表,
 * 让离线场景或上游 /models 接口不返回元信息时也能补全模型能力。
 *
 * 与 [ModelContextWindowRegistry] / [ModelRegistry] 的关系:
 *  - [ModelContextWindowRegistry] 仅推导 contextWindow(单一字段);
 *  - [ModelRegistry] 通过 token 序列推导 abilities / modalities(规则式);
 *  - [KnownModels] 用硬编码方式提供更完整的元信息(contextWindow + maxOutputTokens
 *    + modalities + abilities + pricing + description),三者互补。
 *
 * 调用优先级(由 [ModelRegistry.enrich] 协调):
 *  1. [Model] 自身已有字段(用户/Provider 显式设置)
 *  2. [KnownModels.lookup] 兜底补全
 *  3. [ModelRegistry] / [ModelContextWindowRegistry] 的推断逻辑
 *
 * 数据来源:各厂商公开文档(截至 2025-08)。数字保守,不准的字段留 null。
 * Pricing 单位:USD per 1M tokens(prompt / completion)。
 */
object KnownModels {

    // ──────────────────────────────────────────────────────────────
    // 一、Modality 枚举
    // ──────────────────────────────────────────────────────────────

    /**
     * 模态枚举。wireName 与 [Model.inputModalities] / [Model.outputModalities]
     * 中使用的字符串保持一致("text" / "image" / "audio" / "video" / "file")。
     */
    enum class Modality(val wireName: String) {
        TEXT("text"),
        IMAGE("image"),
        AUDIO("audio"),
        VIDEO("video"),
        FILE("file"),
    }

    // ──────────────────────────────────────────────────────────────
    // 二、KnownModelInfo 数据类
    // ──────────────────────────────────────────────────────────────

    /**
     * 单个已知模型的元信息。
     *
     * 所有字段均可空,未确认的字段保持 null,由调用方决定是否使用。
     *
     * @param contextWindow 上下文窗口大小(token 数)
     * @param maxOutputTokens 单次最大输出 token 数
     * @param inputModalities 输入模态集合(空集表示未声明)
     * @param outputModalities 输出模态集合
     * @param abilities 能力集合(TOOL / REASONING)
     * @param pricingPromptPer1M prompt 端价格(USD / 1M tokens)
     * @param pricingCompletionPer1M completion 端价格(USD / 1M tokens)
     * @param description 简短描述(可选)
     */
    data class KnownModelInfo(
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val inputModalities: Set<Modality> = emptySet(),
        val outputModalities: Set<Modality> = emptySet(),
        val abilities: Set<ModelAbility> = emptySet(),
        val pricingPromptPer1M: Double? = null,
        val pricingCompletionPer1M: Double? = null,
        val description: String? = null,
    )

    // ──────────────────────────────────────────────────────────────
    // 三、内部常量与工具
    // ──────────────────────────────────────────────────────────────

    private val TEXT_IN: Set<Modality> = setOf(Modality.TEXT)
    private val VISION_IN: Set<Modality> = setOf(Modality.TEXT, Modality.IMAGE)
    private val TEXT_OUT: Set<Modality> = setOf(Modality.TEXT)

    private val TOOL: ModelAbility = ModelAbility.TOOL
    private val REASONING: ModelAbility = ModelAbility.REASONING
    private val TOOL_ONLY: Set<ModelAbility> = setOf(TOOL)
    private val REASONING_ONLY: Set<ModelAbility> = setOf(REASONING)

    /**
     * provider+modelId 联合键(均小写)。
     */
    private data class ProviderKey(val provider: String, val modelId: String)

    /**
     * 部分中转/聚合平台会在模型 ID 前加 provider 前缀(如 openrouter/、openai/),
     * 剥掉后再查核心 modelId。
     */
    private val KNOWN_PREFIXES: List<String> = listOf(
        "openrouter/",
        "opencode-go/",
        "anthropic/",
        "google/",
        "openai/",
        "meta-llama/",
        "mistralai/",
        "nousresearch/",
        "deepinfra/",
        "togethercomputer/",
        "accounts/fireworks/models/",
        "presets/",
    )

    private fun stripPrefix(id: String): String {
        for (prefix in KNOWN_PREFIXES) {
            if (id.startsWith(prefix)) return id.removePrefix(prefix)
        }
        return id
    }

    // ──────────────────────────────────────────────────────────────
    // 四、预置模型词典(均以小写 modelId 为键)
    // ──────────────────────────────────────────────────────────────

    private val known: Map<String, KnownModelInfo> = buildMap {
        // ── OpenAI 系列 ──
        put("gpt-4o", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 16384,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 2.50,
            pricingCompletionPer1M = 10.00,
            description = "OpenAI GPT-4o 多模态旗舰",
        ))
        put("gpt-4o-mini", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 16384,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.15,
            pricingCompletionPer1M = 0.60,
            description = "OpenAI GPT-4o mini 轻量版",
        ))
        put("gpt-4-turbo", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 4096,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 10.00,
            pricingCompletionPer1M = 30.00,
            description = "OpenAI GPT-4 Turbo",
        ))
        put("gpt-4", KnownModelInfo(
            contextWindow = 8192,
            maxOutputTokens = 4096,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 30.00,
            pricingCompletionPer1M = 60.00,
            description = "OpenAI GPT-4 原始版(8K)",
        ))
        put("gpt-3.5-turbo", KnownModelInfo(
            contextWindow = 16385,
            maxOutputTokens = 4096,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.50,
            pricingCompletionPer1M = 1.50,
            description = "OpenAI GPT-3.5 Turbo",
        ))
        put("o1", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 100000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 15.00,
            pricingCompletionPer1M = 60.00,
            description = "OpenAI o1 推理模型",
        ))
        put("o1-mini", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 65536,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = REASONING_ONLY,
            pricingPromptPer1M = 3.00,
            pricingCompletionPer1M = 12.00,
            description = "OpenAI o1-mini 推理模型",
        ))
        put("o1-pro", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 100000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 150.00,
            pricingCompletionPer1M = 600.00,
            description = "OpenAI o1-pro 高性能推理",
        ))
        put("o3", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 100000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 10.00,
            pricingCompletionPer1M = 40.00,
            description = "OpenAI o3 推理模型",
        ))
        put("o3-mini", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 100000,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 1.10,
            pricingCompletionPer1M = 4.40,
            description = "OpenAI o3-mini 推理模型",
        ))
        put("o4-mini", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 100000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 1.10,
            pricingCompletionPer1M = 4.40,
            description = "OpenAI o4-mini 推理模型",
        ))
        put("gpt-5", KnownModelInfo(
            contextWindow = 400000,
            maxOutputTokens = 128000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 5.00,
            pricingCompletionPer1M = 15.00,
            description = "OpenAI GPT-5 旗舰",
        ))
        put("gpt-5-mini", KnownModelInfo(
            contextWindow = 400000,
            maxOutputTokens = 128000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 0.50,
            pricingCompletionPer1M = 1.50,
            description = "OpenAI GPT-5 mini",
        ))
        // v1.0.6: 新增 GPT-5.1 / Codex(对齐 既有实现 known-models)
        put("gpt-5.1", KnownModelInfo(
            contextWindow = 400000,
            maxOutputTokens = 128000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 5.00,
            pricingCompletionPer1M = 15.00,
            description = "OpenAI GPT-5.1",
        ))
        put("gpt-5-codex", KnownModelInfo(
            contextWindow = 400000,
            maxOutputTokens = 128000,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "OpenAI GPT-5 Codex 代码模型",
        ))

        // ── Anthropic Claude 系列 ──
        put("claude-3-opus", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 4096,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 15.00,
            pricingCompletionPer1M = 75.00,
            description = "Anthropic Claude 3 Opus",
        ))
        put("claude-3-sonnet", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 4096,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 3.00,
            pricingCompletionPer1M = 15.00,
            description = "Anthropic Claude 3 Sonnet",
        ))
        put("claude-3-haiku", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 4096,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.25,
            pricingCompletionPer1M = 1.25,
            description = "Anthropic Claude 3 Haiku",
        ))
        put("claude-3.5-sonnet", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 3.00,
            pricingCompletionPer1M = 15.00,
            description = "Anthropic Claude 3.5 Sonnet",
        ))
        put("claude-3.5-haiku", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.80,
            pricingCompletionPer1M = 4.00,
            description = "Anthropic Claude 3.5 Haiku",
        ))
        put("claude-3.7-sonnet", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 64000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 3.00,
            pricingCompletionPer1M = 15.00,
            description = "Anthropic Claude 3.7 Sonnet(扩展思考)",
        ))
        put("claude-sonnet-4", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 64000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 3.00,
            pricingCompletionPer1M = 15.00,
            description = "Anthropic Claude Sonnet 4",
        ))
        put("claude-opus-4", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 32000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 15.00,
            pricingCompletionPer1M = 75.00,
            description = "Anthropic Claude Opus 4",
        ))
        // v1.0.6: 新增 Claude 4.5 / 4.1 系列(对齐 既有实现 known-models)
        put("claude-sonnet-4-5", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 64000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 3.00,
            pricingCompletionPer1M = 15.00,
            description = "Anthropic Claude Sonnet 4.5",
        ))
        put("claude-opus-4-1", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 32000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 15.00,
            pricingCompletionPer1M = 75.00,
            description = "Anthropic Claude Opus 4.1",
        ))
        put("claude-haiku-4-5", KnownModelInfo(
            contextWindow = 200000,
            maxOutputTokens = 16384,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 1.00,
            pricingCompletionPer1M = 5.00,
            description = "Anthropic Claude Haiku 4.5",
        ))

        // ── Google Gemini 系列 ──
        put("gemini-1.5-pro", KnownModelInfo(
            contextWindow = 2000000,
            maxOutputTokens = 8192,
            inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 1.25,
            pricingCompletionPer1M = 5.00,
            description = "Google Gemini 1.5 Pro(2M 上下文)",
        ))
        put("gemini-1.5-flash", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 8192,
            inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.075,
            pricingCompletionPer1M = 0.30,
            description = "Google Gemini 1.5 Flash",
        ))
        put("gemini-2.0-flash", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 8192,
            inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.10,
            pricingCompletionPer1M = 0.40,
            description = "Google Gemini 2.0 Flash",
        ))
        put("gemini-2.5-pro", KnownModelInfo(
            contextWindow = 2000000,
            maxOutputTokens = 8192,
            inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 1.25,
            pricingCompletionPer1M = 10.00,
            description = "Google Gemini 2.5 Pro",
        ))
        put("gemini-2.5-flash", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 65536,
            inputModalities = setOf(Modality.TEXT, Modality.IMAGE, Modality.AUDIO, Modality.VIDEO, Modality.FILE),
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 0.30,
            pricingCompletionPer1M = 2.50,
            description = "Google Gemini 2.5 Flash",
        ))

        // ── DeepSeek 系列 ──
        put("deepseek-chat", KnownModelInfo(
            contextWindow = 64000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.27,
            pricingCompletionPer1M = 1.10,
            description = "DeepSeek V3 通用对话",
        ))
        put("deepseek-reasoner", KnownModelInfo(
            contextWindow = 64000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = REASONING_ONLY,
            pricingPromptPer1M = 0.55,
            pricingCompletionPer1M = 2.19,
            description = "DeepSeek R1 推理模型",
        ))
        put("deepseek-coder", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "DeepSeek Coder 代码模型",
        ))

        // ── Moonshot / Kimi 系列 ──
        put("moonshot-v1-8k", KnownModelInfo(
            contextWindow = 8000,
            maxOutputTokens = 2048,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "Moonshot v1 8K 上下文",
        ))
        put("moonshot-v1-32k", KnownModelInfo(
            contextWindow = 32000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "Moonshot v1 32K 上下文",
        ))
        put("moonshot-v1-128k", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "Moonshot v1 128K 上下文",
        ))
        put("kimi-k2", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "Moonshot Kimi K2",
        ))
        // v1.0.53: Kimi K2.6 — 多模态版本(视觉+文本),支持工具+推理。
        // 修正:此前误标 TEXT_IN 导致中转站即使声明多模态也被 v1.137 纠错逻辑覆盖为纯文本,
        // 图片只能走视觉辅助而不能直发。
        put("kimi-k2.6", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "Moonshot Kimi K2.6(多模态)",
        ))
        put("kimi-k1", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = REASONING_ONLY,
            description = "Moonshot Kimi K1",
        ))

        // ── 通义 Qwen 系列 ──
        put("qwen-max", KnownModelInfo(
            contextWindow = 32000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "通义千问 Max 旗舰",
        ))
        put("qwen-plus", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "通义千问 Plus",
        ))
        put("qwen-turbo", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "通义千问 Turbo",
        ))
        put("qwen3-235b", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "通义千问 3 235B",
        ))
        put("qwen2.5-72b", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "通义千问 2.5 72B",
        ))
        put("qwen2.5-coder", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "通义千问 2.5 Coder 代码模型",
        ))

        // ── 字节豆包 Doubao 系列 ──
        put("doubao-pro", KnownModelInfo(
            contextWindow = 32000,
            maxOutputTokens = 4096,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "字节豆包 Pro",
        ))
        put("doubao-lite", KnownModelInfo(
            contextWindow = 32000,
            maxOutputTokens = 4096,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "字节豆包 Lite",
        ))
        put("doubao-1.6", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "字节豆包 1.6",
        ))
        put("doubao-1.5-pro", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "字节豆包 1.5 Pro",
        ))

        // ── 智谱 GLM 系列 ──
        put("glm-4-plus", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 4096,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "智谱 GLM-4-Plus",
        ))
        put("glm-4-air", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 4096,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "智谱 GLM-4-Air(轻量)",
        ))
        put("glm-4-flash", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 4096,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "智谱 GLM-4-Flash(免费)",
        ))
        put("glm-4.5", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "智谱 GLM-4.5",
        ))

        // ── Mistral 系列 ──
        put("mistral-large", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 2.00,
            pricingCompletionPer1M = 6.00,
            description = "Mistral Large 2",
        ))
        put("mistral-medium", KnownModelInfo(
            contextWindow = 32000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.40,
            pricingCompletionPer1M = 1.20,
            description = "Mistral Medium",
        ))
        put("mistral-small", KnownModelInfo(
            contextWindow = 32000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.20,
            pricingCompletionPer1M = 0.60,
            description = "Mistral Small",
        ))
        put("codestral", KnownModelInfo(
            contextWindow = 32000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.30,
            pricingCompletionPer1M = 0.90,
            description = "Mistral Codestral 代码模型",
        ))
        put("pixtral", KnownModelInfo(
            contextWindow = 128000,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 0.15,
            pricingCompletionPer1M = 0.15,
            description = "Mistral Pixtral 多模态",
        ))

        // ── xAI Grok 系列 ──
        put("grok-2", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            pricingPromptPer1M = 2.00,
            pricingCompletionPer1M = 10.00,
            description = "xAI Grok 2",
        ))
        put("grok-3", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            pricingPromptPer1M = 3.00,
            pricingCompletionPer1M = 15.00,
            description = "xAI Grok 3",
        ))
        put("grok-4", KnownModelInfo(
            contextWindow = 256000,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "xAI Grok 4",
        ))
        // v1.0.6: 新增 Grok 4.5 / 4.3 系列(对齐 既有实现 known-models,主要走 xAI OAuth Responses)
        put("grok-4.5", KnownModelInfo(
            contextWindow = 500000,
            maxOutputTokens = 128000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "xAI Grok 4.5",
        ))
        put("grok-4.5-latest", KnownModelInfo(
            contextWindow = 500000,
            maxOutputTokens = 128000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "xAI Grok 4.5 Latest",
        ))
        put("grok-build-latest", KnownModelInfo(
            contextWindow = 500000,
            maxOutputTokens = 128000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "xAI Grok Build Latest",
        ))
        put("grok-4.3", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 128000,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "xAI Grok 4.3(1M 上下文)",
        ))

        // ── MiniMax abab 系列 ──
        put("abab6.5", KnownModelInfo(
            contextWindow = 245768,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "MiniMax abab6.5",
        ))
        put("abab7", KnownModelInfo(
            contextWindow = 245768,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "MiniMax abab7",
        ))
        put("minimax-m3", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = TOOL_ONLY,
            description = "MiniMax M3 视觉模型",
        ))

        // ── 思必驰 Agnes 系列 ──
        // v1.0.8: Agnes chat / image / video 模型(对齐 PresetProviders.agnes)
        put("agnes-2.0-flash", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL),
            description = "Agnes 2.0 Flash 多模态对话",
        ))
        put("agnes-2.0-pro", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = VISION_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL),
            description = "Agnes 2.0 Pro 多模态对话",
        ))
        // 生图模型:context=0(不适用),仅输出 image 模态
        put("agnes-image-2.1-flash", KnownModelInfo(
            contextWindow = 0,
            maxOutputTokens = null,
            inputModalities = emptySet(),
            outputModalities = setOf(Modality.IMAGE),
            abilities = emptySet(),
            description = "Agnes Image 2.1 Flash 文生图模型",
        ))
        // 视频模型:context=0(不适用),仅输出 video 模态
        put("agnes-video-v2.0", KnownModelInfo(
            contextWindow = 0,
            maxOutputTokens = null,
            inputModalities = emptySet(),
            outputModalities = setOf(Modality.VIDEO),
            abilities = emptySet(),
            description = "Agnes Video 2.0 文生视频模型",
        ))

        // ── 近期新模型补全(2025-08 后陆续上线)──
        // DeepSeek V4 系列(对齐 ModelContextWindowRegistry 精确匹配)
        put("deepseek-v4", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "DeepSeek V4 旗舰(1M 上下文)",
        ))
        put("deepseek-v4-pro", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "DeepSeek V4 Pro",
        ))
        put("deepseek-v4-flash", KnownModelInfo(
            contextWindow = 1000000,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL),
            description = "DeepSeek V4 Flash 轻量版",
        ))
        // 通义 Qwen3-Max 旗舰
        put("qwen3-max", KnownModelInfo(
            contextWindow = 131072,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL, REASONING),
            description = "通义千问 3 Max 旗舰",
        ))
        put("qwen3-coder-plus", KnownModelInfo(
            contextWindow = 1048576,
            maxOutputTokens = 8192,
            inputModalities = TEXT_IN,
            outputModalities = TEXT_OUT,
            abilities = setOf(TOOL),
            description = "通义千问 3 Coder Plus 代码模型",
        ))
    }

    // ──────────────────────────────────────────────────────────────
    // 五、Provider 特定覆盖(主要为 OpenRouter 等聚合平台标注 pricing)
    // ──────────────────────────────────────────────────────────────

    /**
     * OpenRouter 等聚合平台的 pricing 覆盖表。
     * key 为 (provider, modelId) 均小写;value 为 (promptPer1M, completionPer1M)。
     * 命中时通过 [KnownModelInfo.copy] 合并到基础 [lookup] 结果上(不覆盖已知非空字段)。
     */
    private val providerPricing: Map<ProviderKey, Pair<Double, Double>> = mapOf(
        // OpenRouter 上的热门模型 pricing(USD / 1M tokens),仅供参考
        ProviderKey("openrouter", "gpt-4o") to (2.50 to 10.00),
        ProviderKey("openrouter", "gpt-4o-mini") to (0.15 to 0.60),
        ProviderKey("openrouter", "gpt-4-turbo") to (10.00 to 30.00),
        ProviderKey("openrouter", "claude-3-opus") to (15.00 to 75.00),
        ProviderKey("openrouter", "claude-3.5-sonnet") to (3.00 to 15.00),
        ProviderKey("openrouter", "claude-3.5-haiku") to (0.80 to 4.00),
        ProviderKey("openrouter", "claude-sonnet-4") to (3.00 to 15.00),
        ProviderKey("openrouter", "claude-opus-4") to (15.00 to 75.00),
        ProviderKey("openrouter", "gemini-2.5-pro") to (1.25 to 10.00),
        ProviderKey("openrouter", "gemini-2.5-flash") to (0.30 to 2.50),
        ProviderKey("openrouter", "deepseek-chat") to (0.27 to 1.10),
        ProviderKey("openrouter", "deepseek-reasoner") to (0.55 to 2.19),
        ProviderKey("openrouter", "grok-3") to (3.00 to 15.00),
        ProviderKey("openrouter", "mistral-large") to (2.00 to 6.00),
        ProviderKey("openrouter", "qwen3-235b") to (0.20 to 0.60),
    )

    // ──────────────────────────────────────────────────────────────
    // 六、对外 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 按 modelId 大小写不敏感查找。
     * 会自动剥掉常见的 provider 前缀(如 "openrouter/gpt-4o" → "gpt-4o")。
     * 未命中返回 null。
     */
    fun lookup(modelId: String): KnownModelInfo? {
        val raw = modelId.trim().lowercase()
        if (raw.isEmpty()) return null

        // 1. 精确匹配
        known[raw]?.let { return it }

        // 2. 剥前缀后再精确匹配
        val stripped = stripPrefix(raw)
        if (stripped != raw) {
            known[stripped]?.let { return it }
        }

        // 3. 边界前缀匹配(如 "gpt-4o-2024-08-06" 命中 "gpt-4o")
        known.entries.firstOrNull { (pattern, _) ->
            raw == pattern || raw.startsWith("$pattern-") || raw.startsWith("${pattern}_") ||
                stripped == pattern || stripped.startsWith("$pattern-") || stripped.startsWith("${pattern}_")
        }?.let { return it.value }

        return null
    }

    /**
     * 按 provider + modelId 二级查找。
     *
     * 流程:
     *  1. 先用 [lookup] 拿到基础元信息(含本词典自带的 pricing)
     *  2. 再查 [providerPricing] 是否有该 provider 特定 pricing 覆盖,
     *     有的话用 [KnownModelInfo.copy] 合并(仅填充基础结果中为 null 的 pricing 字段)
     *  3. 基础结果为 null 时直接返回 null
     */
    fun lookupProvider(provider: String, modelId: String): KnownModelInfo? {
        val base = lookup(modelId) ?: return null
        val p = provider.trim().lowercase()
        if (p.isEmpty()) return base

        val raw = modelId.trim().lowercase()
        val stripped = stripPrefix(raw)

        // 查 provider 特定 pricing(分别尝试原始 / 剥前缀 modelId)
        val pricing = providerPricing[ProviderKey(p, raw)]
            ?: providerPricing[ProviderKey(p, stripped)]
            ?: return base

        return base.copy(
            pricingPromptPer1M = base.pricingPromptPer1M ?: pricing.first,
            pricingCompletionPer1M = base.pricingCompletionPer1M ?: pricing.second,
        )
    }

    /**
     * 调试用:返回所有预置 modelId(小写,按字母序)。
     */
    fun knownModelIds(): List<String> = known.keys.sorted()
}
