package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.Logger
import io.zer0.muse.tools.script.NodePluginEngine
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

    /**
     * 执行前确保权限账本已按**已重验的 manifest** 播种。
     *
     * 为什么在这里：Hook 与启动激活也会在"首次工具调用之前"跑插件代码，而它们只查账本、
     * 不看清单；不在这里播种就会被误判成"没授权"。已有账本只登记新增为待审，不自动扩权
     * （见 [PluginCapabilityGateway.seedFromDeclaration]）。
     */
    suspend fun call(
        manifest: PluginManifest,
        entryCode: String,
        functionName: String,
        argsJson: String,
        configJson: String?,
        timeoutMs: Long = SkillEngine.DEFAULT_TIMEOUT_MS,
        /**
         * 多轮桥接（只有 node 档能用）。默认值是"桥接不可用"，
         * 因为**没接入必须表现为拒绝**，而不是让插件以为调用成功了。
         */
        bridgeHandler: suspend (String) -> io.zer0.muse.tools.script.SkillBridge.HandleResult = {
            io.zer0.muse.tools.script.SkillBridge.HandleResult.Failure("桥接不可用（宿主未接入能力总线）")
        },
    ): SkillEngineResult {
        PluginCapabilityLedgers.shared(context).seedFromDeclaration(manifest.id, manifest.capabilities)
        // Hook 与启动激活也是真在跑插件代码，必须与工具路径同一道门；
        // 否则"关掉工具执行"只是把绕行路线留给了后台激活。
        val pythonTrusted = PluginCapabilityLedgers.shared(context)
            .isApproved(manifest.id, PluginCapabilityGateway.RUNTIME_PYTHON)
        // 超时按授权收窄/放宽：没拿到 runtime.long 时，调用方传多大的 timeoutMs 都不作数
        val effectiveTimeoutMs = PluginExecutionBudget.effectiveTimeoutMs(
            requested = timeoutMs,
            longTaskAllowed = PluginCapabilityLedgers.shared(context)
                .isApproved(manifest.id, PluginCapabilityGateway.RUNTIME_LONG),
        )
        if (isPython(manifest) && !pythonTrusted) {
            Logger.w(TAG, "Python 插件未获高信任授权，跳过执行: ${manifest.id}/$functionName")
            return SkillEngineResult.Error(
                message = "插件需要「Python 运行时」高信任授权（子进程与应用同 UID，可读写应用数据）；" +
                    "请在 设置 → 工具与扩展 → 插件 → 能力与权限 中允许后重试",
            )
        }
        return when {
            isPython(manifest) ->
            PythonSkillEngine(context).callPluginFunction(
                entryCode = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                timeoutMs = effectiveTimeoutMs,
                scopeKey = manifest.id,
                pluginConfigJson = configJson,
            )
            isNode(manifest) -> NodePluginEngine(context).callPluginFunction(
                entryCode = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                timeoutMs = effectiveTimeoutMs,
                scopeKey = manifest.id,
                pluginConfigJson = configJson,
                // 交给调用方的同一个桥接判定：这里只是转发通道，**不在这里判权限**。
                // 两处各判一次，最先松动的就是绕过账本的那条。
                bridgeHandler = bridgeHandler,
            )
            else -> WebViewSkillEngine().callFunction(
                functionDef = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                timeoutMs = effectiveTimeoutMs,
                scopeKey = manifest.id,
                pluginConfigJson = configJson,
            )
        }
    }

    companion object {
        /** `manifest.runtime` 中表示内置 CPython 子进程的值。 */
        private const val TAG = "PluginFunctionInvoker"
        const val RUNTIME_PYTHON = "python"

        /** `manifest.runtime` 中表示内置 Node 子进程（`vm` 无网络上下文）的值。 */
        const val RUNTIME_NODE = "node"

        /** 该插件是否跑在 Python 运行时上。 */
        fun isPython(manifest: PluginManifest): Boolean =
            manifest.runtime.equals(RUNTIME_PYTHON, ignoreCase = true)

        /**
         * 该插件是否跑在 Node 子进程上。
         *
         * 这是 P0-6 的默认方向：WebView 与主应用共用渲染进程，跑飞脚本饿别人且杀不掉
         * （真机实测见 `PERF_LEDGER.md` §12.8），Node 子进程既可 `timeout` 打断同步死循环，
         * 也能被 `destroyForcibly()` 兜底。
         */
        fun isNode(manifest: PluginManifest): Boolean =
            manifest.runtime.equals(RUNTIME_NODE, ignoreCase = true)
    }
}
