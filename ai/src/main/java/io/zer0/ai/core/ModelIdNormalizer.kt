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
