package io.zer0.muse.data.preset

import android.content.Context
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.R

/**
 * v1.0.6: 预置供应商清单 — 全量对齐 openhanako 的 37 个内置供应商,
 * 另保留 1muse 特有的 8 个(deepinfra / lingyi / github-copilot / 6 个中转站模板),
 * 合计 45 个预置供应商。
 *
 * 对齐内容(参考 openhanako/lib/providers/):
 *  - 供应商 id / displayName / baseUrl / defaultApi 协议 / authType
 *  - 每个供应商内嵌默认 chat 模型列表(来自 openhanako/lib/default-models.json)
 *  - 4 个 Coding Plan 供应商(dashscope-coding / kimi-coding / volcengine-coding / zhipu-coding)
 *    标记 specific.codingPlan = true
 *  - minimax / minimax-token-plan 走 Anthropic Messages 协议(baseUrl = /anthropic)
 *  - xai-oauth 走 OpenAI Responses 协议(type = OPENAI_RESPONSES)
 *  - ollama 走 authType=none 语义(allowMissingApiKey = true)
 *
 * 1muse 特有保留:
 *  - SiliconFlow 免费模型预填 8 个模型 + UI「一键填入免费模型」按钮
 *  - GitHub Copilot OAuth 预设(openhanako 无)
 *  - deepinfra / lingyi 两个海外/国产供应商(openhanako 无)
 *  - 6 个中转站模板(opencode / api2d / aihubmix / deepbricks / oneapi / newapi)
 *
 * 分类:
 *  - 海外官方(15): OpenAI / Anthropic / Gemini / xAI / xAI-OAuth / OpenAI-Codex /
 *    GitHub-Copilot / Groq / Together / Mistral / OpenRouter / DeepInfra / Fireworks /
 *    Perplexity / Ollama
 *  - 国产官方(22): DeepSeek / 通义 / 智谱 / Kimi / 豆包 / 百川 / 零一 / 阶跃 / SiliconFlow /
 *    混元 / 百度 / 魔搭 / 无问芯穹 / 小米MiMo / 思必驰Agnes / MiniMax /
 *    百炼Coding / Kimi-Coding / 豆包Coding / 智谱Coding /
 *    MiniMax-Token-Plan / 小米MiMo-Token-Plan
 *  - 中转站(6): OpenCode / API2D / AiHubMix / DeepBricks / OneAPI / NewAPI
 *
 * 调用方: SettingsRepository.presetProviders(首次启动 / Onboarding / "添加 Provider" 时展示)
 */
class PresetProviders(
    private val context: Context,
) {

    // ── 端点常量(集中管理,便于统一修改)─────────────────────────────
    private val ENDPOINT_GROQ = "https://api.groq.com/openai/v1"
    private val ENDPOINT_TOGETHER = "https://api.together.xyz/v1"
    private val ENDPOINT_MISTRAL = "https://api.mistral.ai/v1"
    private val ENDPOINT_OPENROUTER = "https://openrouter.ai/api/v1"
    private val ENDPOINT_DEEPINFRA = "https://api.deepinfra.com/v1/openai"
    private val ENDPOINT_FIREWORKS = "https://api.fireworks.ai/inference/v1"
    private val ENDPOINT_PERPLEXITY = "https://api.perplexity.ai"
    private val ENDPOINT_OLLAMA = "http://localhost:11434/v1"
    private val ENDPOINT_DEEPSEEK = "https://api.deepseek.com/v1"
    private val ENDPOINT_QWEN = "https://dashscope.aliyuncs.com/compatible-mode/v1"
    private val ENDPOINT_ZHIPU = "https://open.bigmodel.cn/api/paas/v4"
    private val ENDPOINT_MOONSHOT = "https://api.moonshot.cn/v1"
    private val ENDPOINT_DOUBAO = "https://ark.cn-beijing.volces.com/api/v3"
    private val ENDPOINT_BAICHUAN = "https://api.baichuan-ai.com/v1"
    private val ENDPOINT_LINGYI = "https://api.lingyiwanwu.com/v1"
    private val ENDPOINT_STEPFUN = "https://api.stepfun.com/v1"
    private val ENDPOINT_HUNYUAN = "https://api.hunyuan.cloud.tencent.com/v1"
    private val ENDPOINT_BAIDU = "https://qianfan.baidubce.com/v2"
    private val ENDPOINT_MODELSCOPE = "https://api-inference.modelscope.cn/v1"
    private val ENDPOINT_INFINI = "https://cloud.infini-ai.com/maas/v1"
    private val ENDPOINT_MIMO = "https://api.xiaomimimo.com/v1"
    private val ENDPOINT_MIMO_TOKEN_PLAN = "https://token-plan-cn.xiaomimimo.com/v1"
    private val ENDPOINT_AGNES = "https://apihub.agnes-ai.com/v1"
    private val ENDPOINT_MINIMAX = "https://api.minimaxi.com/anthropic"
    private val ENDPOINT_DASHSCOPE_CODING = "https://coding.dashscope.aliyuncs.com/v1"
    private val ENDPOINT_KIMI_CODING = "https://api.kimi.com/coding/v1"
    private val ENDPOINT_DOUBAO_CODING = "https://ark.cn-beijing.volces.com/api/coding/v3"
    private val ENDPOINT_ZHIPU_CODING = "https://api.z.ai/api/coding/paas/v4"
    private val ENDPOINT_OPENCODE = "https://opencode.ai/zen/go/v1"
    private val ENDPOINT_API2D = "https://oa.api2d.net/v1"
    private val ENDPOINT_AIHUBMIX = "https://aihubmix.com/v1"
    private val ENDPOINT_DEEPBRICKS = "https://api.deepbricks.ai/v1"
    // P2-5: SiliconFlow 平台 OpenAI 兼容端点(与 SiliconFlowFreeModels.BASE_URL 保持一致)
    private val ENDPOINT_SILICONFLOW = SiliconFlowFreeModels.BASE_URL

    // ── 供应商分组 ─────────────────────────────────────────────────

    /** 海外官方厂商(15 个)。 */
    val overseas: List<ProviderConfig> = listOf(
        openai(),
        anthropic(),
        gemini(),
        xai(),
        xaiOauth(),
        openaiCodex(),
        githubCopilot(),
        groq(),
        together(),
        mistral(),
        openrouter(),
        deepInfra(),
        fireworks(),
        perplexity(),
        ollama(),
    )

    /** 国产官方厂商(22 个,含 4 个 Coding Plan + 2 个 Token Plan)。 */
    val domestic: List<ProviderConfig> = listOf(
        deepseek(),
        qwen(),
        zhipu(),
        moonshot(),
        doubao(),
        baichuan(),
        lingyi(),
        stepfun(),
        // P2-5: SiliconFlow 免费模型兜底(国产开源模型聚合平台)
        siliconFlowFree(),
        // v1.0.6: 新增国产供应商(对齐 openhanako)
        hunyuan(),
        baiduCloud(),
        modelscope(),
        infini(),
        mimo(),
        agnes(),
        minimax(),
        // v1.0.6: Coding Plan 系列(对齐 openhanako *-coding)
        dashscopeCoding(),
        kimiCoding(),
        volcengineCoding(),
        zhipuCoding(),
        // v1.0.6: Token Plan 系列(对齐 openhanako *-token-plan)
        minimaxTokenPlan(),
        mimoTokenPlan(),
    )

    /** 中转站(6 个,1muse 特有)。 */
    val relay: List<ProviderConfig> = listOf(
        opencode(),
        api2d(),
        aihubmix(),
        deepbricks(),
        oneapiTemplate(),
        newapiTemplate(),
    )

    /**
     * 自定义 OpenAI 兼容供应商模板。
     * 用户在引导页选中后需手动填写 baseUrl + apiKey。
     */
    private fun customOpenAI() = ProviderConfig(
        id = "preset_custom_openai",
        displayName = context.getString(R.string.onboarding_provider_custom),
        type = ProviderType.OPENAI,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /**
     * 引导页展示的精选供应商。
     * 海外/国产/中转严格按 1:1:1 比例各取 3 个,并追加自定义选项。
     */
    val onboardingPresets: List<ProviderConfig> = (
        overseas.take(3) +
            domestic.take(3) +
            relay.take(3) +
            customOpenAI()
    ).map { preset ->
        preset.copy(specId = preset.id.removePrefix("preset_").ifBlank { null })
    }

    /**
     * 全部预置供应商(15 + 22 + 6 + 1 自定义模板 = 43 + 1 个)。
     *
     * v1.0.7: 每个 preset 自动填入 specId(从 id 去除 "preset_" 前缀),
     * 供 [io.zer0.ai.core.ProviderSpecMerger] 在运行时合并 spec 默认模型 + 用户 overlay。
     */
    val all: List<ProviderConfig> = (overseas + domestic + relay + customOpenAI()).map { preset ->
        preset.copy(specId = preset.id.removePrefix("preset_").ifBlank { null })
    }

    /** 按 id 查找预置供应商。 */
    fun byId(id: String): ProviderConfig? = all.firstOrNull { it.id == id }

    /**
     * v1.0.7: 按 specId 查找预置供应商规格(供 ProviderSpecMerger 合并默认模型)。
     * @param specId 内置规格标识(如 "openai"/"deepseek",不含 "preset_" 前缀)
     */
    fun bySpecId(specId: String): ProviderConfig? = all.firstOrNull { it.specId == specId }

    // ── 模型构造辅助 ──────────────────────────────────────────────

    /**
     * 构造 chat 模型(简化样板代码)。
     *
     * @param providerId 所属 Provider id(与 ProviderConfig.id 一致)
     * @param id 模型 id(发送到 API 的 model 字段)
     * @param name 模型显示名(默认同 id)
     * @param contextWindow 上下文窗口(token 数),null 表示未知
     * @param maxOutputTokens 最大输出 token,null 表示用上游默认
     * @param supportsVision 是否支持图像输入
     * @param reasoning 是否支持推理链(thinking / o 系列)
     * @param supportsTools 是否支持函数调用
     */
    @Suppress("ktlint:standard:function-naming")
    private fun chatModel(
        providerId: String,
        id: String,
        name: String = id,
        contextWindow: Int? = null,
        maxOutputTokens: Int? = null,
        supportsVision: Boolean = false,
        reasoning: Boolean = false,
        supportsTools: Boolean = false,
    ): Model = Model(
        id = id,
        name = name,
        providerId = providerId,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        supportsVision = supportsVision,
        abilities = buildSet {
            if (reasoning) add(ModelAbility.REASONING)
            if (supportsTools) add(ModelAbility.TOOL)
        },
    )

    // ── 海外官方 ──────────────────────────────────────────────────────

    private fun openai() = ProviderConfig(
        id = "preset_openai",
        displayName = "OpenAI",
        type = ProviderType.OPENAI,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_openai", "gpt-4o", "GPT-4o", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_openai", "gpt-4o-mini", "GPT-4o mini", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_openai", "o3-mini", "o3-mini", contextWindow = 200_000, reasoning = true, supportsTools = true),
            chatModel("preset_openai", "gpt-4.1", "GPT-4.1", contextWindow = 1_047_576, supportsTools = true),
            chatModel("preset_openai", "gpt-4.1-mini", "GPT-4.1 mini", contextWindow = 1_047_576, supportsTools = true),
        ),
    )

    private fun anthropic() = ProviderConfig(
        id = "preset_anthropic",
        displayName = "Anthropic Claude",
        type = ProviderType.ANTHROPIC,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_anthropic", "claude-sonnet-4-5-20250514", "Claude Sonnet 4.5", contextWindow = 200_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_anthropic", "claude-opus-4-1-20250805", "Claude Opus 4.1", contextWindow = 200_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_anthropic", "claude-haiku-4-5-20251001", "Claude Haiku 4.5", contextWindow = 200_000, supportsVision = true, supportsTools = true),
            chatModel("preset_anthropic", "claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", contextWindow = 200_000, supportsVision = true, supportsTools = true),
            chatModel("preset_anthropic", "claude-3-5-haiku-20241022", "Claude 3.5 Haiku", contextWindow = 200_000, supportsTools = true),
        ),
    )

    private fun gemini() = ProviderConfig(
        id = "preset_gemini",
        displayName = "Google Gemini",
        type = ProviderType.GEMINI,
        baseUrl = "",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_gemini", "gemini-2.5-flash", "Gemini 2.5 Flash", contextWindow = 1_048_576, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_gemini", "gemini-2.5-pro", "Gemini 2.5 Pro", contextWindow = 1_048_576, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_gemini", "gemini-2.0-flash", "Gemini 2.0 Flash", contextWindow = 1_048_576, supportsVision = true, supportsTools = true),
        ),
    )

    /**
     * P1-6: xAI 预设供应商(api-key 认证)。
     *
     * 走 OpenAI 兼容协议(type=OPENAI),baseUrl 用 xAI 官方端点。
     * 也支持 OAuth 登录(OAuthConfig.XAI_PRESET),用户可选择 api-key 或 OAuth。
     */
    private fun xai() = ProviderConfig(
        id = "preset_xai",
        displayName = "xAI Grok",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.x.ai/v1",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_xai", "grok-4", "Grok 4", contextWindow = 256_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_xai", "grok-4-heavy", "Grok 4 Heavy", contextWindow = 256_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_xai", "grok-3", "Grok 3", contextWindow = 131_072, supportsVision = true, supportsTools = true),
            chatModel("preset_xai", "grok-3-mini", "Grok 3 mini", contextWindow = 131_072, reasoning = true, supportsTools = true),
        ),
        oauthConfig = OAuthConfig.XAI_PRESET,
    )

    /**
     * v1.0.6: xAI OAuth 预设供应商(走 OpenAI Responses 协议)。
     *
     * 对齐 openhanako 的 xai-oauth:走 OAuth + openai-responses 协议,
     * baseUrl 指向 xAI OAuth 代理端点(https://cli-chat-proxy.grok.com)。
     *
     * 与 [xai] 区别:
     *  - type = OPENAI_RESPONSES(走 /v1/responses 而非 /v1/chat/completions)
     *  - OAuth 为唯一认证方式(不填 apiKey,通过 OAuth 拿 access_token)
     *  - 模型列表聚焦 Grok 4.5+(Responses API 支持的模型)
     */
    private fun xaiOauth() = ProviderConfig(
        id = "preset_xai_oauth",
        displayName = "xAI Grok (OAuth Responses)",
        type = ProviderType.OPENAI_RESPONSES,
        baseUrl = "https://cli-chat-proxy.grok.com",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_xai_oauth", "grok-4.5", "Grok 4.5", contextWindow = 500_000, maxOutputTokens = 128_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_xai_oauth", "grok-4.5-latest", "Grok 4.5 Latest", contextWindow = 500_000, maxOutputTokens = 128_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_xai_oauth", "grok-build-latest", "Grok Build Latest", contextWindow = 500_000, maxOutputTokens = 128_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_xai_oauth", "grok-4.3", "Grok 4.3", contextWindow = 1_000_000, maxOutputTokens = 128_000, supportsVision = true, reasoning = true, supportsTools = true),
        ),
        oauthConfig = OAuthConfig.XAI_OAUTH_PRESET,
        specific = ProviderSpecificConfig.OpenAI(useResponseApi = true),
        allowMissingApiKey = true, // OAuth 拿到 token 前允许空 apiKey
    )

    /**
     * v1.134 P2-1: OpenAI Codex 预设供应商(走 OAuth Device Flow)。
     *
     * 用户用 ChatGPT 账号扫码授权后,自动拿到 access_token 当作 apiKey 使用。
     * baseUrl 走 OpenAI 官方 /v1 端点,内部走 Responses API(由 useResponseApi 控制)。
     */
    private fun openaiCodex() = ProviderConfig(
        id = "preset_openai_codex",
        displayName = "OpenAI Codex (OAuth)",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_openai_codex", "gpt-4o", "GPT-4o", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_openai_codex", "gpt-4o-mini", "GPT-4o mini", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_openai_codex", "o3-mini", "o3-mini", contextWindow = 200_000, reasoning = true, supportsTools = true),
        ),
        oauthConfig = OAuthConfig.OPENAI_CODEX_PRESET,
        specific = ProviderSpecificConfig.OpenAI(useResponseApi = true),
    )

    /**
     * v1.134 P2-1: GitHub Copilot 预设供应商(走 GitHub OAuth Device Flow)。
     *
     * 1muse 特有(openhanako 无)。用户用 GitHub 账号扫码授权后,
     * access_token 当作 apiKey,Copilot 网关代理到 GPT-4o / Claude 等模型。
     */
    private fun githubCopilot() = ProviderConfig(
        id = "preset_github_copilot",
        displayName = "GitHub Copilot",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.githubcopilot.com",
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_github_copilot", "gpt-4o", "GPT-4o", contextWindow = 128_000, supportsVision = true, supportsTools = true),
            chatModel("preset_github_copilot", "claude-sonnet-4-5-20250514", "Claude Sonnet 4.5", contextWindow = 200_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_github_copilot", "gemini-2.5-flash", "Gemini 2.5 Flash", contextWindow = 1_048_576, supportsVision = true, reasoning = true, supportsTools = true),
        ),
        oauthConfig = OAuthConfig.GITHUB_COPILOT_PRESET,
    )

    private fun groq() = ProviderConfig(
        id = "preset_groq",
        displayName = "Groq",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_GROQ,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_groq", "llama-3.3-70b-versatile", "Llama 3.3 70B Versatile", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_groq", "llama-3.1-8b-instant", "Llama 3.1 8B Instant", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_groq", "mixtral-8x7b-32768", "Mixtral 8x7B", contextWindow = 32_768, supportsTools = true),
        ),
    )

    private fun together() = ProviderConfig(
        id = "preset_together",
        displayName = "Together AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_TOGETHER,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_together", "meta-llama/Llama-3.3-70B-Instruct-Turbo", "Llama 3.3 70B Turbo", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_together", "deepseek-ai/DeepSeek-R1", "DeepSeek R1", contextWindow = 131_072, reasoning = true),
        ),
    )

    private fun mistral() = ProviderConfig(
        id = "preset_mistral",
        displayName = "Mistral AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MISTRAL,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_mistral", "mistral-large-latest", "Mistral Large", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mistral", "mistral-small-latest", "Mistral Small", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mistral", "codestral-latest", "Codestral", contextWindow = 262_144, supportsTools = true),
        ),
    )

    private fun openrouter() = ProviderConfig(
        id = "preset_openrouter",
        displayName = "OpenRouter",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_OPENROUTER,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(), // 聚合平台,模型列表由用户从 /models 拉取
    )

    /** 1muse 特有(openhanako 无)。 */
    private fun deepInfra() = ProviderConfig(
        id = "preset_deepinfra",
        displayName = "DeepInfra",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPINFRA,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(),
    )

    private fun fireworks() = ProviderConfig(
        id = "preset_fireworks",
        displayName = "Fireworks AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_FIREWORKS,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_fireworks", "accounts/fireworks/models/llama-v3p3-70b-instruct", "Llama 3.3 70B", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_fireworks", "accounts/fireworks/models/deepseek-r1", "DeepSeek R1", contextWindow = 131_072, reasoning = true),
        ),
    )

    /** v1.0.6: Perplexity 预设(对齐 openhanako)。 */
    private fun perplexity() = ProviderConfig(
        id = "preset_perplexity",
        displayName = "Perplexity",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_PERPLEXITY,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_perplexity", "sonar-pro", "Sonar Pro", contextWindow = 200_000, supportsTools = true),
            chatModel("preset_perplexity", "sonar", "Sonar", contextWindow = 127_072, supportsTools = true),
            chatModel("preset_perplexity", "sonar-reasoning", "Sonar Reasoning", contextWindow = 127_072, reasoning = true, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: Ollama 本地预设(对齐 openhanako)。
     *
     * - authType=none → allowMissingApiKey=true(无需 apiKey)
     * - baseUrl=http://localhost:11434/v1(本地端点)
     * - models=空列表(由用户本地配置的模型决定,可从 /models 拉取)
     */
    private fun ollama() = ProviderConfig(
        id = "preset_ollama",
        displayName = "Ollama (Local)",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_OLLAMA,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = emptyList(), // 由用户本地 ollama pull 的模型决定
        allowMissingApiKey = true,
    )

    // ── 国产官方 ──────────────────────────────────────────────────────

    private fun deepseek() = ProviderConfig(
        id = "preset_deepseek",
        displayName = context.getString(R.string.preset_provider_deepseek),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPSEEK,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_deepseek", "deepseek-chat", "DeepSeek V3", contextWindow = 64_000, supportsTools = true),
            chatModel("preset_deepseek", "deepseek-reasoner", "DeepSeek R1", contextWindow = 64_000, reasoning = true),
        ),
    )

    private fun qwen() = ProviderConfig(
        id = "preset_qwen",
        displayName = context.getString(R.string.preset_provider_qwen),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_QWEN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_qwen", "qwen-max", "Qwen Max", contextWindow = 32_768, supportsTools = true),
            chatModel("preset_qwen", "qwen-plus", "Qwen Plus", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_qwen", "qwen-turbo", "Qwen Turbo", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_qwen", "qwen-long", "Qwen Long", contextWindow = 1_048_576, supportsTools = true),
            chatModel("preset_qwen", "qwen-vl-max", "Qwen VL Max", contextWindow = 32_768, supportsVision = true, supportsTools = true),
            chatModel("preset_qwen", "qwen-vl-plus", "Qwen VL Plus", contextWindow = 131_072, supportsVision = true, supportsTools = true),
            chatModel("preset_qwen", "qwen3-coder-plus", "Qwen3 Coder Plus", contextWindow = 1_048_576, supportsTools = true),
        ),
    )

    private fun zhipu() = ProviderConfig(
        id = "preset_zhipu",
        displayName = context.getString(R.string.preset_provider_zhipu),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_ZHIPU,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_zhipu", "glm-4-plus", "GLM-4 Plus", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_zhipu", "glm-4-flash", "GLM-4 Flash", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_zhipu", "glm-4-air", "GLM-4 Air", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_zhipu", "glm-4.5", "GLM-4.5", contextWindow = 128_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_zhipu", "glm-4.6", "GLM-4.6", contextWindow = 128_000, supportsVision = true, reasoning = true, supportsTools = true),
        ),
    )

    private fun moonshot() = ProviderConfig(
        id = "preset_moonshot",
        displayName = context.getString(R.string.preset_provider_moonshot),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MOONSHOT,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_moonshot", "moonshot-v1-128k", "Moonshot V1 128K", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_moonshot", "moonshot-v1-32k", "Moonshot V1 32K", contextWindow = 32_768, supportsTools = true),
            chatModel("preset_moonshot", "moonshot-v1-8k", "Moonshot V1 8K", contextWindow = 8_192, supportsTools = true),
            chatModel("preset_moonshot", "kimi-k2", "Kimi K2", contextWindow = 256_000, supportsTools = true),
        ),
    )

    private fun doubao() = ProviderConfig(
        id = "preset_doubao",
        displayName = context.getString(R.string.preset_provider_doubao),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DOUBAO,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        // 豆包的 model id 实为 endpoint id,用户需在火山引擎控制台创建接入点后填入
        models = emptyList(),
    )

    private fun baichuan() = ProviderConfig(
        id = "preset_baichuan",
        displayName = context.getString(R.string.preset_provider_baichuan),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_BAICHUAN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_baichuan", "Baichuan4-Turbo", "Baichuan4 Turbo", contextWindow = 128_000, supportsTools = true),
            chatModel("preset_baichuan", "Baichuan4-Air", "Baichuan4 Air", contextWindow = 128_000, supportsTools = true),
        ),
    )

    /** 1muse 特有(openhanako 无,零一万物已逐步停服,保留供存量用户)。 */
    private fun lingyi() = ProviderConfig(
        id = "preset_lingyi",
        displayName = context.getString(R.string.preset_provider_lingyi),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_LINGYI,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_lingyi", "yi-large", "Yi Large", contextWindow = 32_768, supportsTools = true),
            chatModel("preset_lingyi", "yi-medium", "Yi Medium", contextWindow = 16_384, supportsTools = true),
            chatModel("preset_lingyi", "yi-lightning", "Yi Lightning", contextWindow = 16_384, supportsTools = true),
        ),
    )

    private fun stepfun() = ProviderConfig(
        id = "preset_stepfun",
        displayName = context.getString(R.string.preset_provider_stepfun),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_STEPFUN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_stepfun", "step-2-16k", "Step 2 16K", contextWindow = 16_384, supportsTools = true),
            chatModel("preset_stepfun", "step-1-flash", "Step 1 Flash", contextWindow = 32_768, supportsTools = true),
        ),
    )

    /**
     * P2-5: SiliconFlow 免费模型兜底预设。
     *
     * v1.0.18: 对齐 Kelivo 免费模型机制 — 用户未填 apiKey 时通过
     * [io.zer0.ai.core.FreeModelConfig] 注入内置 fallback key,
     * 可直接调用 THUDM/GLM-4-9B-0414 与 Qwen/Qwen3-8B 两款免费模型。
     * 用户填入自己的 apiKey 后 fallback 失效,走用户 key 调远程 /models 解锁更多模型。
     *
     * - [allowMissingApiKey]=true: 允许不填 key(fallback 机制兜底)
     * - [models] 仅预填 2 个免费模型(对齐 Kelivo `_defaultModels` SiliconFlow 分支);
     *   用户填 key 后可点击「一键填入免费模型」按钮([SiliconFlowFreeModels.MODELS])
     *   或调远程 /models 拉取全部
     * - 1muse 特有:UI 提供「一键填入免费模型」按钮(仅当 baseUrl 命中 siliconflow.cn 时显示)
     */
    private fun siliconFlowFree() = ProviderConfig(
        id = SiliconFlowFreeModels.PROVIDER_ID,
        displayName = context.getString(R.string.preset_siliconflow_free),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_SILICONFLOW,
        apiKey = "",  // 空,触发 FreeModelConfig 免费模型 fallback 机制
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        allowMissingApiKey = true,  // v1.0.18: 允许不填 key,由 fallback key 兜底
        models = listOf(
            // v1.0.18: 与 FreeModelConfig.FREE_MODEL_IDS 保持一致(对齐 Kelivo 白名单)
            chatModel(
                SiliconFlowFreeModels.PROVIDER_ID,
                "THUDM/GLM-4-9B-0414",
                "GLM-4-9B 0414 (免费)",
                contextWindow = 32_768,
                supportsTools = true,
            ),
            chatModel(
                SiliconFlowFreeModels.PROVIDER_ID,
                "Qwen/Qwen3-8B",
                "Qwen3-8B (免费)",
                contextWindow = 32_768,
                supportsTools = true,
            ),
        ),
    )

    // ── 国产官方(新增,对齐 openhanako)──────────────────────────────

    /** v1.0.6: 腾讯混元(对齐 openhanako)。 */
    private fun hunyuan() = ProviderConfig(
        id = "preset_hunyuan",
        displayName = context.getString(R.string.preset_provider_hunyuan),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_HUNYUAN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_hunyuan", "hunyuan-turbos-latest", "Hunyuan Turbos", contextWindow = 256_000, supportsTools = true),
            chatModel("preset_hunyuan", "hunyuan-large-latest", "Hunyuan Large", contextWindow = 32_000, supportsTools = true),
        ),
    )

    /** v1.0.6: 百度智能云/文心(对齐 openhanako)。 */
    private fun baiduCloud() = ProviderConfig(
        id = "preset_baidu_cloud",
        displayName = context.getString(R.string.preset_provider_baidu_cloud),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_BAIDU,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_baidu_cloud", "ernie-4.5-turbo-vl-32k", "ERNIE 4.5 Turbo VL", contextWindow = 32_768, supportsVision = true, supportsTools = true),
            chatModel("preset_baidu_cloud", "ernie-4.0-turbo-128k", "ERNIE 4.0 Turbo 128K", contextWindow = 128_000, supportsTools = true),
        ),
    )

    /** v1.0.6: 魔搭 ModelScope(对齐 openhanako)。 */
    private fun modelscope() = ProviderConfig(
        id = "preset_modelscope",
        displayName = context.getString(R.string.preset_provider_modelscope),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MODELSCOPE,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_modelscope", "Qwen/Qwen3-235B-A22B", "Qwen3 235B A22B", contextWindow = 131_072, reasoning = true, supportsTools = true),
        ),
    )

    /** v1.0.6: 无问芯穹 Infini(对齐 openhanako)。 */
    private fun infini() = ProviderConfig(
        id = "preset_infini",
        displayName = context.getString(R.string.preset_provider_infini),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_INFINI,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_infini", "deepseek-r1", "DeepSeek R1", contextWindow = 64_000, reasoning = true),
            chatModel("preset_infini", "deepseek-v3-0324", "DeepSeek V3", contextWindow = 64_000, supportsTools = true),
        ),
    )

    /** v1.0.6: 小米 MiMo(对齐 openhanako)。 */
    private fun mimo() = ProviderConfig(
        id = "preset_mimo",
        displayName = context.getString(R.string.preset_provider_mimo),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MIMO,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_mimo", "mimo-v2.5-pro", "MiMo V2.5 Pro", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo", "mimo-v2.5", "MiMo V2.5", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo", "mimo-v2-pro", "MiMo V2 Pro", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo", "mimo-v2-flash", "MiMo V2 Flash", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo", "mimo-v2-omni", "MiMo V2 Omni", contextWindow = 131_072, supportsVision = true, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: 思必驰 Agnes AI(对齐 openhanako)。
     *
     * v1.0.8: 补全模型清单 — 2 个 chat 模型 + 1 个生图模型 + 1 个视频模型,
     * 覆盖 Agnes 各模态能力。Agnes 走 OpenAI 兼容协议,
     * GET /v1/models 可拉取上游完整模型列表(参见 [io.zer0.ai.openai.OpenAIProvider.listModels])。
     *
     * v1.0.8 (8.6): listModels 兼容性验证 — 无需改代码,仅注释。
     *  已验证 OpenAIProvider.listModels 对 Agnes 完全兼容:
     *   1. URL 拼接:resolvedBaseUrl + "/models" → https://apihub.agnes-ai.com/v1/models ✓
     *   2. 鉴权:Bearer token(与 Agnes 官方文档一致)✓
     *   3. 响应解析:OpenAIModelsResponse 标准 {"data":[...]} 格式 ✓
     *   4. 能力字段:7.3 多字段名解析覆盖 Agnes 返回的 capabilities/modalities ✓
     *   5. 错误分级:7.5 瀑布流日志在 401/403/404/5xx 各场景均记录分类标签 ✓
     *   6. 上下文窗口:context_length 字段自动注册到 ModelContextWindowRegistry ✓
     */
    private fun agnes() = ProviderConfig(
        id = "preset_agnes",
        displayName = "Agnes AI",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_AGNES,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            // chat 模型(支持视觉输入 + 工具调用)
            chatModel(
                "preset_agnes", "agnes-2.0-flash", "Agnes 2.0 Flash",
                contextWindow = 131_072, supportsTools = true, supportsVision = true,
            ),
            chatModel(
                "preset_agnes", "agnes-2.0-pro", "Agnes 2.0 Pro",
                contextWindow = 131_072, supportsTools = true, supportsVision = true,
            ),
            // 生图模型(输出 image 模态)
            Model(
                id = "agnes-image-2.1-flash",
                name = "Agnes Image 2.1 Flash",
                providerId = "preset_agnes",
                // 生图模型不适用文本上下文窗口
                contextWindow = 0,
                // 标记为图片生成模型(仅输出 image 模态,无文本输入输出)
                inputModalities = emptySet(),
                outputModalities = setOf("image"),
            ),
            // 视频模型(输出 video 模态)
            Model(
                id = "agnes-video-v2.0",
                name = "Agnes Video 2.0",
                providerId = "preset_agnes",
                supportsVideo = true,
                contextWindow = 0,
                inputModalities = emptySet(),
                outputModalities = setOf("video"),
            ),
        ),
    )

    /**
     * v1.0.6: MiniMax 预设(对齐 openhanako)。
     *
     * 对齐 openhanako:走 Anthropic Messages 协议(defaultApi=anthropic-messages),
     * baseUrl 指向 https://api.minimaxi.com/anthropic(MiniMax 官方 Anthropic 兼容端点)。
     */
    private fun minimax() = ProviderConfig(
        id = "preset_minimax",
        displayName = "MiniMax",
        type = ProviderType.ANTHROPIC,
        baseUrl = ENDPOINT_MINIMAX,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_minimax", "MiniMax-M3", "MiniMax M3", contextWindow = 1_000_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_minimax", "MiniMax-M2.7", "MiniMax M2.7", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax", "MiniMax-M2.5", "MiniMax M2.5", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax", "MiniMax-M2.1", "MiniMax M2.1", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax", "MiniMax-M2", "MiniMax M2", contextWindow = 1_000_000, supportsTools = true),
        ),
    )

    // ── Coding Plan 系列(对齐 openhanako *-coding)─────────────────

    /**
     * v1.0.6: 阿里云百炼 Coding Plan(对齐 openhanako)。
     *
     * 与 [qwen] 区别:走专属 coding 端点(coding.dashscope.aliyuncs.com),
     * 模型列表聚焦编程(qwen3-coder 系列),apiKey 通常带专属前缀。
     */
    private fun dashscopeCoding() = ProviderConfig(
        id = "preset_dashscope_coding",
        displayName = context.getString(R.string.preset_provider_dashscope_coding),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DASHSCOPE_CODING,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        specific = ProviderSpecificConfig.OpenAI(codingPlan = true),
        models = listOf(
            chatModel("preset_dashscope_coding", "qwen3-coder-plus", "Qwen3 Coder Plus", contextWindow = 1_048_576, supportsTools = true),
            chatModel("preset_dashscope_coding", "qwen3-coder-next", "Qwen3 Coder Next", contextWindow = 1_048_576, supportsTools = true),
            chatModel("preset_dashscope_coding", "qwen3-coder-flash", "Qwen3 Coder Flash", contextWindow = 1_048_576, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: Kimi Coding Plan(对齐 openhanako)。
     *
     * 走专属 coding 端点(api.kimi.com/coding/v1),模型为 kimi-for-coding,
     * 支持 thinking(kimi 格式),默认推理等级 high。
     */
    private fun kimiCoding() = ProviderConfig(
        id = "preset_kimi_coding",
        displayName = "Kimi Coding Plan",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_KIMI_CODING,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        specific = ProviderSpecificConfig.OpenAI(codingPlan = true),
        models = listOf(
            chatModel("preset_kimi_coding", "kimi-for-coding", "Kimi for Coding", contextWindow = 262_144, reasoning = true, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: 火山引擎豆包 Coding Plan(对齐 openhanako)。
     *
     * 走专属 coding 端点(ark.cn-beijing.volces.com/api/coding/v3),模型为 doubao-seed-code。
     */
    private fun volcengineCoding() = ProviderConfig(
        id = "preset_volcengine_coding",
        displayName = context.getString(R.string.preset_provider_volcengine_coding),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DOUBAO_CODING,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        specific = ProviderSpecificConfig.OpenAI(codingPlan = true),
        models = listOf(
            chatModel("preset_volcengine_coding", "doubao-seed-code", "Doubao Seed Code", contextWindow = 262_144, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: 智谱 GLM Coding Plan(对齐 openhanako)。
     *
     * 走专属 coding 端点(api.z.ai/api/coding/paas/v4),模型聚焦编程。
     */
    private fun zhipuCoding() = ProviderConfig(
        id = "preset_zhipu_coding",
        displayName = context.getString(R.string.preset_provider_zhipu_coding),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_ZHIPU_CODING,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        specific = ProviderSpecificConfig.OpenAI(codingPlan = true),
        models = listOf(
            chatModel("preset_zhipu_coding", "glm-5.2", "GLM-5.2", contextWindow = 131_072, reasoning = true, supportsTools = true),
            chatModel("preset_zhipu_coding", "glm-5-turbo", "GLM-5 Turbo", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_zhipu_coding", "glm-4.7", "GLM-4.7", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_zhipu_coding", "glm-4.5-air", "GLM-4.5 Air", contextWindow = 131_072, supportsTools = true),
        ),
    )

    // ── Token Plan 系列(对齐 openhanako *-token-plan)──────────────

    /**
     * v1.0.6: MiniMax Token Plan(对齐 openhanako)。
     *
     * 与 [minimax] 共用端点和协议(Anthropic Messages),但保持独立 provider id,
     * 用于 MiniMax 的 Token Plan 计费方式(预购 token 包)。
     */
    private fun minimaxTokenPlan() = ProviderConfig(
        id = "preset_minimax_token_plan",
        displayName = "MiniMax Token Plan",
        type = ProviderType.ANTHROPIC,
        baseUrl = ENDPOINT_MINIMAX,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_minimax_token_plan", "MiniMax-M3", "MiniMax M3", contextWindow = 1_000_000, supportsVision = true, reasoning = true, supportsTools = true),
            chatModel("preset_minimax_token_plan", "MiniMax-M2.7", "MiniMax M2.7", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax_token_plan", "MiniMax-M2.5", "MiniMax M2.5", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax_token_plan", "MiniMax-M2.1", "MiniMax M2.1", contextWindow = 1_000_000, supportsTools = true),
            chatModel("preset_minimax_token_plan", "MiniMax-M2", "MiniMax M2", contextWindow = 1_000_000, supportsTools = true),
        ),
    )

    /**
     * v1.0.6: 小米 MiMo Token Plan(对齐 openhanako)。
     *
     * 走专属 token-plan 端点(token-plan-cn.xiaomimimo.com),与 [mimo] 模型列表相同,
     * 但计费方式为预购 token 包。
     */
    private fun mimoTokenPlan() = ProviderConfig(
        id = "preset_mimo_token_plan",
        displayName = "Xiaomi MiMo Token Plan",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_MIMO_TOKEN_PLAN,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.OFFICIAL,
        models = listOf(
            chatModel("preset_mimo_token_plan", "mimo-v2.5-pro", "MiMo V2.5 Pro", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo_token_plan", "mimo-v2.5", "MiMo V2.5", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo_token_plan", "mimo-v2-pro", "MiMo V2 Pro", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo_token_plan", "mimo-v2-flash", "MiMo V2 Flash", contextWindow = 131_072, supportsTools = true),
            chatModel("preset_mimo_token_plan", "mimo-v2-omni", "MiMo V2 Omni", contextWindow = 131_072, supportsVision = true, supportsTools = true),
        ),
    )

    // ── 中转站(1muse 特有)──────────────────────────────────────────

    /**
     * OpenCode Go — OpenCode 官方提供的低成本模型订阅服务。
     *
     * 模型 ID 本地保存带 opencode-go/ 前缀,实际请求前会通过
     * ProviderSpecificConfig.OpenAI.stripModelPrefix 自动剥离。
     */
    private fun opencode() = ProviderConfig(
        id = "preset_opencode",
        displayName = "OpenCode Go",
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_OPENCODE,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.OpenAI(stripModelPrefix = "opencode-go/"),
        models = emptyList(),
    )

    /** API2D 中转站 — 国内老牌 OpenAI 中转。 */
    private fun api2d() = ProviderConfig(
        id = "preset_api2d",
        displayName = context.getString(R.string.preset_provider_api2d),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_API2D,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** AiHubMix 中转站 — 聚合 OpenAI/Claude/Gemini 等。 */
    private fun aihubmix() = ProviderConfig(
        id = "preset_aihubmix",
        displayName = context.getString(R.string.preset_provider_aihubmix),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_AIHUBMIX,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** DeepBricks 中转站 — OpenAI 兼容。 */
    private fun deepbricks() = ProviderConfig(
        id = "preset_deepbricks",
        displayName = context.getString(R.string.preset_provider_deepbricks),
        type = ProviderType.OPENAI,
        baseUrl = ENDPOINT_DEEPBRICKS,
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** OneAPI 自建中转模板 — 用户自部署,baseUrl 留空。 */
    private fun oneapiTemplate() = ProviderConfig(
        id = "preset_oneapi",
        displayName = context.getString(R.string.preset_provider_oneapi),
        type = ProviderType.OPENAI,
        baseUrl = "",  // 用户自部署,留空让用户填
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )

    /** NewAPI 自建中转模板 — OneAPI 的增强 fork。 */
    private fun newapiTemplate() = ProviderConfig(
        id = "preset_newapi",
        displayName = context.getString(R.string.preset_provider_newapi),
        type = ProviderType.OPENAI,
        baseUrl = "",  // 用户自部署,留空让用户填
        apiKey = "",
        builtIn = true,
        category = ProviderCategory.RELAY,
        specific = ProviderSpecificConfig.Custom(),
        models = emptyList(),
    )
}
