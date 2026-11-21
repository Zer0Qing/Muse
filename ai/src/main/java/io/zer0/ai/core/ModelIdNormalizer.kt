package io.zer0.ai.core

/**
 * v2.6.1: 剥离 one-api/new-api 系中转站在模型列表里附加的前导渠道分组标签。
 *
 * 部分中转站在 `/v1/models` 里用 `[分组]模型名` 展示渠道分组(如 `[ais]gemini-3.8-flash`),
 * 但该方括号段**不是可路由的模型名**,原样发出会得到
 * `No available channel for model [ais]xxx ... code=model_not_found`(真实用户实测)。
 *
 * 合法模型名不含方括号,因此只要存在前导 `[...]` 就剥离;支持多级(如 `[A][vip]claude-3`)。
 * - 模型名中间的方括号(如 `model[1]`)不属于前导标签,保持原样。
 * - 纯标签、剥后为空(无真实模型名)时回退原值,避免把请求发成空 model。
 */
internal fun stripChannelGroupLabels(modelId: String): String {
    val trimmed = modelId.trim()
    if (!trimmed.startsWith("[")) return trimmed
    val stripped = Regex("^(\\[[^\\]\\[]{0,64}\\])+").replace(trimmed, "")
    return stripped.takeIf { it.isNotBlank() } ?: trimmed
}

/**
 * 已知的中转/聚合平台模型名前缀。
 *
 * 显式列出而非只靠 `/` 兜底的原因:`accounts/fireworks/models/xxx` 这类多段前缀,
 * 若直接取最后一个 `/` 之后会丢掉 `models` 段信息,导致某些模型名被错误截断。
 *
 * 白名单只是「优先精确剥」,不在名单里的前缀仍会走 [normalizeModelIdForLookup] 的 `/` 兜底。
 */
internal val KNOWN_MODEL_PREFIXES: List<String> = listOf(
    // 海外聚合站
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
    // 国内中转站 / 聚合站
    "siliconflow/",
    "dashscope/",
    "baichuan/",
    "lingyi/",
    "lingyiwanwu/",
    "stepfun/",
    "zhipu/",
    "bigmodel/",
    "minimax/",
    "moonshot/",
    "doubao/",
    "volcengine/",
    "ark/",
    "hunyuan/",
    "qwen/",
    "aliyun/",
    "deepseek-ai/",
)

/**
 * 模型 ID 归一化:查表/能力识别路径的统一入口。
 *
 * 处理顺序:
 *  1. 去空白 + 转小写
 *  2. 剥前导 `[分组]` 标签(可多级)
 *  3. 命中 [KNOWN_MODEL_PREFIXES] 则剥掉该前缀
 *  4. 白名单外的前缀:取最后一个 `/` 之后的部分
 *
 * 为什么需要统一入口:此前项目里有四份独立实现,白名单与兜底规则各不相同,
 * 其中两份没有 `/` 兜底,导致用户自建中转站的自定义前缀(如 `myrelay/gpt-4o`)
 * 在 [KnownModels] 与 [ModelContextWindowRegistry] 里查不到上下文窗口与输出上限。
 *
 * **仅用于查表与能力识别**,不改变实际发给上游的 model 字段 —— 请求侧走
 * `OpenAIProvider.effectiveModelId`,那里刻意原样透传(中转站要求保留前缀才能路由)。
 */
internal fun normalizeModelIdForLookup(modelId: String): String {
    val raw = modelId.trim().lowercase()
    if (raw.isEmpty()) return raw
    val deLabeled = stripChannelGroupLabels(raw)
    // 第一步:命中白名单则精确剥(保护 accounts/fireworks/models/xxx 这类多段前缀)
    val afterWhitelist = KNOWN_MODEL_PREFIXES
        .firstOrNull { deLabeled.startsWith(it) }
        ?.let(deLabeled::removePrefix)
        ?: deLabeled
    // 第二步:仍残留斜杠则取最后一段。
    // 覆盖两种场景:白名单外的前缀(myrelay/gpt-4o),
    // 以及白名单剥完仍带厂商段(openrouter/deepseek/deepseek-v4 → deepseek-v4)。
    // 白名单已剥净时(如 accounts/fireworks/models/llama-3)无斜杠,本步为空操作。
    return afterWhitelist.substringAfterLast('/').takeIf { it.isNotBlank() } ?: afterWhitelist
}
