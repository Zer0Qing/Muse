package io.zer0.muse.tools

/**
 * 工具执行成败判定器(P2-22)。
 *
 * 从 ChatTaskCardCoordinator 抽出,供任务卡、子代理、定时任务与测试**共用同一实现**:
 * 各执行路径对同一份工具返回字符串给出完全一致的成败结论,避免
 * 「子代理用一套中文子串、任务卡用另一套」造成判定漂移。
 *
 * 约定:工具失败时返回以错误描述开头(或 Error:/[超时] 等封装前缀)的字符串;
 * 结构化失败为 {"error": "..."} JSON(stdout 透传)。
 *
 * v2.6.2 修正(用户审计):原实现对"错误/失败/无法/为空/不可用"等中文裸词做无条件前缀匹配,
 * 会把**正常内容**误判为失败 —— read_file 读到的文档、web_fetch 抓到的文章、搜索结果片段
 * 只要以这些词开头(如《错误处理指南》《失败学》),就会被判 FAILED,任务卡显示失败并给
 * 模型注入"[工具调用失败引导] 请修正后重试",引发无谓重试。
 *
 * 现按可靠性分层:
 *  - **强失败信号**(封装标记 `[错误]/[超时]`、`error:`,以及多词合成的专用错误消息)无条件判失败;
 *  - **弱失败词**(错误/失败/无法/为空/未配置/不可用等单字口语词):仅当返回是**短标签**
 *    (长度 ≤ [WEAK_PREFIX_MAX_CHARS])或该词后紧跟分隔符(冒号/括号/逗号等)时才判失败;
 *    长文本(文件正文 / 网页正文)即使以这些词开头,也按正常内容处理。
 */
internal object ToolResultJudge {

    private val BRACKET_FAILURE_MARKERS = listOf(
        "[超时]",
        "[中断]",
        "[工具输出已截断",
        "[错误]",
        "[失败]",
        "[取消]",
    )
    private val STRUCTURED_ERROR_PATTERN = Regex("\"error\"\\s*:\\s*\"[^\"]")

    /**
     * 弱失败词:单独出现时歧义大(可能只是正常正文的开头),需配合"短文本"或"后跟分隔符"才判失败。
     */
    private val WEAK_FAILURE_PREFIXES = listOf(
        "错误", "失败", "异常", "超时", "取消", "未配置", "为空", "不可用", "无法", "不支持",
    )

    /**
     * 强失败前缀:多词合成的专用错误消息,语义明确,几乎不可能是正常正文的开头。
     */
    private val STRONG_FAILURE_PREFIXES = listOf(
        "工具不存在", "工具执行异常", "参数解析失败", "skill 执行异常",
        "路径越权", "缺少参数", "文件过大", "文件不存在", "未知 skill",
        "URL 必须", "未找到 id 为", "子助手",
    )

    /** 弱失败词之后紧跟这些分隔符,说明它是标签而非正文的一部分。 */
    private val WEAK_DELIMITERS = setOf(
        ':', '：', '(', '（', '[', '【', ',', '，', '、', '。', '!', '！', '?', '？',
    )

    /** 短标签阈值:不超过该长度的返回才允许用弱失败词前缀判定。 */
    private const val WEAK_PREFIX_MAX_CHARS = 120

    /** 判定工具执行结果是否为成功。 */
    fun isSuccess(result: String): Boolean {
        val text = result.trimStart()
        if (text.startsWith("error:", ignoreCase = true)) return false
        // Bracket markers are the production shapes for orchestrator-synthesised
        // terminal states; the previous bare-word list never matched them.
        if (BRACKET_FAILURE_MARKERS.any { text.startsWith(it) }) return false
        if (STRONG_FAILURE_PREFIXES.any { text.startsWith(it) }) return false
        if (startsWithWeakFailure(text)) return false
        // Structured failures: {"error": "..."} payloads from hooks and MCP bridges.
        if (text.startsWith("{") && STRUCTURED_ERROR_PATTERN.containsMatchIn(text.take(200))) {
            return false
        }
        return true
    }

    /**
     * 弱失败词判定:词在开头,且(整体是短标签 或 词后紧跟分隔符)。
     *
     * 长正文以弱失败词开头时返回 false,交回正常内容处理。
     */
    private fun startsWithWeakFailure(text: String): Boolean = WEAK_FAILURE_PREFIXES.any { prefix ->
        if (!text.startsWith(prefix)) return@any false
        text.length <= WEAK_PREFIX_MAX_CHARS ||
            text.getOrNull(prefix.length)?.let { it in WEAK_DELIMITERS } ?: true
    }
}
