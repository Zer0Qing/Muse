package io.zer0.muse.data.plugin

import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.hook.HookRegistry
import io.zer0.muse.hook.PromptFinalizeHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v2.x（插件 Hook 体系 MVP）：把**已启用插件**声明的 Hook 挂进 [HookRegistry]。
 *
 * 设计：
 *  - 订阅 [PluginManager.revisionFlow]，插件安装/卸载/启停/版本变化时全量重挂；
 *  - 只重挂本桥注册的 hook（id 前缀 [HOOK_ID_PREFIX]），不碰内置 Kotlin hook；
 *  - MVP 只接入 `prompt_finalize` 一个 Hook 点（提示词定稿前改写历史）——
 *    这是插件影响生成内容的最小切入点，后续按需扩 Hook 点；
 *  - 单个插件加载失败不影响其他插件；
 *  - 入口代码每次都经 [PluginManager.loadVerifiedFunction] 重新校验（不信任注册表缓存）。
 *
 * 为什么用桥而不是让 PluginManager 直接持有 HookRegistry：
 *  HookRegistry 是纯逻辑注册表，PluginManager 是存储/安全域；二者通过本桥解耦，
 *  避免在 PluginManager 里注入执行设施，也让测试更容易。
 */
class PluginHookBridge(
    private val pluginManager: PluginManager,
    private val hookRegistry: HookRegistry,
    private val appScope: CoroutineScope,
) {
    private val registered = HashSet<String>()

    /** 启动订阅（由装配处调用一次）。 */
    fun start() {
        appScope.launch {
            pluginManager.revisionFlow.collect { sync() }
        }
        // 首次同步（revisionFlow 可能没有初始发射）
        appScope.launch { sync() }
    }

    /** 全量重挂插件 hook（幂等：先摘旧再挂新）。 */
    fun sync() {
        // 先摘掉本桥之前注册的全部 hook
        synchronized(registered) {
            registered.forEach { id ->
                runCatching { hookRegistry.unregister(PromptFinalizeHook::class, id) }
            }
            registered.clear()
        }

        pluginManager.list().filter { it.enabled && it.kind == "tool" }.forEach { plugin ->
            // 入口函数必须真实定义（loadVerifiedFunction 会重新读盘并过安全门）
            val verified =
                resultOf { pluginManager.loadVerifiedFunction(plugin.id, HOOK_FUNCTION_NAME) }.getOrNull() ?: run {
                    Logger.d(TAG, "插件 hook 跳过(未定义 ${HOOK_FUNCTION_NAME} 或入口校验失败): ${plugin.id}")
                    return@forEach
                }
            if (HOOK_PROMPT_FINALIZE !in verified.manifest.hooks) return@forEach

            val hook = PluginPromptFinalizeHook(
                pluginId = plugin.id,
                entryCode = verified.entryCode,
                configJson = pluginManager.getPluginConfigJson(verified.manifest),
            )
            hookRegistry.register(hook)
            synchronized(registered) { registered.add(hook.id) }
            Logger.i(TAG, "插件 hook 已注册: ${plugin.id} → ${hook.id}")
        }
    }

    companion object {
        private const val TAG = "PluginHookBridge"

        /** 本桥注册的 hook id 前缀（重挂时只摘自己的）。 */
        const val HOOK_ID_PREFIX = "plugin:"

        /** MVP 支持的 Hook 点。 */
        const val HOOK_PROMPT_FINALIZE = "prompt_finalize"

        /** 插件入口需要导出的 hook 函数名。 */
        const val HOOK_FUNCTION_NAME = "onPromptFinalize"
    }
}
