package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.common.Logger
import io.zer0.common.resultOf
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
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

    /**
     * 懒激活：每条用户消息叫醒一次（观察型，返回值不用）。
     *
     * 文本**截断后再发**：事件是给插件做轻量反应的，不是把整段对话搬过去；
     * 需要完整内容应该走 `chat.read` 能力，那条路有独立的授权与条数限制。
     */
    fun dispatchMessage(text: String) {
        appScope.launch {
            dispatchEvent(
                event = ACTIVATION_ON_MESSAGE,
                capability = PluginCapabilityGateway.EVENT_ON_MESSAGE,
                payloadJson = messagePayload(text),
            )
        }
    }

    /** 懒激活：每次工具调用叫醒一次（只给工具名，不给参数 —— 参数里常有敏感内容）。 */
    fun dispatchToolCall(toolName: String) {
        appScope.launch {
            dispatchEvent(
                event = ACTIVATION_ON_TOOL_CALL,
                capability = PluginCapabilityGateway.EVENT_ON_TOOL_CALL,
                payloadJson = """{"event":"$ACTIVATION_ON_TOOL_CALL","tool":${quote(toolName)}}""",
            )
        }
    }

    /**
     * 派发一次懒激活事件。
     *
     * 三个上限一起挡住"插件越多越慢"：
     *  - **总开关**关闭时一次都不叫；
     *  - 只有**被授权**该能力、且在清单里声明了事件的插件才收到；
     *  - 单次事件最多叫醒 [MAX_EVENT_PLUGINS] 个插件，超出只记日志。
     */
    private suspend fun dispatchEvent(event: String, capability: String, payloadJson: String) {
        if (!settings.arePluginsGloballyEnabled()) return
        val ledger = PluginCapabilityLedgers.shared(context)
        val targets = pluginManager.list()
            .filter { it.enabled && it.kind == KIND_TOOL }
            .filter { ledger.isApproved(it.id, capability) }
            .take(MAX_EVENT_PLUGINS)
        targets.forEach { plugin ->
            val verified = resultOf { pluginManager.loadVerifiedPlugin(plugin.id) }.getOrNull() ?: return@forEach
            if (event !in verified.manifest.activationEvents) return@forEach
            val result = resultOf {
                invoker.call(
                    manifest = verified.manifest,
                    entryCode = verified.entryCode,
                    functionName = ACTIVATION_FUNCTION,
                    argsJson = "[$payloadJson]",
                    configJson = pluginManager.getPluginConfigJson(verified.manifest),
                )
            }.getOrNull()
            when (result) {
                null -> Logger.w(TAG, "事件派发异常: ${plugin.id} / $event")
                is SkillEngineResult.Success -> Logger.d(TAG, "事件已派发: ${plugin.id} / $event")
                is SkillEngineResult.Error -> Logger.w(TAG, "事件处理失败: ${plugin.id} / $event — ${result.message}")
            }
        }
    }

    private fun quote(value: String): String = io.zer0.common.AppJson.encodeToString(
        String.serializer(),
        value,
    )

    /** 消息事件的载荷（截断 + 转义）。抽出来是为了能在没有插件环境的地方验证。 */
    internal fun messagePayload(text: String): String {
        val preview = text.trim().take(MAX_EVENT_TEXT_CHARS)
        return """{"event":"$ACTIVATION_ON_MESSAGE","text":${quote(preview)}}"""
    }

    /** 工具调用事件的载荷。 */
    internal fun toolCallPayload(toolName: String): String =
        """{"event":"$ACTIVATION_ON_TOOL_CALL","tool":${quote(toolName)}}"""

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

        @Volatile
        private var shared: PluginActivationBridge? = null

        /** 由装配处在创建桥之后调用一次；界面/发送链路通过 [dispatchMessageGlobal] 使用。 */
        fun install(bridge: PluginActivationBridge) {
            shared = bridge
        }

        /**
         * 发送链路用的入口：桥没装好（例如测试、启动早期）时静默跳过。
         *
         * 用进程级持有者而不是继续加构造参数：`ChatGenerationController` 的参数已经很多，
         * 为一个观察型事件再加一个依赖不值得（也撞过 `LongParameterList`）。
         */
        fun dispatchMessageGlobal(text: String) {
            shared?.dispatchMessage(text)
        }

        /** 只有工具类插件有可执行入口。 */
        private const val KIND_TOOL = "tool"

        /** 支持的激活事件：启动。 */
        const val ACTIVATION_STARTUP = "onStartup"

        /** 懒激活：每条用户消息。 */
        const val ACTIVATION_ON_MESSAGE = "onMessage"

        /** 懒激活：每次工具调用。 */
        const val ACTIVATION_ON_TOOL_CALL = "onToolCall"

        /** 事件里携带的消息文本上限。 */
        private const val MAX_EVENT_TEXT_CHARS = 500

        /** 单次事件最多叫醒多少个插件。 */
        private const val MAX_EVENT_PLUGINS = 20

        /** 插件入口需要导出的激活函数名。 */
        const val ACTIVATION_FUNCTION = "onActivate"

        /** 激活入参（参数数组 JSON，取首元素为函数入参）。 */
        private const val STARTUP_ARGS = """[{"event":"onStartup"}]"""
    }
}
