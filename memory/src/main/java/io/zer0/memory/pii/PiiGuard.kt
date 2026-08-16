package io.zer0.memory.pii

/**
 * PII 脱敏器。
 *
 * v1.78: 硬脱敏 9 类敏感信息 + 地址/姓名兜底规则，统一替换为 [REDACTED]。
 * 规则以数据驱动方式声明，每类规则包含正则与是否需要白名单过滤。
 *
 * 覆盖范围：
 *  - api_key 前缀 (sk- / AKIA / gsk_ / ghp_ / glpat- / xoxb-)
 *  - inline_secret (api_key=XXX / password=XXX 等)
 *  - private_key (PEM 块)
 *  - credit_card (4-4-4-4)
 *  - id_card (15/18 位中国身份证, R-TEST-09)
 *  - ssn (美国社会安全号)
 *  - email、phone(中国手机号,支持空格/连字符分隔, R-TEST-09)、ipv4
 *  - address(行政区划地址)、name(常见中文姓名)、english_name
 *
 * 姓名规则使用白名单过滤常见非姓名词，降低误伤。
 */
object PiiGuard {

    /** 命中类型枚举：每类携带检测正则与脱敏方式。 */
    private enum class RuleKind(
        val label: String,
        val pattern: Regex,
        val whitelistProtected: Boolean = false,
    ) {
        API_KEY(
            "api_key",
            Regex(
                """(?i)(?:sk-[a-z0-9_]{20,}|AKIA[0-9A-Z]{16}|gsk_[a-z0-9_]{20,}|ghp_[a-z0-9]{36}|glpat-[a-z0-9_-]{20,}|xoxb-[0-9a-z]+)"""
            ),
        ),
        INLINE_SECRET(
            "inline_secret",
            Regex(
                """(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|password)\s*[:=]\s*[a-z0-9_/+=.\-]{16,}"""
            ),
        ),
        PRIVATE_KEY(
            "private_key",
            Regex(
                """-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----""",
                setOf(RegexOption.DOT_MATCHES_ALL),
            ),
        ),
        CREDIT_CARD(
            "credit_card",
            Regex("""(?<!\d)(?:\d{4}[- ]?){3}\d{4}(?!\d)"""),
        ),
        ID_CARD(
            "id_card",
            Regex("""(?<!\d)(?:[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx]|[1-9]\d{5}\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3})(?!\d)"""),
        ),
        SSN(
            "ssn",
            Regex("""(?<!\d)\d{3}-\d{2}-\d{4}(?!\d)"""),
        ),
        EMAIL(
            "email",
            Regex("""(?i)(?<!\w)[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}(?!\w)"""),
        ),
        PHONE(
            "phone",
            Regex("""(?<!\d)1[3-9](?:[ -]?\d){9}(?!\d)"""),
        ),
        IPV4(
            "ipv4",
            Regex(
                """(?<!\d)(?:(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)\.){3}(?:25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(?!\d)"""
            ),
        ),
        ADDRESS(
            "address",
            Regex(
                """[\p{IsHan}]{2,12}(?:省|自治区|市|自治州|地区|盟)[\p{IsHan}]{2,12}(?:区|县|旗|市|镇|乡|街道|路|街|巷|弄|村)(?:\d{1,6}号?)?(?:[\p{IsHan}\d-]{0,20})?"""
            ),
        ),
        NAME(
            "name",
            Regex(
                """[王李张刘陈杨赵黄周吴徐孙胡朱高林何郭马罗梁宋郑谢韩唐冯于董萧程曹袁邓许傅沈曾彭吕苏卢蒋蔡贾丁魏薛叶阎余潘杜戴夏钟汪田任姜范方石姚谭廖邹熊金陆郝孔白崔康毛邱秦江史顾侯邵孟龙万段雷钱汤尹黎易常武乔贺赖龚文][\p{IsHan}]{1,2}(?![\p{IsHan}])"""
            ),
            whitelistProtected = true,
        ),
        ENGLISH_NAME(
            "english_name",
            Regex("""\b[A-Z][a-z]{1,12}\s+[A-Z][a-z]{1,12}\b"""),
            whitelistProtected = true,
        ),
    }

    /** B4-06: 常见非姓名词,避免中文姓名规则误伤。 */
    private val NAME_WHITELIST = setOf(
        "明白", "理解", "问题", "目的", "方法", "消息", "方向", "标题", "开始", "结束",
        "安排", "设计", "开发", "实现", "更新", "修复", "完成", "取消", "确认", "可能",
        "可以", "应该", "因为", "所以", "如果", "然后", "但是", "什么", "怎么", "这样",
        "那样", "这个", "那个", "我们", "你们", "他们", "自己", "朋友", "家人", "名字",
        "生日", "喜欢", "讨厌", "工作", "学习", "生活", "记忆", "系统", "项目", "团队",
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
        for (rule in RuleKind.entries) {
            val matched = rule.pattern.containsMatchIn(current)
            if (!matched) continue
            current = if (rule.whitelistProtected) {
                rule.pattern.replace(current) { m ->
                    if (m.value in NAME_WHITELIST) m.value else "[REDACTED]"
                }
            } else {
                rule.pattern.replace(current, "[REDACTED]")
            }
            if (rule.label !in detected) detected.add(rule.label)
        }
        return ScrubResult(current, detected)
    }

    /** S-05: 输入侧可逆掩码结果 — [masked] 为占位符文本,[map] 为 占位符 → 原文 映射。 */
    data class MaskResult(val masked: String, val map: Map<String, String>)

    /**
     * S-05: 输入侧可逆掩码 — 外发 LLM 前把 PII 替换为占位符(如 [PHONE_1]),
     * 原文不离开设备;LLM 输出经 [unmask] 还原后再走 [scrub] 落库。
     *
     * 与 [scrub] 的区别: scrub 是输出侧硬脱敏(落库/展示),mask 是输入侧临时掩码
     * (保留可还原性,避免记忆管道把明文 PII 发给 Provider)。
     */
    fun mask(text: String): MaskResult {
        if (text.isEmpty()) return MaskResult(text, emptyMap())
        var current = text
        val map = linkedMapOf<String, String>()
        for (rule in RuleKind.entries) {
            current = rule.pattern.replace(current) { m ->
                if (rule.whitelistProtected && m.value in NAME_WHITELIST) {
                    m.value
                } else {
                    val token = "[${rule.label.uppercase()}_${map.size + 1}]"
                    map[token] = m.value
                    token
                }
            }
        }
        return MaskResult(current, map)
    }

    /** S-05: 还原 [mask] 产生的占位符(LLM 输出侧)。无映射时原样返回。 */
    fun unmask(text: String, map: Map<String, String>): String {
        if (map.isEmpty()) return text
        var current = text
        for ((token, original) in map) {
            current = current.replace(token, original)
        }
        return current
    }
}
