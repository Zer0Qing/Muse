package io.zer0.muse.data.preset

import io.zer0.ai.core.FreeModelConfig
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility

/**
 * 内置免费额度的模型清单。
 *
 * 历史说明：本对象原名「SiliconFlow 免费模型」，免费额度已迁到 agnes 网关
 * （`apihub.agnes-ai.com`），类名保留是为了不动 12 处调用点；
 * 真正的供应商 id 与 base url 一律读 [FreeModelConfig]，不要在别处另写一份。
 *
 * 用途有两处，必须保持一致：
 *  1. 预设供应商预填的模型（用户未填 key 时直接可用）；
 *  2. 供应商编辑页「一键填入免费模型」。
 *
 * 与 [FreeModelConfig.FREE_MODEL_IDS] 的关系：那份是「谁可以用内置 key」的白名单，
 * 这里是「界面上展示哪些模型」。两边不一致时，用户会选中一个列表里没有额度的模型，
 * 请求带着空 key 出去 —— 所以改动时两处一起改。
 */
object SiliconFlowFreeModels {

    /** 免费额度上游 OpenAI 兼容端点。 */
    const val BASE_URL = FreeModelConfig.AGNES_BASE_URL

    /** 预设 Provider 的固定 id(与 PresetProviders.siliconFlowFree 中保持一致)。 */
    const val PROVIDER_ID = FreeModelConfig.FREE_PROVIDER_ID

    /**
     * 免费额度可用模型。
     *
     * 能力按上游实测填：agnes-2.5-flash 接受图文输入，单请求 max_tokens 到 65536
     * 不被拒，返回里带 reasoning_content，因此同时标 REASONING 与 TOOL。
     * 上下文长度按平台声明填 512k；它只影响 UI 上的占用提示与裁剪预算，
     * 填小了会提前截断，填大了只是显示乐观。
     */
    val MODELS: List<Model> = listOf(
        Model(
            id = "agnes-2.5-flash",
            name = "agnes-2.5-flash (免费)",
            providerId = PROVIDER_ID,
            contextWindow = 512_000,
            maxOutputTokens = 65_536,
            supportsVision = true,
            inputModalities = setOf("text", "image"),
            abilities = setOf(ModelAbility.TOOL, ModelAbility.REASONING),
        ),
    )
}
