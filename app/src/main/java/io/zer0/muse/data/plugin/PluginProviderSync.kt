package io.zer0.muse.data.plugin

import io.zer0.ai.plugin.ProviderPlugin
import io.zer0.ai.plugin.ProviderPluginRegistry
import io.zer0.common.Logger
import java.io.File

/**
 * 把 `kind = provider` 的插件接进 Provider 体系（插件计划：provider 并入）。
 *
 * 现状是两套：`ProviderPluginRegistry` 自己扫 `filesDir/muse_plugins/` 下的 JSON 文件，
 * 而插件体系有自己的安装/启用/卸载生命周期。两边各管一套的结果是：
 * 用户卸了插件，供应商还留在列表里；或者插件启用了，供应商要再手工丢一个 JSON。
 *
 * 这里的约定：
 *  - 供应商声明写在**插件包内**的 `provider.json`（整包随安装一起落盘，随卸载一起删除）；
 *  - **注册表里的 id 一律用插件 id**，不用文件里的 `id` 字段 —— 包 id 天然唯一，
 *    两个插件写同一个供应商 id 时不会互相顶掉；
 *  - 插件被禁用/卸载/文件坏掉时，**主动注销**，不留孤儿供应商。
 *
 * 信任边界与插件代码一致：`provider.json` 来自插件包，与它的 JS 同级信任。
 */
class PluginProviderSync(
    private val pluginManager: PluginManager,
    private val registry: ProviderPluginRegistry,
) {

    data class Report(val registered: Int, val removed: Int)

    /** 同步一次。幂等：可以反复调用。 */
    suspend fun sync(): Report {
        val providerPlugins = pluginManager.list().filter { it.kind == KIND_PROVIDER }
        val liveIds = providerPlugins.map { it.id }.toSet()
        var removed = 0
        // 先清掉"插件已经不在了"的供应商
        registry.list().forEach { existing ->
            if (existing.id !in liveIds) {
                registry.unregister(existing.id)
                removed++
            }
        }
        var registered = 0
        providerPlugins.forEach { plugin ->
            val declaration = if (plugin.enabled) readDeclaration(plugin) else null
            if (declaration == null) {
                // 禁用、文件缺失、解析失败、字段不合法：统一注销，不留孤儿
                if (registry.list().any { it.id == plugin.id }) {
                    registry.unregister(plugin.id)
                    removed++
                }
                return@forEach
            }
            registry.register(declaration)
            registered++
        }
        Logger.i(TAG, "供应商插件同步完成: 注册 $registered / 移除 $removed")
        return Report(registered, removed)
    }

    /**
     * 读包内 `provider.json`。
     *
     * 解析失败或字段不合法一律返回 null（调用方据此注销）——
     * 一个坏掉的声明不该让供应商列表停在半旧状态。
     */
    private fun readDeclaration(plugin: PluginManager.InstalledPlugin): ProviderPlugin? {
        val file = File(pluginManager.pluginDirFor(plugin.id), DECLARATION_FILE)
        if (!file.isFile) return null
        val parsed = runCatching {
            io.zer0.common.AppJson.decodeFromString(ProviderPlugin.serializer(), file.readText())
        }.onFailure { Logger.w(TAG, "provider.json 解析失败: ${plugin.id} — ${it.message}") }
            .getOrNull() ?: return null
        val normalized = parsed.copy(id = plugin.id, displayName = parsed.displayName.ifBlank { plugin.name })
        val valid = normalized.displayName.isNotBlank() &&
            normalized.baseUrl.startsWith("https://", ignoreCase = true)
        if (!valid) {
            Logger.w(TAG, "provider.json 字段不合法(需要 https baseUrl 与名称): ${plugin.id}")
            return null
        }
        return normalized
    }

    private companion object {
        const val TAG = "PluginProviderSync"

        /** 只认工具类与 provider 类插件；其它 kind 不参与。 */
        const val KIND_PROVIDER = "provider"

        /** 声明文件名（固定，避免同一包里出现两个入口）。 */
        const val DECLARATION_FILE = "provider.json"
    }
}
