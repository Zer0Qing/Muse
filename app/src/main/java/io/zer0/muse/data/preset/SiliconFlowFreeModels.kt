package io.zer0.muse.data.preset

import io.zer0.ai.core.FreeModelConfig
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility

/**
 * 内置免费额度的模型清单。
 *
 * 免费档的接口地址、密钥与模型由**服务端不定期轮换**（见 [FreeModelConfig]），
 * 因此本对象不持有任何写死的模型常量，而是每次按当前生效配置投影。
 *
 * 类名沿用历史名称（12 处调用点），语义已改为「免费档模型投影」。
 *
 * 用途有两处，必须保持一致：
 *  1. 预设供应商预填的模型（用户未填 key 时直接可用）；
 *  2. 供应商编辑页「一键填入免费模型」。
 *
 * 与 [FreeModelConfig.allowedModelIds] 的关系：那份是「谁可以用派发 key」的白名单，
 * 这里是「界面上展示哪些模型」。两者都由同一份当前配置派生，不会不一致。
 */
object SiliconFlowFreeModels {

    /** 免费档上游 OpenAI 兼容端点（随轮换变化）。 */
    val BASE_URL: String get() = FreeModelConfig.currentBaseUrl()

    /** 预设 Provider 的固定 id(与 PresetProviders.siliconFlowFree 中保持一致)。 */
    const val PROVIDER_ID = FreeModelConfig.FREE_PROVIDER_ID

    /**
     * 当前免费档可用模型（按派发配置投影）。
     *
     * **无派发配置时仍返回条目**：让用户在选择器里看得见「有个免费模型」，
     * 只是界面会同时显示「服务不可用，请填写 API Key」。若这里返回空列表，
     * 条目直接消失，用户既不知道有这个选项，也看不到那句提示。
     *
     * 能力按服务端下发字段填；未声明时保守取 false，
     * 避免把不支持的能力标成可用导致请求被拒。
     */
    val MODELS: List<Model>
        get() {
            val config = FreeModelConfig.current()
            val abilities = buildSet {
                if (config.supportsTools) add(ModelAbility.TOOL)
                if (config.supportsReasoning) add(ModelAbility.REASONING)
            }
            val modalities = if (config.supportsVision) setOf("text", "image") else setOf("text")
            return listOf(
                Model(
                    id = config.model,
                    name = config.effectiveDisplayName,
                    providerId = PROVIDER_ID,
                    contextWindow = config.contextWindow.takeIf { it > 0 } ?: DEFAULT_CONTEXT_WINDOW,
                    maxOutputTokens = config.maxOutputTokens.takeIf { it > 0 } ?: DEFAULT_MAX_OUTPUT,
                    supportsVision = config.supportsVision,
                    inputModalities = modalities,
                    abilities = abilities,
                ),
            )
        }

    private const val DEFAULT_CONTEXT_WINDOW = 128_000
    private const val DEFAULT_MAX_OUTPUT = 8_192
}
