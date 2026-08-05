package io.zer0.muse.tools

/**
 * v1.0.53: 工具输入内容安全规则(既有实现 safety-policy.ts)。
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
            matcher = { it.contains("document.cookie") || (it.contains("fetch('http") && it.contains("token")) },
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
        // workspace_write 路径越界
        ContentSafetyRule(
            toolName = "workspace_write",
            matcher = { it.contains("..") },
            reason = "路径包含 .. 被安全策略拦截",
            ruleId = "workspace-traversal",
        ),
        // workspace_write 绝对路径越界
        ContentSafetyRule(
            toolName = "workspace_write",
            matcher = { it.startsWith("/") || Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(it) },
            reason = "绝对路径写入被安全策略拦截(仅允许工作区内相对路径)",
            ruleId = "workspace-absolute-path",
        ),
    )

    /**
     * 检查规则;命中返回第一条规则,未命中返回 null。
     *
     * @param toolName 工具名
     * @param input 输入内容(参数值拼接)
     */
    fun check(toolName: String, input: String): ContentSafetyRule? =
        RULES.firstOrNull { it.toolName == toolName && it.matcher(input) }
}
