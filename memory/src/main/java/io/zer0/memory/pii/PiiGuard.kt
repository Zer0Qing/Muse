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
 *  - credit_card (16-19 位数字卡号,支持 4-4-4-4 分隔; A-20 对齐 app 版 BANK_CARD)
 *  - id_card (15/18 位中国身份证, R-TEST-09)
 *  - ssn (美国社会安全号)
 *  - email、phone(中国手机号,支持空格/连字符分隔, R-TEST-09)、ipv4
 *  - address(行政区划地址)、name(常见中文姓名)、english_name
 *
 * 姓名规则(A-18)改为「姓氏 + 称谓」与「称谓上下文 + 姓氏 + 名」两种高置信模式，
 * 不再用裸「姓氏字 + 1-2 汉字」匹配已知姓名，避免把 文化/文章/王国 等常见词误抹；
 * 另以 NAME_WHITELIST 白名单作第二道防线进一步降误伤。
 */
object PiiGuard {

    /** 常见姓氏字集合(A-18)：仅用于高置信姓名匹配，避免命中普通冠姓词。 */
    private const val SURNAME_CHARS =
        "王李张刘陈杨赵黄周吴徐孙胡朱高林何郭马罗梁宋郑谢韩唐冯于董萧程曹袁邓许傅沈曾彭吕苏卢蒋蔡贾丁魏薛叶阎余潘杜戴夏钟汪田任姜范方石姚谭廖邹熊金陆郝孔白崔康毛邱秦江史顾侯邵孟龙万段雷钱汤尹黎易常武乔贺赖龚文"

    /** 姓氏 + 称谓 白名单(A-18)：含称谓才判定为姓名，显著降低误伤。 */
    private const val HONORIFIC_TITLES =
        "老师傅|老师|医生|护士|同志|同学|先生|女士|师傅|经理|主任|教授|老板|厂长|院长|校长|律师|教练|董事长|总裁|导师|顾问|会计|叔叔|阿姨|爷爷|奶奶|哥哥|姐姐"

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
        // A-20 防误判: ID_CARD 必须先于 CREDIT_CARD 执行 —
        // 18 位身份证是 16-19 位连续数字的子集,卡号规则(16-19 位)会先吞掉
        // 身份证的前 18 位(结尾 X 使 (?!\d) 成立),导致身份证被标成 credit_card。
        // 先替换身份证,卡号规则不再命中已替换文本。
        ID_CARD(
            "id_card",
            Regex("""(?<!\d)(?:[1-9]\d{5}(?:19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[0-9Xx]|[1-9]\d{5}\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3})(?!\d)"""),
        ),
        CREDIT_CARD(
            "credit_card",
            // A-20: 与 app 版 BANK_CARD (\d{16,19}) 对齐,覆盖 16-19 位连续数字卡号
            // (支持 4-4-4-4 分隔),消除「对话层遮蔽、存储层裸存」的互补性漏检。
            Regex("""(?<!\d)(?:\d{4}[- ]?){3}\d{4,7}(?!\d)"""),
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
            // A-18: 高置信姓名匹配。两个分支:
            //  1) 姓氏 + 称谓(王老师/李医生/张同学) ——「姓氏字」+ 称谓白名单;
            //  2) 称谓上下文 + 姓氏 + 1-2 汉字(我叫张明/姓李/名叫...) —— 依赖上下文,
            //     不再用裸「姓氏字 + 汉字」匹配,避免 文化/文章/王国 等常见词被误抹。
            Regex(
                """(?:[$SURNAME_CHARS](?:$HONORIFIC_TITLES)|(?:叫|姓|名叫|叫作|称为|唤作|称作|称呼|姓名)\s*[$SURNAME_CHARS][\p{IsHan}]{1,2}(?![\p{IsHan}]))"""
            ),
            whitelistProtected = true,
        ),
        ENGLISH_NAME(
            "english_name",
            Regex("""\b[A-Z][a-z]{1,12}\s+[A-Z][a-z]{1,12}\b"""),
            whitelistProtected = true,
        ),
    }

    /** A-18: 常见非姓名词白名单,避免姓名规则误伤(命中则保留原文)。 */
    private val NAME_WHITELIST = setOf(
        // 原文已有词。
        "明白", "理解", "问题", "目的", "方法", "消息", "方向", "标题", "开始", "结束",
        "安排", "设计", "开发", "实现", "更新", "修复", "完成", "取消", "确认", "可能",
        "可以", "应该", "因为", "所以", "如果", "然后", "但是", "什么", "怎么", "这样",
        "那样", "这个", "那个", "我们", "你们", "他们", "自己", "朋友", "家人", "名字",
        "生日", "喜欢", "讨厌", "工作", "学习", "生活", "记忆", "系统", "项目", "团队",
        // A-18 新增:审计点名的高频误伤词(置于称谓上下文也可能出现,双保险)。
        "文化", "文章", "王国", "文明", "文学", "文字", "文采", "文法", "文献", "文稿",
        "文风", "文集", "文笔", "文言", "文雅", "方向感", "方法学", "方法论",
        "位置", "位址", "治疗", "治法", "治安", "治理", "治病", "程序", "过程", "程序化",
        "流程", "工程师", "工程", "路程", "旅行", "履行", "军事", "武器", "武装", "武术",
        "经济", "经营", "经理", "管理", "道理", "道德", "逻辑", "精神", "精华", "精致",
        "精彩", "精灵", "领袖", "领域", "领土", "灵活", "领导", "刘邦", "刘备",
        // A-18 补充:常见「姓氏字开头」的双字/三字常用词。
        "文件", "任务", "任务书", "任何", "何处", "人事", "人物", "人类", "人才",
        "金钱", "金融", "金库", "金属", "石头", "石碑", "石油", "化石", "长征", "长江",
        "长城", "长跑", "劳动", "劳模", "安全", "安心", "安定", "安静", "消除", "消防",
        "消费", "消化", "南京", "南宁", "南通", "南方", "北上", "北京", "北方", "陈列",
        "杨柳", "杨树", "柳树", "权威", "权力", "权益", "权利", "魏国", "高峰", "高中",
        "高见", "高兴", "高雅", "高级", "高铁", "高压", "高低", "郭靖", "郭敬明",
        "邓丽君", "程序员", "程度", "陈设", "吴语", "吴侬软语", "余裕", "富有", "富裕",
        "方式", "关系", "关注", "关于", "关切", "关头", "士兵", "失业", "示威", "示范",
        "开放", "开关", "开设", "开水", "唐诗", "唐突", "处理", "处置", "处事", "到处",
        "处方", "旋律", "旅游", "旅途", "日常", "日子", "日出", "日期", "日用", "月亮",
        "月光", "月经", "周末", "周到", "忙碌", "明亮", "明朗", "明显", "明月", "明星",
        "明智", "名人", "名胜", "名言", "名著", "名望", "命名", "名册", "名次", "合格",
        "合理", "合作", "合计", "合适", "记得", "记录", "记事", "纪念", "纪律", "纪念日",
        "世纪", "世面", "世代", "事业", "事件", "事迹", "事实", "事物", "事情", "事项",
        "事宜", "值得", "直接", "直到", "职责", "职业", "职位", "职务", "今天", "天气",
        "温暖", "温和", "温柔", "高山", "高兴", "沉默", "深沉", "深刻", "深奥", "深思",
        "清楚", "清晰", "经典", "经过", "经营", "结果", "结论", "结束", "结合", "结婚",
        "紧张", "紧急", "严格要求", "严肃", "严格", "严重", "严冬", "主要", "主动", "主题",
        "重要", "重复", "重生", "重庆",
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

    /**
     * S-05: 还原 [mask] 产生的占位符(LLM 输出侧)。无映射时原样返回。
     *
     * 审查修复 (2.0 B-08): LLM 改写/丢弃原文时占位符会残留(如 [PHONE_1]),
     * unmask 只做精确替换无法清除,残留的 [PHONE_1] 字面量会被写入记忆/消息;
     * 末尾统一扫描并剥离全部 mask 形态的残留占位符(空 map 同样执行 —
     * 无映射时任何 [LABEL_N] 形态都是残留或幻觉,不应落库)。
     */
    fun unmask(text: String, map: Map<String, String>): String {
        var current = text
        if (map.isNotEmpty()) {
            for ((token, original) in map) {
                current = current.replace(token, original)
            }
        }
        return RESIDUAL_TOKEN_RE.replace(current, "")
    }

    /** B-08: mask 生成的占位符形态([LABEL_N])— 还原后仍未消除的即残留,剥离之。 */
    private val RESIDUAL_TOKEN_RE = Regex(
        """\[(?:API_KEY|INLINE_SECRET|PRIVATE_KEY|ID_CARD|CREDIT_CARD|SSN|EMAIL|PHONE|""" +
            """IPV4|ADDRESS|NAME|ENGLISH_NAME)_\d+\]"""
    )
}
