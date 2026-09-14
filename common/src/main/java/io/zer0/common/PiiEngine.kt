package io.zer0.common

/** Pure Kotlin PII rules shared by app and memory facades. */
object PiiEngine {
    enum class AppType { PHONE, EMAIL, ID_CARD, BANK_CARD, IP_ADDRESS, ADDRESS }
    data class AppMatch(val type: AppType, val original: String, val masked: String, val start: Int, val end: Int)

    private fun appPrefix(type: AppType) = if (type == AppType.IP_ADDRESS) "IP" else type.name
    private val appPatterns = linkedMapOf(
        AppType.PHONE to Regex("(?<!\\d)1[3-9](?:[ -]?\\d){9}(?!\\d)"),
        AppType.EMAIL to Regex("(?i)(?<!\\w)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?!\\w)"),
        AppType.ID_CARD to Regex("(?<!\\d)(?:[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]|[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3})(?!\\d)"),
        AppType.BANK_CARD to Regex("(?<!\\d)\\d{16,19}(?!\\d)"),
        AppType.IP_ADDRESS to Regex("(?<!\\d)\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}(?!\\d)"),
        AppType.ADDRESS to Regex("(?:省|市|区|县|镇|乡|村|路|街|巷|弄|室|栋|单元)\\s*\\d{1,6}(?:[^-\\s]{0,20})"),
    )

    fun detectApp(text: String): List<AppMatch> {
        if (text.isEmpty()) return emptyList()
        val raw = appPatterns.flatMap { (type, regex) ->
            regex.findAll(text).map { AppMatch(type, it.value, "", it.range.first, it.range.last + 1) }
        }.sortedBy { it.start }
        val result = mutableListOf<AppMatch>()
        var lastEnd = -1
        for (match in raw) if (match.start >= lastEnd) { result += match; lastEnd = match.end }
        return result
    }

    fun maskApp(text: String): Pair<String, List<AppMatch>> {
        val matches = detectApp(text)
        if (matches.isEmpty()) return text to emptyList()
        val counters = mutableMapOf<AppType, Int>()
        val annotated = matches.map { match ->
            val number = (counters[match.type] ?: 0) + 1
            counters[match.type] = number
            match.copy(masked = "[${appPrefix(match.type)}_%03d]".format(number))
        }
        val result = StringBuilder(text.length + annotated.size * 12)
        var cursor = 0
        for (match in annotated) { result.append(text, cursor, match.start); result.append(match.masked); cursor = match.end }
        result.append(text, cursor, text.length)
        return result.toString() to annotated
    }

    fun unmaskApp(text: String, matches: List<AppMatch>): String {
        if (matches.isEmpty()) return text
        var result = text
        for (match in matches) if (match.masked.isNotEmpty()) result = result.replace(match.masked, match.original)
        return result
    }

    private const val SURNAMES = "王李张刘陈杨赵黄周吴徐孙胡朱高林何郭马罗梁宋郑谢韩唐冯于董萧程曹袁邓许傅沈曾彭吕苏卢蒋蔡贾丁魏薛叶阎余潘杜戴夏钟汪田任姜范方石姚谭廖邹熊金陆郝孔白崔康毛邱秦江史顾侯邵孟龙万段雷钱汤尹黎易常武乔贺赖龚文"
    private const val TITLES = "老师傅|老师|医生|护士|同志|同学|先生|女士|师傅|经理|主任|教授|老板|厂长|院长|校长|律师|教练|董事长|总裁|导师|顾问|会计|叔叔|阿姨|爷爷|奶奶|哥哥|姐姐"
    private val whitelist = setOf("明白","理解","问题","目的","方法","消息","方向","标题","开始","结束","安排","设计","开发","实现","更新","修复","完成","取消","确认","可能","可以","应该","因为","所以","如果","然后","但是","什么","怎么","这样","那样","这个","那个","我们","你们","他们","自己","朋友","家人","名字","生日","喜欢","讨厌","工作","学习","生活","记忆","系统","项目","团队","文化","文章","王国","文明","文学","文字","文采","文法","文献","文稿","文风","文集","文笔","文言","文雅","方向感","方法学","方法论","位置","位址","治疗","治法","治安","治理","治病","程序","过程","程序化","流程","工程师","工程","路程","旅行","履行","军事","武器","武装","武术","经济","经营","经理","管理","道理","道德","逻辑","精神","精华","精致","精彩","精灵","领袖","领域","领土","灵活","领导","刘邦","刘备","文件","任务","任务书","任何","何处","人事","人物","人类","人才","金钱","金融","金库","金属","石头","石碑","石油","化石","长征","长江","长城","长跑","劳动","劳模","安全","安心","安定","安静","消除","消防","消费","消化","南京","南宁","南通","南方","北上","北京","北方","陈列","杨柳","杨树","柳树","权威","权力","权益","权利","魏国","高峰","高中","高见","高兴","高雅","高级","高铁","高压","高低","郭靖","郭敬明","邓丽君","程序员","程度","陈设","吴语","吴侬软语","余裕","富有","富裕","方式","关系","关注","关于","关切","关头","士兵","失业","示威","示范","开放","开关","开设","开水","唐诗","唐突","处理","处置","处事","到处","处方","旋律","旅游","旅途","日常","日子","日出","日期","日用","月亮","月光","月经","周末","周到","忙碌","明亮","明朗","明显","明月","明星","明智","名人","名胜","名言","名著","名望","命名","名册","名次","合格","合理","合作","合计","合适","记得","记录","记事","纪念","纪律","纪念日","世纪","世面","世代","事业","事件","事迹","事实","事物","事情","事项","事宜","值得","直接","直到","职责","职业","职位","职务","今天","天气","温暖","温和","温柔","高山","高兴","沉默","深沉","深刻","深奥","深思","清楚","清晰","经典","经过","经营","结果","结论","结束","结合","结婚","紧张","紧急","严格要求","严肃","严格","严重","严冬","主要","主动","主题","重要","重复","重生","重庆")
    private data class Rule(val label: String, val regex: Regex, val protected: Boolean = false)
    private val rules by lazy { listOf(
        Rule("api_key", Regex("(?i)(?:sk-[a-z0-9_]{20,}|AKIA[0-9A-Z]{16}|gsk_[a-z0-9_]{20,}|ghp_[a-z0-9]{36}|glpat-[a-z0-9_-]{20,}|xoxb-[0-9a-z]+)")),
        Rule("inline_secret", Regex("(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?token|auth[_-]?token|password)\\s*[:=]\\s*[a-z0-9_/+=.\\-]{16,}")),
        Rule("private_key", Regex("-----BEGIN (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----.*?-----END (?:RSA |EC |DSA |OPENSSH )?PRIVATE KEY-----", setOf(RegexOption.DOT_MATCHES_ALL))),
        Rule("id_card", Regex("(?<!\\d)(?:[1-9]\\d{5}(?:19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]|[1-9]\\d{5}\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3})(?!\\d)")),
        Rule("credit_card", Regex("(?<!\\d)(?:\\d{4}[- ]?){3}\\d{4,7}(?!\\d)")),
        Rule("ssn", Regex("(?<!\\d)\\d{3}-\\d{2}-\\d{4}(?!\\d)")),
        Rule("email", Regex("(?i)(?<!\\w)[a-z0-9._%+-]+@[a-z0-9.-]+\\.[a-z]{2,}(?!\\w)")),
        Rule("phone", Regex("(?<!\\d)1[3-9](?:[ -]?\\d){9}(?!\\d)")),
        Rule("ipv4", Regex("(?<!\\d)(?:(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(?!\\d)")),
        Rule("address", Regex("[\\p{IsHan}]{2,12}(?:省|自治区|市|自治州|地区|盟)[\\p{IsHan}]{2,12}(?:区|县|旗|市|镇|乡|街道|路|街|巷|弄|村)(?:\\d{1,6}号?)?(?:[\\p{IsHan}\\d-]{0,20})?")),
        Rule("name", Regex("(?:[$SURNAMES](?:$TITLES)|(?:叫|姓|名叫|叫作|称为|唤作|称作|称呼|姓名)\\s*[$SURNAMES][\\p{IsHan}]{1,2}(?![\\p{IsHan}]))"), true),
        Rule("english_name", Regex("\\b[A-Z][a-z]{1,12}\\s+[A-Z][a-z]{1,12}\\b"), true),
    ) }
    data class ScrubResult(val cleaned: String, val detected: List<String>)
    data class MaskResult(val masked: String, val map: Map<String, String>)
    private val residual = Regex("\\[(?:API_KEY|INLINE_SECRET|PRIVATE_KEY|ID_CARD|CREDIT_CARD|SSN|EMAIL|PHONE|IPV4|ADDRESS|NAME|ENGLISH_NAME)_\\d+\\]")

    fun scrubMemory(text: String): ScrubResult {
        if (text.isEmpty()) return ScrubResult(text, emptyList())
        var current = text; val detected = mutableListOf<String>()
        for (rule in rules) {
            if (!rule.regex.containsMatchIn(current)) continue
            current = rule.regex.replace(current) { if (rule.protected && it.value in whitelist) it.value else "[REDACTED]" }
            if (rule.label !in detected) detected += rule.label
        }
        return ScrubResult(current, detected)
    }
    fun maskMemory(text: String): MaskResult {
        if (text.isEmpty()) return MaskResult(text, emptyMap())
        var current = text; val map = linkedMapOf<String, String>()
        for (rule in rules) current = rule.regex.replace(current) { if (rule.protected && it.value in whitelist) it.value else "[${rule.label.uppercase()}_${map.size + 1}]".also { token -> map[token] = it.value } }
        return MaskResult(current, map)
    }
    fun unmaskMemory(text: String, map: Map<String, String>): String {
        var current = text
        for ((token, original) in map) current = current.replace(token, original)
        return residual.replace(current, "")
    }
}
