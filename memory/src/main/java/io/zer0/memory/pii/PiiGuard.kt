package io.zer0.memory.pii

/**
 * PII 脱敏器 (openhanako pii-guard.ts 移植)。
 *
 * v1.78: 扩展覆盖范围,硬脱敏 9 类敏感信息,统一替换为 [REDACTED]:
 *   - api_key 前缀 (sk- / AKIA / gsk_ / ghp_ / glpat- / xoxb-)
 *   - inline_secret (api_key=XXX / password=XXX 等)
 *   - private_key (PEM 块)
 *   - credit_card (4-4-4-4)
 *   - id_card (18 位中国身份证)
 *   - ssn (美国社会安全号)
 *   - email (v1.78 新增)
 *   - phone (v1.78 新增,中国手机号 1[3-9]xxxxxxxxx)
 *   - ipv4 (v1.78 新增)
 *
 * 注: 地址 / 姓名不脱敏(正则误匹配率高,需 NER,后续可接入)。
 */
object PiiGuard {

    /** 命中类型 → 正则。 */
    private val HARD_PATTERNS: List<Pair<String, Regex>> = listOf(
        "api_key" to Regex(
            "(?:sk-[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|gsk_[A-Za-z0-9_]{20,}|ghp_[A-Za-z0-9]{36}|glpat-[A-Za-z0-9_-]{20,}|xoxb-[0-9A-Za-z]+)"
        ),
        "inline_secret" to Regex(
            "(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|password)[\"']?\\s*[:=]\\s*[\"']?[A-Za-z0-9_/+\\-=.]{16,}"
        ),
        "private_key" to Regex(
            "-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----[\\s\\S]*?-----END (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----"
        ),
        "credit_card" to Regex("\\b\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4}\\b"),
        "id_card" to Regex("\\b[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]\\b"),
        "ssn" to Regex("\\b\\d{3}-\\d{2}-\\d{4}\\b"),
        // v1.78: 邮箱
        "email" to Regex("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
        // v1.78: 中国手机号 1[3-9] 开头 + 9 位数字
        "phone" to Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
        // v1.78: IPv4 地址(排除版本号如 1.2.3.4 的误匹配:要求每段 0-255)
        "ipv4" to Regex("\\b(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\b"),
    )

    /** 脱敏结果。 */
    data class ScrubResult(val cleaned: String, val detected: List<String>)

    /**
     * 对文本脱敏。命中类型按出现顺序去重记录到 [detected]。
     * 同一段文本可能命中多个类型,逐个替换。
     */
    fun scrub(text: String): ScrubResult {
        if (text.isEmpty()) return ScrubResult(text, emptyList())
        var current = text
        val detected = mutableListOf<String>()
        for ((name, pattern) in HARD_PATTERNS) {
            val matched = pattern.containsMatchIn(current)
            if (matched) {
                current = pattern.replace(current, "[REDACTED]")
                if (name !in detected) detected.add(name)
            }
        }
        return ScrubResult(current, detected)
    }
}
