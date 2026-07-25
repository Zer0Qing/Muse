package io.zer0.ai.core

/**
 * v1.0.7: Provider 专属 system prompt 补丁(对齐 openhanako provider-prompt-patches)。
 *
 * 设计原则(对齐 openhanako):
 *  - 单一职责:本模块只负责**生成**补丁文本数组,不关心如何注入;
 *    注入由调用方(OpenAIProvider.buildRequestBody / buildResponsesRequestBody)统一处理,
 *    把补丁追加到 system 消息 content 末尾或 Responses API 的 instructions 字段。
 *  - 临时补丁:每个补丁都应写明删除条件(厂商修复底层问题后可移除)。
 *  - 按 locale 输出中/英双语,默认走中文(1muse 主语言)。
 *
 * 当前实现的补丁:
 *  - [deepseekOutputContractPrompt]:DeepSeek 推理模型输出契约
 *    (防止模型只输出 reasoning_content / thinking 而不写 final assistant content)
 *
 * 独立编写,参考 openhanako core/provider-prompt-patches.ts。
 */
object ProviderPromptPatches {

    /**
     * 生成 Provider 专属 system prompt 补丁列表。
     *
     * @param model 目标模型(用于判定模型家族)
     * @param baseUrl Provider 的 baseUrl(用于判定厂商,如 api.deepseek.com)
     * @param thinkingFormat ProviderCompat 派生的思考格式(用于判定 DeepSeek 家族)
     * @param reasoningLevel 推理等级(OFF 时不注入任何补丁)
     * @param locale 语言标签(null 默认中文,"zh*" 中文,其他英文)
     * @return 补丁文本列表;空列表表示无补丁需要注入
     */
    fun getProviderPromptPatches(
        model: Model,
        baseUrl: String,
        thinkingFormat: ThinkingFormat?,
        reasoningLevel: ReasoningLevel,
        locale: String? = null,
    ): List<String> {
        // 思考关闭时不注入任何补丁(对齐 openhanako isThinkingOff 判定)
        if (reasoningLevel == ReasoningLevel.OFF) return emptyList()

        val patches = mutableListOf<String>()

        // DeepSeek 推理模型输出契约
        if (isDeepSeekReasoningModel(model, baseUrl, thinkingFormat)) {
            patches.add(deepseekOutputContractPrompt(locale))
        }

        return patches
    }

    /**
     * 判定是否为 DeepSeek 推理模型(对齐 openhanako isDeepSeekReasoningModel)。
     *
     * 判定路径(任一命中即视为 DeepSeek 家族,且满足推理模型条件):
     *  1. baseUrl 包含 api.deepseek.com(官方端点)
     *  2. modelId 包含 deepseek-reasoner / deepseek-r1 / deepseek-v4(推理模型 id)
     *  3. modelId 包含 deepseek-ai/ 或 deepseek/(第三方托管前缀,如 OpenRouter / SiliconFlow)
     *  4. thinkingFormat == DEEPSEEK(ProviderCompat 派生结果)
     *
     * 注:不限制 provider — OpenRouter / SiliconFlow / ModelScope 等第三方托管的
     *   DeepSeek 模型(如 deepseek-ai/DeepSeek-R1)也会命中(对齐 openhanako 测试用例)。
     *
     * @param model 目标模型
     * @param baseUrl Provider 的 baseUrl
     * @param thinkingFormat ProviderCompat 派生的思考格式
     */
    private fun isDeepSeekReasoningModel(
        model: Model,
        baseUrl: String,
        thinkingFormat: ThinkingFormat?,
    ): Boolean {
        // 快速路径:thinkingFormat 已派生为 DEEPSEEK
        if (thinkingFormat == ThinkingFormat.DEEPSEEK) return true

        val modelId = model.id.lowercase()
        val host = baseUrl.lowercase()

        // 非官方端点 + 非 deepseek modelId → 不是 DeepSeek 家族
        val isDeepSeekHost = host.contains("api.deepseek.com")
        val isDeepSeekModelId = modelId.contains("deepseek-ai/") ||
            modelId.contains("deepseek/") ||
            modelId.startsWith("deepseek-")
        if (!isDeepSeekHost && !isDeepSeekModelId) return false

        // 已确认是 DeepSeek 家族,进一步判定是否推理模型:
        //  - deepseek-reasoner / deepseek-r1 / deepseek-v4 系列(deepseek-v4 / deepseek-v4-pro 等)
        //  - deepseek-ai/DeepSeek-R1(第三方托管的 R1)
        //  - deepseek-ai/deepseek-r1(小写形式)
        //  - 普通的 deepseek-chat / deepseek-v3 / deepseek-v3.1 不触发(非推理模型)
        return modelId.contains("deepseek-reasoner") ||
            modelId.contains("deepseek-r1") ||
            modelId.contains("deepseek-v4") ||
            modelId.contains("deepseek-reasoning")
    }

    /**
     * DeepSeek 推理模型输出契约补丁(对齐 openhanako deepseekOutputContractPrompt)。
     *
     * 删除条件:DeepSeek 推理模型在官方与第三方 provider 上都能稳定把用户可见答案
     * 写入 final assistant content 后,可移除此补丁。
     *
     * 问题背景:DeepSeek-R1 / DeepSeek-V4 等推理模型在部分场景下只输出
     * reasoning_content / thinking(思考链)就结束本轮回复,导致用户看不到最终答案。
     * 此补丁明确要求模型在思考结束后把答案写入 final assistant content。
     *
     * @param locale 语言标签(null 默认中文,"zh*" 中文,其他英文)
     */
    private fun deepseekOutputContractPrompt(locale: String?): String {
        val isZh = locale == null || locale.startsWith("zh", ignoreCase = true)
        return if (isZh) {
            """
如果你使用的是 DeepSeek 模型,请遵守以下 DeepSeek 输出契约:
reasoning_content / thinking 只用于内部推理草稿。
任何需要展示给用户的回答、建议、代码、列表、问题、摘要、结论,都必须在思考结束后写入最终 assistant content。
不要只输出 reasoning_content / thinking 就结束本轮回复。
如果使用 <think> 标签,必须先关闭思考标签,再输出最终回答。
            """.trimIndent()
        } else {
            """
If you are using a DeepSeek model, follow this DeepSeek output contract:
reasoning_content / thinking is only for private reasoning scratch work.
Any user-facing answer, recommendation, code, list, question, summary, or conclusion must be written into the final assistant content after thinking.
Do not end a response with only reasoning_content / thinking.
If you use <think> tags, close the thinking tag before emitting the final answer.
            """.trimIndent()
        }
    }
}
