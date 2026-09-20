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
 */
internal object ToolResultJudge {

    private val BRACKET_FAILURE_MARKERS = listOf(
        "[超时]", "[中断]", "[工具输出已截断", "[错误]", "[失败]", "[取消]",
    )
    private val STRUCTURED_ERROR_PATTERN = Regex("\"error\"\\s*:\\s*\"[^\"]")

    /** 判定工具执行结果是否为成功。 */
    fun isSuccess(result: String): Boolean {
        val text = result.trimStart()
        if (text.startsWith("error:", ignoreCase = true)) return false
        // Bracket markers are the production shapes for orchestrator-synthesised
        // terminal states; the previous bare-word list never matched them.
        if (BRACKET_FAILURE_MARKERS.any { text.startsWith(it) }) return false
        val errorPrefixes = listOf(
            "工具不存在", "工具执行异常", "参数解析失败", "skill 执行异常",
            "路径越权", "缺少参数", "文件过大", "文件不存在", "未知 skill",
            "URL 必须", "无法", "未找到 id 为", "子助手",
            // v1.0.72: 补全失败判定 — 此前"图片生成失败""错误:..."等真实失败
            // 被误判为成功,模型以为工具成功继续回复(前台却无结果)
            "错误", "失败", "异常", "超时", "取消", "未配置", "为空", "不可用",
        )
        if (errorPrefixes.any { text.startsWith(it) }) return false
        // Structured failures: {"error": "..."} payloads from hooks and MCP bridges.
        if (text.startsWith("{") && STRUCTURED_ERROR_PATTERN.containsMatchIn(text.take(200))) {
            return false
        }
        return true
    }
}