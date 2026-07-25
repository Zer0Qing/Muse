package io.zer0.muse.data.preset

import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility

/**
 * P2-5: SiliconFlow 平台免费模型兜底清单。
 *
 * 背景:
 *  - SiliconFlow 在 `https://api.siliconflow.cn/v1` 提供 OpenAI 兼容端点,
 *    并对部分开源模型标注 `:free` 后缀(免费可用,但通常有 RPM/QPS 上限)。
 *  - 用户拿到 SiliconFlow 的 apiKey 后,常因不知道"哪些模型免费"而逐个尝试,
 *    体验较差。本对象预置一份当前已知免费可用的模型清单,
 *    在 ProviderEditPage(当 baseUrl 命中 siliconflow.cn 时)提供「一键填入免费模型」入口。
 *
 * 设计:
 *  - 仅记录模型 [Model.id] 与基础能力(supportsVision / abilities / contextWindow),
 *    不含定价信息(平台策略会变动,以官网为准)。
 *  - [PROVIDER_ID] 用预设 Provider 的固定 id,确保预设阶段 Model.providerId 不为空;
 *    用户保存为真实 Provider 后,ModelRegistry.enrich / ProviderConfig 持久化时
 *    会按 Provider.id 同步 providerId,无需此处关心。
 *  - supportsVision 仅对真实支持图像输入的模型(如 Qwen2.5-VL 系列)置 true;
 *    本清单目前不含 VL 变体,故全部为 false,保留字段以便后续扩展。
 *  - supportsTools 通过 [ModelAbility.TOOL] 标记;DeepSeek-R1 为纯推理模型,
 *    标 [ModelAbility.REASONING] 而不开 TOOL。
 *
 * 维护:
 *  - 模型清单会随 SiliconFlow 平台策略变动(新增 / 下架 / 限速调整),
 *    定期对照 `https://siliconflow.cn/zh-cn/models` 校对一次即可。
 */
object SiliconFlowFreeModels {

    /** SiliconFlow 平台 OpenAI 兼容端点。 */
    const val BASE_URL = "https://api.siliconflow.cn/v1"

    /** 预设 Provider 的固定 id(与 PresetProviders.siliconFlowFree 中保持一致)。 */
    const val PROVIDER_ID = "preset_siliconflow_free"

    /**
     * SiliconFlow 平台当前免费可用的模型列表(不含定价,会随平台策略变动)。
     *
     * 说明:
     *  - contextWindow 取各家厂商公布的官方上下文长度(单位:token);
     *    如有歧义保守取较小值,避免 UI 上的"上下文占用小圈"显示过大。
     *  - 不含 maxOutputTokens(留空让运行时按上游 /models 自行声明)。
     *  - 输入/输出模态默认 ["text"](非视觉模型),视觉模型按需补 "image"。
     */
    val MODELS: List<Model> = listOf(
        // ── Qwen2.5 Instruct 系列(支持 tool calling,无视觉)──
        Model(
            id = "Qwen/Qwen2.5-7B-Instruct",
            name = "Qwen2.5 7B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        Model(
            id = "Qwen/Qwen2.5-14B-Instruct",
            name = "Qwen2.5 14B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        Model(
            id = "Qwen/Qwen2.5-32B-Instruct",
            name = "Qwen2.5 32B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        Model(
            id = "Qwen/Qwen2.5-72B-Instruct",
            name = "Qwen2.5 72B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        // ── DeepSeek 系列(V2.5 支持 tool calling;R1 为纯推理模型)──
        Model(
            id = "deepseek-ai/DeepSeek-V2.5",
            name = "DeepSeek V2.5 (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        Model(
            id = "deepseek-ai/DeepSeek-R1",
            name = "DeepSeek R1 (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 65_536,
            // R1 为推理模型,不支持 tool calling(空 abilities 不进入 TOOL 分支)
            abilities = setOf(ModelAbility.REASONING),
        ),
        // ── 智谱 GLM-4-9B-Chat(支持 tool calling,无视觉)──
        Model(
            id = "THUDM/glm-4-9b-chat",
            name = "GLM-4 9B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 32_768,
            abilities = setOf(ModelAbility.TOOL),
        ),
        // ── Meta Llama 3.1 8B Instruct(支持 tool calling,无视觉)──
        Model(
            id = "meta-llama/Meta-Llama-3.1-8B-Instruct",
            name = "Llama 3.1 8B (Free)",
            providerId = PROVIDER_ID,
            contextWindow = 131_072,
            abilities = setOf(ModelAbility.TOOL),
        ),
    )
}
