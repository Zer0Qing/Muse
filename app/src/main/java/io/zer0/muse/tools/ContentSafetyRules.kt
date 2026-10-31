package io.zer0.muse.tools

/**
 * v1.0.53: 工具输入内容安全规则。
 *
 * 每条规则: toolName + 内容匹配 + 动作(拦截)+ reason + ruleId。
 * 在工具执行前统一检查;命中返回拒绝结果(带 ruleId,日志可追溯)。
 *
 * 注意: [ParamPolicies] 负责审批层拦截(用户可见),本规则是执行前硬边界
 * (无论审批如何都拦截),两条防线互补。
 */
data class ContentSafetyRule(
    val toolName: String,
    val matcher: (String) -> Boolean,
    val reason: String,
    val ruleId: String,
    val risk: String = "high",
    /**
     * 只拿该参数的值去做匹配；null 表示拿全部参数值拼接后的串（默认行为）。
     *
     * 路径类规则必须指定这个字段。否则正文内容会跟路径拼在一起过 `contains("..")`，
     * 写一段含省略号的文本就会被当成路径穿越拦下。
     */
    val targetArg: String? = null,
)

object ContentSafetyRules {

    val RULES: List<ContentSafetyRule> = listOf(
        // execute_javascript 危险模式
        ContentSafetyRule(
            toolName = "execute_javascript",
            matcher = { it.contains("rm -rf") || it.contains("ProcessBuilder") || it.contains("Runtime.getRuntime().exec") },
            reason = "代码执行类危险操作被安全策略拦截",
            ruleId = "js-dangerous-process",
        ),
        ContentSafetyRule(
            toolName = "execute_javascript",
            matcher = { input ->
                // H-SEC-3: 增强 token/密钥外泄检测
                // 1) 原有危险进程执行检测
                val hasDangerousProcess = input.contains("rm -rf") ||
                    input.contains("ProcessBuilder") ||
                    input.contains("Runtime.getRuntime().exec")
                // 2) cookie 外泄
                val hasCookieExfil = input.contains("document.cookie")
                // 3) fetch/XMLHttpRequest/WebSocket/sendBeacon 外发到 http/https URL(覆盖单双引号)
                val hasFetchExfil = Regex("""fetch\s*\(\s*['"`]https?://""").containsMatchIn(input) ||
                    Regex("""XMLHttpRequest\s*\(\s*['"`]https?://""").containsMatchIn(input)
                val hasWsExfil = Regex("""new\s+WebSocket\s*\(\s*['"`]wss?://""").containsMatchIn(input)
                val hasBeacon = input.contains("sendBeacon") &&
                    Regex("""https?://""").containsMatchIn(input)
                // 4) 通用 URL 外发含敏感词
                val hasUrlWithSensitive = Regex("""https?://[^\s`'")\]]+""").findAll(input)
                    .any {
                        it.value.contains("token") || it.value.contains("secret") ||
                            it.value.contains("key=") || it.value.contains("auth")
                    }
                hasDangerousProcess || hasCookieExfil || hasFetchExfil ||
                    hasWsExfil || hasBeacon || hasUrlWithSensitive
            },
            reason = "疑似外传敏感信息被安全策略拦截",
            ruleId = "js-exfil-token",
        ),
        // open_url 本地文件
        ContentSafetyRule(
            toolName = "open_url",
            matcher = { it.startsWith("file://") },
            reason = "不允许打开本地文件链接",
            ruleId = "url-local-file",
        ),
        // 写文件类工具的路径硬边界在下方 writePathRules 里按工具名展开:只看 path,不碰正文
    ) + writePathRules("workspace_write") + writePathRules("workspace_edit")

    /**
     * 写文件类工具共用的路径硬边界。
     *
     * 两条规则都带 [ContentSafetyRule.targetArg]：只拿 path 匹配，正文内容不参与，
     * 否则写一段带省略号的正文就会被当成路径穿越拦下。
     *
     * @param toolName 写文件类工具名（workspace_write / workspace_edit）
     */
    private fun writePathRules(toolName: String): List<ContentSafetyRule> =
        listOf(
            ContentSafetyRule(
                toolName = toolName,
                matcher = { it.contains("..") },
                reason = "路径包含 .. 被安全策略拦截",
                ruleId = "workspace-traversal",
                targetArg = "path",
            ),
            ContentSafetyRule(
                toolName = toolName,
                matcher = { it.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(it) },
                reason = "绝对路径写入被安全策略拦截(仅允许工作区内相对路径)",
                ruleId = "workspace-absolute-path",
                targetArg = "path",
            ),
        )

    /**
     * 检查规则;命中返回第一条规则,未命中返回 null。
     *
     * @param toolName 工具名
     * @param input 输入内容(参数值拼接)
     */
    fun check(toolName: String, input: String): ContentSafetyRule? = RULES.firstOrNull { it.toolName == toolName && it.matcher(input) }

    /**
     * 按参数表检查。带 [ContentSafetyRule.targetArg] 的规则只拿对应字段，
     * 其余规则仍拿全部参数值拼接后的串（与 [check] 行为一致）。
     *
     * 执行链路必须走这个入口：调用方把参数表原样传进来，不要在外部自己拼接再调 [check]，
     * 否则路径规则会连带检查文件正文。
     */
    fun checkArgs(toolName: String, args: Map<String, String>): ContentSafetyRule? {
        val joined by lazy { args.values.joinToString("\n") }
        return RULES.firstOrNull { rule ->
            if (rule.toolName != toolName) return@firstOrNull false
            val input = rule.targetArg?.let { argName -> args[argName] ?: return@firstOrNull false } ?: joined
            rule.matcher(input)
        }
    }
}
