package io.zer0.ai.openai

/**
 * 把用户填的 Base URL 归一成「可依次尝试的候选」。
 *
 * 为什么需要它：绝大多数「拉取模型正常、聊天报错」都不是账号问题，而是地址写法差异 ——
 * 有人填 `https://x.com`，有人填 `https://x.com/v1`，还有人直接把文档里的完整端点
 * `https://x.com/v1/chat/completions` 粘进来。让用户去读文档分辨这三种写法，是把
 * 实现细节推给了使用者；这里统一收敛成一份候选，由程序逐个探测。
 */
object OpenAiEndpointCandidates {

    /** 用户可能误粘贴的端点尾巴；剥掉后才是 Base URL。 */
    private val KNOWN_SUFFIXES = listOf("/chat/completions", "/responses", "/models", "/embeddings")

    /**
     * 生成候选 Base URL（去重、保序）。
     *
     * 只补一个 `/v1` 变体，不猜 `/v1/v1`：多猜一层会在少数网关上变成 404 的假阳性，
     * 而「原样」永远排第一，保证原本就正确的配置不会因为归一化被改坏。
     */
    fun baseUrls(raw: String): List<String> {
        var base = raw.trim()
        if (base.isEmpty()) return emptyList()
        KNOWN_SUFFIXES.forEach { suffix ->
            if (base.endsWith(suffix, ignoreCase = true)) {
                base = base.dropLast(suffix.length)
            }
        }
        base = base.trimEnd('/')
        if (base.isEmpty()) return emptyList()
        val candidates = linkedSetOf(base)
        if (!base.endsWith("/v1", ignoreCase = true)) {
            candidates.add(base + "/v1")
        }
        return candidates.toList()
    }

    /**
     * 这个失败是否像「端点不存在 / 被网关拦住」，从而值得换端点或换协议重试。
     *
     * 只认确定性信号：404/405/501、HTML 页面、okhttp-sse 的 Invalid content-type。
     * 401/402/429 这类是账号或限流问题，换端点只会白跑一趟。
     */
    fun looksLikeMissingEndpoint(status: Int?, contentType: String?, message: String?): Boolean {
        if (status == 404 || status == 405 || status == 501) return true
        if (contentType.orEmpty().contains("text/html", ignoreCase = true)) return true
        return message.orEmpty().contains("Invalid content-type", ignoreCase = true)
    }

    /**
     * 自动识别要尝试的「协议 × 地址」组合，按命中概率排序。
     *
     * 用户只填一个 Base URL 时，真正未知的是「这站实现的是哪套协议」；
     * 顺序按现实中出现的频率排：OpenAI 兼容 > Responses > Anthropic > Gemini。
     * 每种协议都配 `/v1` 与否两种地址，避免让用户理解「路径该不该带 v1」。
     */
    fun protocolProbes(raw: String): List<ProtocolProbe> {
        val bases = baseUrls(raw)
        if (bases.isEmpty()) return emptyList()
        val out = mutableListOf<ProtocolProbe>()
        bases.forEach { base ->
            out += ProtocolProbe(base, ProtocolKind.OPENAI_CHAT)
        }
        bases.forEach { base ->
            out += ProtocolProbe(base, ProtocolKind.OPENAI_RESPONSES)
        }
        bases.forEach { base ->
            out += ProtocolProbe(base, ProtocolKind.ANTHROPIC)
        }
        bases.forEach { base ->
            out += ProtocolProbe(base, ProtocolKind.GEMINI)
        }
        return out.distinct()
    }

    /** 一套协议。名字只用于日志与界面提示。 */
    enum class ProtocolKind(val label: String) {
        OPENAI_CHAT("Chat Completions"),
        OPENAI_RESPONSES("Responses API"),
        ANTHROPIC("Anthropic Messages"),
        GEMINI("Gemini generateContent"),
    }

    data class ProtocolProbe(val baseUrl: String, val protocol: ProtocolKind)
}
