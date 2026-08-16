package io.zer0.muse.tools.script

import io.zer0.muse.tools.JsSandbox

/**
 * Skill 引擎抽象层 (P3-1)。
 *
 * 定义 JS 脚本执行的统一契约，使上层（SkillExecutor / SkillPackageLoader）
 * 不直接依赖具体引擎实现（当前为 WebView V8，未来可替换为 QuickJS）。
 *
 * 设计要点：
 *  - 引擎实例不可重入：同一时刻只能执行一个脚本（WebView evaluateJavascript 串行）
 *  - 引擎池 [SkillEnginePool] 管理多实例复用，避免并发等待
 *  - 超时机制由实现层保证（[JsSandbox.execute] 默认 10s）
 *  - 安全限制由实现层保证（禁用 fetch/XHR/WebSocket 等，见 [JsSandbox.INIT_JS]）
 *
 * 说明: 既有实现 SkillEngine 接口设计，适配 Muse 的 WebView V8 后端。
 */
interface SkillEngine {

    /**
     * 执行 JS 表达式/语句块，返回 JSON 序列化结果。
     *
     * @param script JS 代码（表达式、语句块或函数调用）
     * @param timeoutMs 超时毫秒数，默认 10 秒
     * @param scopeKey C-30: 执行归属的插件 id(插件工具传 pluginId),用于 JS 沙盒按插件隔离
     *   熔断状态;内置/非插件工具不传使用默认全局 scope
     * @return 执行结果：成功时 [SkillEngineResult.Success] 包含 JSON 值和 console 日志；
     *         失败时 [SkillEngineResult.Error] 包含错误信息
     */
    suspend fun eval(script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, scopeKey: String? = null): SkillEngineResult

    /**
     * 调用已加载脚本中定义的函数。
     *
     * 先 [eval] 加载函数定义，再调用指定函数名。
     *
     * @param functionDef 函数定义 JS 代码（如 `function foo(a,b){return a+b}`）
     * @param functionName 要调用的函数名
     * @param argsJson 参数数组 JSON 字符串（如 `[1,2]`）
     * @param timeoutMs 超时毫秒数
     * @param scopeKey C-30: 执行归属的插件 id(插件工具传 pluginId),透传给 [eval]
     * @return 执行结果
     */
    suspend fun callFunction(
        functionDef: String,
        functionName: String,
        argsJson: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        scopeKey: String? = null,
    ): SkillEngineResult {
        // 默认实现：拼接 IIFE 调用
        val combined = buildString {
            append(functionDef)
            append("\n;")
            append("JSON.stringify(")
            append(functionName)
            append(".apply(null, ")
            append(argsJson)
            append("))")
        }
        return eval(combined, timeoutMs, scopeKey)
    }

    /**
     * 中断当前执行（尽力而为，WebView V8 无法真正中断 JS）。
     * 实现层应在超时后让 Kotlin 侧返回错误。
     */
    fun interrupt()

    companion object {
        /** 默认超时 10 秒。 */
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }
}

/**
 * Skill 引擎执行结果密封类。
 *
 * 使用 sealed class 而非 Any 联合类型（符合 AGENTS.md §5 类型安全规范）。
 */
sealed class SkillEngineResult {
    /**
     * 执行成功。
     *
     * @param valueJson 返回值的 JSON 字符串（可能为 "null" / "42" / '"text"' / '[1,2]' 等）
     * @param consoleLogs console.log/warn/error 收集的日志
     */
    data class Success(
        val valueJson: String,
        val consoleLogs: List<String>,
    ) : SkillEngineResult()

    /**
     * 执行失败（超时 / JS 异常 / 引擎不可用）。
     *
     * @param message 错误信息
     * @param consoleLogs 失败前收集的日志（可能有助于排查）
     */
    data class Error(
        val message: String,
        val consoleLogs: List<String> = emptyList(),
    ) : SkillEngineResult()
}
