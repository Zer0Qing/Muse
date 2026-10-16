package io.zer0.ai.core

/**
 * v2.6.2: 中转站强制请求头规则（声明式）。
 *
 * 背景：部分中转/聚合站对每个请求强制要求会话/客户端标识头，缺失即拒绝：
 *  - OpenCode Go（opencode.ai）：`x-opencode-session`，缺失返回 HTTP 400
 *    `MissingSessionID`。该值用于路由亲和与 prompt 缓存 —— 服务端不解析其内容
 *    （任意字符串均可），但要求**同一会话稳定**，否则缓存命中率归零。
 *
 * 设计：按 host 声明规则，请求层按当前会话 id 派生值自动注入。新增中转站只需在
 * [RULES] 追加一行，无需改动 Provider / UI / 调用方。
 *
 * 与用户手填的关系：用户在供应商页「自定义请求头」里显式填了同名头时优先
 * （见 [mergeWithUserHeaders]），自动注入只做兜底，不覆盖用户意图。
 */
object RelayRequiredHeaders {

    /** 单条规则：命中哪些 host、需要哪些头。 */
    private data class Rule(
        val match: (host: String) -> Boolean,
        val headerNames: List<String>,
    )

    /**
     * 已知需要自动会话头的中转站。
     *
     * 判定按 baseUrl 的 host（已小写、去端口）。用后缀匹配兼容自定义子域。
     */
    private val RULES: List<Rule> = listOf(
        // OpenCode Go — x-opencode-session（路由亲和 + prompt 缓存）
        Rule(
            match = { host -> host == "opencode.ai" || host.endsWith(".opencode.ai") },
            headerNames = listOf("x-opencode-session"),
        ),
    )

    /**
     * 该 baseUrl 需要自动注入的头名列表；无规则时为空。
     */
    fun requiredHeaderNames(baseUrl: String): List<String> {
        val host = extractHost(baseUrl)
        if (host.isEmpty()) return emptyList()
        return RULES.firstOrNull { it.match(host) }?.headerNames.orEmpty()
    }

    /**
     * 生成自动头（名 → 值）。
     *
     * 值由 [seed] 派生：优先用会话 id（每会话稳定 → 缓存亲和正确）；
     * 无会话上下文（后台任务）时退化为按 providerId 的稳定值 —— 保证头存在、
     * 不触发 400，缓存亲和降为 provider 粒度。
     *
     * @param baseUrl 供应商 baseUrl（用于 host 匹配）
     * @param sessionId 当前会话 id，可空
     * @param providerId 供应商 id（无 sessionId 时的退化种子）
     */
    fun autoHeaders(baseUrl: String, sessionId: String?, providerId: String): Map<String, String> {
        val names = requiredHeaderNames(baseUrl)
        if (names.isEmpty()) return emptyMap()
        val seed = sessionId?.takeIf { it.isNotBlank() } ?: "provider:$providerId"
        val value = stableId(seed)
        return names.associateWith { value }
    }

    /**
     * 自动头与用户显式头合并 —— 用户显式值优先（大小写不敏感判定同名）。
     */
    fun mergeWithUserHeaders(auto: Map<String, String>, user: Map<String, String>): Map<String, String> {
        if (auto.isEmpty()) return user
        val userLower = user.keys.map { it.lowercase() }.toSet()
        val merged = LinkedHashMap<String, String>()
        auto.forEach { (k, v) -> if (k.lowercase() !in userLower) merged[k] = v }
        merged.putAll(user)
        return merged
    }

    /**
     * 由种子派生稳定的不透明 id（32 位十六进制）。服务端不解析内容，仅要求稳定。
     */
    private fun stableId(seed: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(seed.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(32)

    /** 从 baseUrl 提取 host（小写、去端口）。与 [ProviderCompat] 的 extractHost 同口径。 */
    private fun extractHost(baseUrl: String): String {
        val trimmed = baseUrl.trim().ifBlank { return "" }
        return runCatching {
            trimmed
                .removePrefix("https://")
                .removePrefix("http://")
                .substringBefore('/')
                .substringBefore(':')
                .lowercase()
        }.getOrDefault("")
    }
}
