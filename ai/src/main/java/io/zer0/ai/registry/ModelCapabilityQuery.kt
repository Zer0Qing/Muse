package io.zer0.ai.registry

import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ProviderCompat

/**
 * M2.2/M2.8: 能力查询的三态结果。
 *
 * - [SUPPORTED]:注册表/兼容层确证支持。
 * - [UNSUPPORTED]:注册表确证不支持(如纯文本模型请求视觉输入)。
 * - [UNKNOWN]:模型未在注册表命中(新模型/私有部署名),调用方应做
 *   capability preflight 或按保守策略处理,而不是用模型名猜测后把上游
 *   错误抛给用户。
 */
enum class CapabilitySupport { SUPPORTED, UNSUPPORTED, UNKNOWN }

/**
 * M2.2: 模型能力快照 — 文本/视觉输入、工具、推理、流式、非流式、结构化输出
 * 全部显式区分,未知模型各维度返回 [CapabilitySupport.UNKNOWN]。
 *
 * app 侧只能通过本门面读取能力,不得自行解析模型名(与 ModelRegistry 的
 * 公开查询接口共同构成 M2.1 的唯一能力边界)。
 */
data class ModelCapabilitySnapshot(
    val modelId: String,
    val textInput: CapabilitySupport,
    val visionInput: CapabilitySupport,
    val toolCalling: CapabilitySupport,
    val reasoning: CapabilitySupport,
    val streaming: CapabilitySupport,
    val nonStreaming: CapabilitySupport,
    val structuredOutput: CapabilitySupport,
    /** 注册表是否命中该模型(UNKNOWN 判定的来源,便于日志与 preflight 决策)。 */
    val known: Boolean,
)

/**
 * M2.8: 能力预检门面。
 *
 * @param modelId 模型 ID(允许携带聚合前缀,内部与 ModelRegistry 相同的前缀剥离逻辑)
 * @param compat Provider 兼容层(结构化输出等参数级约束);传 null 时对应维度
 *   返回 [CapabilitySupport.UNKNOWN] 而不是猜测。ProviderCompat 由
 *   [ProviderCompatRules.resolve] 按 ProviderType/host/modelId 派生,
 *   所有内置 Provider 类型都会产生 compat 对象 —— 因此 compat 非 null 即代表
 *   该 Provider 具备流式通道。
 * @param providerSupportsNonStreaming Provider 是否实现 completeText
 *   (Provider.completeText 默认抛 UnsupportedOperationException,由调用方
 *   传入真实实现状态);null 表示调用方未提供该信息。
 */
object ModelCapabilityQuery {

    fun snapshot(
        modelId: String,
        compat: ProviderCompat? = null,
        providerSupportsNonStreaming: Boolean? = null,
    ): ModelCapabilitySnapshot {
        val definitions = ModelRegistry.resolveDefinitions(modelId)
        val known = definitions.isNotEmpty()
        val abilities = definitions.flatMap { it.abilities }.toSet()
        val inputModalities = definitions.flatMap { it.inputModalities }.toSet()
        return ModelCapabilitySnapshot(
            modelId = modelId,
            known = known,
            textInput = when {
                !known -> CapabilitySupport.UNKNOWN
                "text" in inputModalities -> CapabilitySupport.SUPPORTED
                else -> CapabilitySupport.UNSUPPORTED
            },
            visionInput = when {
                !known -> CapabilitySupport.UNKNOWN
                "image" in inputModalities -> CapabilitySupport.SUPPORTED
                else -> CapabilitySupport.UNSUPPORTED
            },
            toolCalling = triState(known, ModelAbility.TOOL in abilities),
            reasoning = triState(known, ModelAbility.REASONING in abilities),
            streaming = if (compat != null) CapabilitySupport.SUPPORTED else CapabilitySupport.UNKNOWN,
            nonStreaming = when (providerSupportsNonStreaming) {
                true -> CapabilitySupport.SUPPORTED
                false -> CapabilitySupport.UNSUPPORTED
                null -> CapabilitySupport.UNKNOWN
            },
            structuredOutput = triStateCompat(compat) { it.supportsJsonMode },
        )
    }

    /** 简单布尔能力 → 三态(注册表未命中 → UNKNOWN)。 */
    private fun triState(known: Boolean, supported: Boolean): CapabilitySupport = when {
        !known -> CapabilitySupport.UNKNOWN
        supported -> CapabilitySupport.SUPPORTED
        else -> CapabilitySupport.UNSUPPORTED
    }

    /**
     * Provider 级能力 → 三态。Provider 侧协议默认同时支持流式与非流式
     * (所有内置 Provider 的 streamChat/completeText 都有实现),差异集中在
     * json_mode 等参数级支持,由 [ProviderCompat] 提供;无 compat 时 UNKNOWN。
     */
    private fun triStateCompat(
        compat: ProviderCompat?,
        supported: (ProviderCompat) -> Boolean,
    ): CapabilitySupport = when {
        compat == null -> CapabilitySupport.UNKNOWN
        supported(compat) -> CapabilitySupport.SUPPORTED
        else -> CapabilitySupport.UNSUPPORTED
    }
}
