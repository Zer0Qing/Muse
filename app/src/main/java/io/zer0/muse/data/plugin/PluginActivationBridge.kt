package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 插件激活事件派发。
 *
 * `manifest.activationEvents` 此前只是一个被展示、从不派发的字段 —— 插件作者写了
 * `["onStartup"]`，以为启动时会被叫一次做初始化，实际什么都没发生，而且没有任何提示。
 * 这个桥把声明变成真的动作。
 *
 * ## 支持的事件
 *
 *  - `onStartup`：应用启动、或插件被启用时派发一次。插件入口需导出
 *    `onActivate(event)`（JS）或同名函数（Python），入参 `{ event: "onStartup" }`。
 *
 * `onCommand` / `onFileType` 目前**没有触发源**（斜杠命令是硬编码枚举，文件打开没有
 * 插件挂钩点）。它们的声明会被保留但不派发；安装页对此有明确说明，不假装支持。
 *
 * ## 去重
 *
 * 同一个 `<id>@<version>` 只激活一次。revisionFlow 在安装、启停、版本变化时都会发射，
 * 不记的话插件每次改配置就会被重新初始化一遍。插件被禁用或升级后记录自然失效
 * （记录集合每次按当前启用列表收窄），重新启用会再激活一次。
 *
 * ## 失败处理
 *
 * 激活失败只记日志。插件是可选能力，任何一个初始化出错都不该影响应用启动，
 * 也不该阻止其他插件激活。
 */
class PluginActivationBridge(
    private val context: Context,
    private val pluginManager: PluginManager,
    private val appScope: CoroutineScope,
    private val settings: io.zer0.muse.data.SettingsRepository,
) {
    private val invoker = PluginFunctionInvoker(context)

    /** 已激活过的 `<id>@<version>`。 */
    private val activatedKeys = HashSet<String>()

    /** 启动订阅（由装配处调用一次）。 */
    fun start() {
        appScope.launch {
            pluginManager.revisionFlow.collect { dispatchStartup() }
        }
        // revisionFlow 可能没有初始发射，首次同步单独跑一次。
        appScope.launch { dispatchStartup() }
    }

    /** 对所有声明了 `onStartup` 且已启用的插件派发一次激活。 */
    suspend fun dispatchStartup() {
        // 总开关关闭时不叫醒任何插件。已激活记录同时清空：重新打开时要能再激活一次。
        if (!settings.arePluginsGloballyEnabled()) {
            synchronized(activatedKeys) { activatedKeys.clear() }
            Logger.i(TAG, "插件总开关关闭，跳过激活")
            return
        }
        val plugins = pluginManager.list().filter { it.enabled && it.kind == KIND_TOOL }
        // 只保留仍然存在的插件记录：被禁用/卸载的插件重新启用时要能再激活一次。
        val liveKeys = plugins.map { keyOf(it) }.toSet()
        synchronized(activatedKeys) { activatedKeys.retainAll(liveKeys) }
        plugins.forEach { activateIfNeeded(it) }
    }

    private suspend fun activateIfNeeded(plugin: PluginManager.InstalledPlugin) {
        val key = keyOf(plugin)
        val verified = resolveActivatable(plugin, key) ?: return

        val result = resultOf {
            invoker.call(
                manifest = verified.manifest,
                entryCode = verified.entryCode,
                functionName = ACTIVATION_FUNCTION,
                argsJson = STARTUP_ARGS,
                configJson = pluginManager.getPluginConfigJson(verified.manifest),
            )
        }.getOrNull()

        when (result) {
            null -> Logger.w(TAG, "插件激活异常: ${plugin.id}")
            is SkillEngineResult.Success -> Logger.i(TAG, "插件已激活: ${plugin.id}")
            is SkillEngineResult.Error -> Logger.w(TAG, "插件激活失败: ${plugin.id} — ${result.message}")
        }
    }

    /**
     * 判断这个插件此刻该不该激活；该激活时返回校验过的入口，否则 null。
     *
     * 四个前置条件串在这里而不是平铺在 [activateIfNeeded] 里：
     * 「没声明 onStartup」和「已经激活过」都是正常跳过，不应该和「激活失败」混在同一层，
     * 否则日志会把前两种当成需要关注的事件。
     */
    private suspend fun resolveActivatable(
        plugin: PluginManager.InstalledPlugin,
        key: String,
    ): PluginManager.VerifiedPlugin? {
        synchronized(activatedKeys) { if (key in activatedKeys) return null }

        // 声明在磁盘上的 manifest 里，不在注册表缓存里 —— 与所有执行路径保持一致。
        val verified = resultOf { pluginManager.loadVerifiedPlugin(plugin.id) }.getOrNull() ?: run {
            Logger.d(TAG, "插件激活跳过(入口校验失败): ${plugin.id}")
            return null
        }
        if (ACTIVATION_STARTUP !in verified.manifest.activationEvents) return null

        // 先登记再执行：激活函数可能很慢，期间 revision 又变了不该重复跑。
        val registered = synchronized(activatedKeys) { activatedKeys.add(key) }
        return if (registered) verified else null
    }

    private fun keyOf(plugin: PluginManager.InstalledPlugin): String = "${plugin.id}@${plugin.version}"

    companion object {
        private const val TAG = "PluginActivation"

        /** 只有工具类插件有可执行入口。 */
        private const val KIND_TOOL = "tool"

        /** 支持的激活事件：启动。 */
        const val ACTIVATION_STARTUP = "onStartup"

        /** 插件入口需要导出的激活函数名。 */
        const val ACTIVATION_FUNCTION = "onActivate"

        /** 激活入参（参数数组 JSON，取首元素为函数入参）。 */
        private const val STARTUP_ARGS = """[{"event":"onStartup"}]"""
    }
}
