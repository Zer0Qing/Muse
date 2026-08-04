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
    /** B4-06: 常见非姓名词,避免中文姓名规则误伤。 */
    private val NAME_WHITELIST = setOf(
        "明白", "理解", "问题", "目的", "方法", "消息", "方向", "标题", "开始", "结束",
        "安排", "设计", "开发", "实现", "更新", "修复", "完成", "取消", "确认", "可能",
        "可以", "应该", "因为", "所以", "如果", "然后", "但是", "什么", "怎么", "这样",
        "那样", "这个", "那个", "我们", "你们", "他们", "自己", "朋友", "家人", "名字",
        "生日", "喜欢", "讨厌", "工作", "学习", "生活", "记忆", "系统", "项目", "团队",
    )
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
        // B4-06: 行政区划地址(省/市/区/县/镇/乡/街道/路/村)
        "address" to Regex(
            "[\\u4e00-\\u9fff]{2,12}(?:省|自治区|市|自治州|地区|盟)[\\u4e00-\\u9fff]{2,12}(?:区|县|旗|市|镇|乡|街道|路|街|巷|弄|村)(?:\\d{1,6}号?)?(?:[\\u4e00-\\u9fff\\d-]{0,20})?"
        ),
        // B4-06: 规则兜底姓名(常见中文姓氏 + 2-3 字,白名单过滤误伤)
        "name" to Regex(
            "(?:王|李|张|刘|陈|杨|赵|黄|周|吴|徐|孙|胡|朱|高|林|何|郭|马|罗|梁|宋|郑|谢|韩|唐|冯|于|董|萧|程|曹|袁|邓|许|傅|沈|曾|彭|吕|苏|卢|蒋|蔡|贾|丁|魏|薛|叶|阎|余|潘|杜|戴|夏|钟|汪|田|任|姜|范|方|石|姚|谭|廖|邹|熊|金|陆|郝|孔|白|崔|康|毛|邱|秦|江|史|顾|侯|邵|孟|龙|万|段|雷|钱|汤|尹|黎|易|常|武|乔|贺|赖|龚|文)[\\u4e00-\\u9fff]{1,2}(?![\\u4e00-\\u9fff])"
        ),
        // B4-06: 英文 First Last 姓名
        "english_name" to Regex("\\b[A-Z][a-z]{1,12}\\s+[A-Z][a-z]{1,12}\\b"),
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
                current = if (name == "name" || name == "english_name") {
                    pattern.replace(current) { m ->
                        if (m.value in NAME_WHITELIST) m.value else "[REDACTED]"
                    }
                } else {
                    pattern.replace(current, "[REDACTED]")
                }
                if (name !in detected) detected.add(name)
            }
        }
        return ScrubResult(current, detected)
    }
}
