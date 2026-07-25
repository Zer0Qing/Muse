package io.zer0.ai.ollama

/**
 * Ollama 模型能力推断器。
 *
 * P2-3: Ollama 的 /api/tags 与 /v1/models 接口均不返回模型能力字段
 * (supportsVision / supportsTools),需要根据模型名做正则启发式推断,
 * 让 UI 层能正确显示视觉/工具调用能力标识。
 *
 * 设计原则:
 *  - 推断结果仅作"显示与初步判断"用,不阻塞发送 tools 字段(实际是否支持
 *    由模型自行决定,发错最多 400,不会损坏数据);
 *  - 关键字匹配大小写不敏感,覆盖常见视觉/工具模型家族;
 *  - 未命中关键字一律返回 false(保守兜底,避免误报)。
 *
 * 规则参考:
 *  - 视觉模型家族: llava / llama3.2-vision / qwen2-vl / qwen2.5-vl /
 *    gemma3 / pixtral / moondream / minicpm-v / internvl / phi3-vision 等
 *  - 工具调用模型家族: llama3.1 / llama3.3 / qwen2.5 / qwen3 / mistral /
 *    mixtral / command-r / phi3 / gemma3 / deepseek-r1 等
 */
object OllamaVisionInferrer {

    // ──────────────────────────────────────────────────────────────
    // 一、视觉模型关键字(大小写不敏感,正则匹配)
    // ──────────────────────────────────────────────────────────────

    /**
     * 视觉能力关键字列表。
     * 命中任意一个即判定为支持视觉输入。
     *
     * 注意: "vision" 作为通用关键字放在最后,作为兜底匹配
     * (如 "xxx-vision" / "vision-model" 等命名变体)。
     */
    private val VISION_KEYWORDS: List<Regex> = listOf(
        "llava",
        "llama3\\.2-vision",
        "qwen2-vl",
        "qwen2\\.5-vl",
        "gemma3",
        "pixtral",
        "moondream",
        "minicpm-v",
        "internvl",
        "phi3-vision",
        "phi-3-vision",
        "vision",
    ).map { keyword ->
        // 大小写不敏感,边界宽松匹配(关键字作为子串即可)
        Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE)
    }

    // ──────────────────────────────────────────────────────────────
    // 二、工具调用模型关键字(大小写不敏感,正则匹配)
    // ──────────────────────────────────────────────────────────────

    /**
     * 工具调用能力关键字列表。
     * 命中任意一个即判定为支持 function calling。
     */
    private val TOOLS_KEYWORDS: List<Regex> = listOf(
        "llama3\\.1",
        "llama3\\.3",
        "qwen2\\.5",
        "qwen3",
        "mistral",
        "mixtral",
        "command-r",
        "phi3",
        "gemma3",
        "deepseek-r1",
    ).map { keyword ->
        Regex(Regex.escape(keyword), RegexOption.IGNORE_CASE)
    }

    // ──────────────────────────────────────────────────────────────
    // 三、对外 API
    // ──────────────────────────────────────────────────────────────

    /**
     * 基于模型名推断 Ollama 模型是否支持视觉输入。
     *
     * 匹配规则: 大小写不敏感,模型 id 包含 [VISION_KEYWORDS] 中任意关键字即返回 true,
     * 否则返回 false 兜底。
     *
     * @param modelId Ollama 模型 id(如 "llava:13b" / "gemma3:12b" / "qwen2.5:7b")
     * @return true 表示推断支持视觉输入
     */
    fun inferSupportsVision(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        return VISION_KEYWORDS.any { it.containsMatchIn(modelId) }
    }

    /**
     * 基于模型名推断是否支持工具调用(function calling)。
     *
     * 匹配规则: 大小写不敏感,模型 id 包含 [TOOLS_KEYWORDS] 中任意关键字即返回 true,
     * 否则返回 false 兜底。
     *
     * @param modelId Ollama 模型 id(如 "llama3.1:8b" / "qwen3:32b" / "mistral:7b")
     * @return true 表示推断支持工具调用
     */
    fun inferSupportsTools(modelId: String): Boolean {
        if (modelId.isBlank()) return false
        return TOOLS_KEYWORDS.any { it.containsMatchIn(modelId) }
    }
}
