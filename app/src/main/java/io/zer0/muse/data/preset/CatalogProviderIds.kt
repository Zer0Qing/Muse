package io.zer0.muse.data.preset

/**
 * 预设 specId 与云端模型目录 providerId 的命名归一。
 *
 * 背景（v2.6.0）：
 *  - 预置供应商的 specId 由 `id.removePrefix("preset_")` 推导，历史上用**下划线**命名
 *    （如 `openai_codex`、`baidu_cloud`、`dashscope_coding`）。
 *  - 云端模型目录 `model-catalog.json` 的 providers 段用**连字符**命名
 *    （如 `openai-codex-oauth`、`baidu-cloud`、`dashscope-coding`）。
 *
 * [ModelCatalogStore.mergeIntoModels]、[ModelCatalogStore.entries] 等按 providerId
 * 精确（忽略大小写）匹配目录条目，命名不一致时会**静默匹配失败** —— 表现为模型清单
 * 停留在预设硬编码的旧数据、云端目录的新模型（如 Codex 的 GPT-6 系列）不生效。
 *
 * 本对象只在**代码逻辑层**做 id 归一，不预置任何模型数据：模型清单始终来自云端目录。
 */
object CatalogProviderIds {

    /**
     * 显式别名：specId → 目录 providerId。
     *
     * 仅覆盖无法用「下划线→连字符」通用规则推导的少数情况；
     * 其余命名不一致（如 `baidu_cloud` → `baidu-cloud`）由 [toCatalogId] 通用规则处理。
     */
    private val aliases: Map<String, String> = mapOf(
        // Codex 订阅走官方 device-auth，目录键带后缀 -oauth。
        "openai_codex" to "openai-codex-oauth",
        // 目录里 OpenCode Go 网关用 opencode-go。
        "opencode" to "opencode-go",
        // xAI 的订阅/OAuth 变体共用同一份目录条目。
        "xai_oauth" to "xai",
    )

    /**
     * 把预设 specId 归一成云端目录的 providerId。
     *
     * 幂等：已经是对应目录键（或本就不在别名表、且无下划线）时原样返回，
     * 因此可安全地对任意来源的 providerId 调用。
     */
    fun toCatalogId(specId: String): String {
        val raw = specId.trim()
        if (raw.isEmpty()) return raw
        // 显式别名优先；否则按通用规则把下划线归一为连字符
        // （对已含连字符的键、或不在目录里的自定义 id 均无副作用）。
        return aliases[raw.lowercase()] ?: raw.replace('_', '-')
    }
}
