package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillEngine
import io.zer0.muse.tools.script.SkillEngineResult
import io.zer0.muse.tools.script.WebViewSkillEngine

/**
 * 按 `manifest.runtime` 把一次插件函数调用分派到对应引擎。
 *
 * 抽出来的原因：调用点已经有三处（工具执行、`prompt_finalize` Hook、启动激活），
 * 每处各写一遍 `if (runtime == python)` 的话，将来加第三个运行时就得记得改三遍，
 * 而漏掉的那一处表现是「插件装了但某个能力静默不生效」—— 不报错，只是没反应。
 *
 * 两种运行时的参数语义已经对齐（入口代码 + 函数名 + 参数数组 JSON + 配置 JSON），
 * 所以这里不需要按运行时改写调用方式，只切换引擎实现。
 */
internal class PluginFunctionInvoker(private val context: Context) {

    suspend fun call(
        manifest: PluginManifest,
        entryCode: String,
        functionName: String,
        argsJson: String,
        configJson: String?,
        timeoutMs: Long = SkillEngine.DEFAULT_TIMEOUT_MS,
    ): SkillEngineResult =
        if (isPython(manifest)) {
            PythonSkillEngine(context).callPluginFunction(
                entryCode = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                timeoutMs = timeoutMs,
                scopeKey = manifest.id,
                pluginConfigJson = configJson,
            )
        } else {
            WebViewSkillEngine().callFunction(
                functionDef = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                timeoutMs = timeoutMs,
                scopeKey = manifest.id,
                pluginConfigJson = configJson,
            )
        }

    companion object {
        /** `manifest.runtime` 中表示内置 CPython 子进程的值。 */
        const val RUNTIME_PYTHON = "python"

        /** 该插件是否跑在 Python 运行时上。 */
        fun isPython(manifest: PluginManifest): Boolean =
            manifest.runtime.equals(RUNTIME_PYTHON, ignoreCase = true)
    }
}
