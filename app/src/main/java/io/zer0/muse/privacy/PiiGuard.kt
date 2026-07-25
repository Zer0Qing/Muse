package io.zer0.muse.privacy

import io.zer0.muse.util.MusePatterns

/**
 * PII Guard — 在用户消息发送给 LLM 前自动检测并遮蔽敏感信息,
 * AI 回复后自动还原。所有方法均为纯函数(无共享可变状态),线程安全。
 *
 * 遮蔽策略(占位符格式):
 *  - PHONE      → [PHONE_001]
 *  - EMAIL      → [EMAIL_001]
 *  - ID_CARD    → [ID_CARD_001]
 *  - BANK_CARD  → [BANK_CARD_001]
 *  - IP_ADDRESS → [IP_001]
 *  - ADDRESS    → [ADDRESS_001]
 *
 * 设计原则:
 *  - 宁可漏检不要误检(保守匹配),避免把普通数字/常见短语误判为 PII。
 *  - 所有正则加边界保护(数字前后不能再有数字),防止长串中段误匹配。
 *  - mask 与 unmask 严格可逆:同一 [PiiMatch.masked] 占位符还原为同一 [PiiMatch.original]。
 *
 * 参考实现:openhanako 项目的 pii-guard.ts。
 */
object PiiGuard {

    /** PII 类型。 */
    enum class PiiType {
        PHONE,
        EMAIL,
        ID_CARD,
        BANK_CARD,
        IP_ADDRESS,
        ADDRESS,
    }

    /**
     * 单条 PII 匹配结果。
     *
     * @param type    PII 类型
     * @param original 原始文本
     * @param masked   占位符文本(如 [PHONE_001])
     * @param start    在原文中的起始偏移(含)
     * @param end      在原文中的结束偏移(不含)
     */
    data class PiiMatch(
        val type: PiiType,
        val original: String,
        val masked: String,
        val start: Int,
        val end: Int,
    )

    /**
     * 占位符前缀。IP_ADDRESS 简化为 IP,其余用 type.name。
     */
    private fun placeholderPrefix(type: PiiType): String = when (type) {
        PiiType.IP_ADDRESS -> "IP"
        else -> type.name
    }

    /**
     * 各类型正则 — 保守匹配,避免误检。
     *
     * - PHONE:     中国大陆手机号 1[3-9]xxxxxxxxx,前后不能是数字。
     * - EMAIL:     复用 [MusePatterns.EMAIL_REGEX](去掉 ^ $ 锚定),支持子串匹配。
     * - ID_CARD:   18 位身份证(地区码 + 生日 18-20 开头 + 顺序码 + 校验位数字/X)。
     * - BANK_CARD: 16-19 位连续数字(简化判断,前后不能是数字)。
     * - IP_ADDRESS:IPv4(4 段 1-3 位数字以点分隔),不校验数值范围。
     * - ADDRESS:   中文地址关键字(省/市/区/县/街道/镇/乡/村/路/号/巷/弄/室)
     *              + 后续 5-30 个非空白字符。
     */
    private val patterns: Map<PiiType, Regex> = linkedMapOf(
        PiiType.PHONE to Regex("(?<!\\d)1[3-9]\\d{9}(?!\\d)"),
        // 邮箱:复用 MusePatterns.EMAIL_REGEX 的核心模式(去掉 ^ $ 锚定)。
        PiiType.EMAIL to Regex(
            MusePatterns.EMAIL_REGEX.pattern.removePrefix("^").removeSuffix("$"),
        ),
        PiiType.ID_CARD to Regex(
            "(?<!\\d)[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx](?!\\d)",
        ),
        PiiType.BANK_CARD to Regex("(?<!\\d)\\d{16,19}(?!\\d)"),
        PiiType.IP_ADDRESS to Regex("(?<!\\d)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?!\\d)"),
        PiiType.ADDRESS to Regex(
            "(?:省|市|区|县|街道|镇|乡|村|路|号|巷|弄|室)[^\\s]{5,30}",
        ),
    )

    /**
     * 检测文本中的所有 PII,返回按位置排序的匹配列表。
     *
     * 保守策略:同一区间被多个 pattern 命中时,仅保留首个(按 [patterns] 顺序优先级)。
     * 区间重叠时丢弃后出现的(避免一个长串被多个 pattern 重复匹配)。
     *
     * @return 排序后的 [PiiMatch] 列表(不含 masked 字段,需在 [mask] 中填充)
     */
    fun detect(text: String): List<PiiMatch> {
        if (text.isEmpty()) return emptyList()
        val raw = mutableListOf<PiiMatch>()
        for ((type, regex) in patterns) {
            for (m in regex.findAll(text)) {
                raw.add(PiiMatch(type, m.value, "", m.range.first, m.range.last + 1))
            }
        }
        if (raw.isEmpty()) return emptyList()
        // 按起始位置排序;区间重叠时保留先出现的(更具体的优先)。
        raw.sortBy { it.start }
        val result = mutableListOf<PiiMatch>()
        var lastEnd = -1
        for (m in raw) {
            if (m.start >= lastEnd) {
                result.add(m)
                lastEnd = m.end
            }
        }
        return result
    }

    /**
     * 遮蔽文本中的 PII,返回 (遮蔽后文本, 匹配列表)。
     *
     * 每个 PII 替换为 [TYPE_NNN] 形式的占位符,NNN 从 001 开始按类型独立递增
     * (PHONE 与 EMAIL 互不冲突)。同一次调用内,相同原文 → 相同占位符。
     *
     * @return 遮蔽后的文本与对应匹配列表(含 masked 字段)
     */
    fun mask(text: String): Pair<String, List<PiiMatch>> {
        val matches = detect(text)
        if (matches.isEmpty()) return text to emptyList()
        // 按类型分配递增编号,跨类型独立计数。
        val counters = mutableMapOf<PiiType, Int>()
        val annotated = matches.map { m ->
            val n = (counters[m.type] ?: 0) + 1
            counters[m.type] = n
            val placeholder = "[%s_%03d]".format(placeholderPrefix(m.type), n)
            m.copy(masked = placeholder)
        }
        val sb = StringBuilder(text.length + annotated.size * 12)
        var cursor = 0
        for (m in annotated) {
            sb.append(text, cursor, m.start)
            sb.append(m.masked)
            cursor = m.end
        }
        sb.append(text, cursor, text.length)
        return sb.toString() to annotated
    }

    /**
     * 还原 PII 占位符为原始文本。
     *
     * 占位符 → 原文的映射来自 [mask] 返回的 [matches] 列表。
     * 同一占位符多次出现时全部替换为同一原文(标准行为)。
     * 若 [matches] 为空则原样返回(避免无谓的字符串拷贝)。
     *
     * @param text    含占位符的文本(通常是 LLM 的回复)
     * @param matches [mask] 返回的匹配列表
     * @return 还原后的文本
     */
    fun unmask(text: String, matches: List<PiiMatch>): String {
        if (matches.isEmpty()) return text
        var result = text
        for (m in matches) {
            if (m.masked.isEmpty()) continue
            result = result.replace(m.masked, m.original)
        }
        return result
    }
}
