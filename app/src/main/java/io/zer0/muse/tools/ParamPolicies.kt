package io.zer0.muse.tools

import java.util.concurrent.ConcurrentHashMap

/**
 * v1.0.53: 参数化权限策略注册表(既有实现 resolveInvocation)。
 *
 * 静态风险等级无法表达"同一工具不同参数风险不同"的场景,如:
 *  - open_url: http/https 自动放行,file:// 拒绝,未知协议转审批
 *  - execute_javascript: 含危险模式(runtime exec / rm -rf)直接拒绝
 *
 * 判定优先级(在 [ToolPermissionResolver.resolve] 内):
 *  1. 用户单工具策略 ALWAYS_DENY(最高优先,不变)
 *  2. 参数化策略(evaluate 返回非 null 时采用)
 *  3. 原静态风险等级逻辑
 *
 * 策略返回 null 表示"不参与参数级判定",回落静态逻辑。
 */
object ParamPolicies {

    /** 参数化策略:返回 null 表示不拦截,走静态逻辑。 */
    fun interface ParamPolicy {
        fun evaluate(args: Map<String, Any?>): ToolApprovalState?
    }

    private val registry = ConcurrentHashMap<String, ParamPolicy>()

    /** 注册工具参数策略(重复注册覆盖)。 */
    fun register(toolName: String, policy: ParamPolicy) {
        registry[toolName] = policy
    }

    /** 查询策略(未注册返回 null)。 */
    fun evaluate(toolName: String, args: Map<String, Any?>): ToolApprovalState? =
        registry[toolName]?.evaluate(args)

    /** 注册内置策略(构造时调用一次)。 */
    fun registerBuiltIn() {
        // open_url:http/https 放行,file:// 拒绝,未知协议转审批
        register("open_url") { args ->
            val url = (args["url"] as? String)?.trim() ?: return@register null
            when {
                url.startsWith("http://") || url.startsWith("https://") -> ToolApprovalState.Auto
                url.startsWith("file://") ->
                    ToolApprovalState.Denied("不允许打开本地文件链接(file://),已拦截")
                else -> ToolApprovalState.Pending
            }
        }

        // execute_javascript:危险模式直接拒绝,其余走静态等级
        register("execute_javascript") { args ->
            val code = (args["code"] as? String) ?: return@register null
            val dangerous = listOf(
                "rm -rf",
                "ProcessBuilder",
                "Runtime.getRuntime().exec",
                "format(",
                "document.cookie",
            ).any { code.contains(it) }
            if (dangerous) {
                ToolApprovalState.Denied("代码包含危险操作(rm -rf/进程执行/敏感信息外传),已拦截")
            } else null
        }
    }
}
