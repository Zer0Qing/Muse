package io.zer0.muse.data.plugin

import android.content.Context
import io.zer0.muse.data.SettingsStoreContextHolder
import java.io.File

/**
 * 进程内共享的 [PluginCapabilityGateway]。
 *
 * 需要共享是因为**两条出口都要问同一个账本**：
 *  - `SkillBridge` 的 `fs_* / clipboard_* / http_*`（在 `SkillMediaToolsImpl` 判定）；
 *  - 沙盒里 `fetch / XMLHttpRequest` 走的 `__museHttp`（在 `JsSandbox` 判定）。
 *
 * 各建一份会立刻出现"同一插件一边放行一边拒绝"，所以做成单例；
 * 账本文件在 `filesDir`，进程内只该有一个写者。
 */
object PluginCapabilityLedgers {

    @Volatile
    private var gateway: PluginCapabilityGateway? = null

    fun shared(context: Context): PluginCapabilityGateway = gateway ?: synchronized(this) {
        gateway ?: PluginCapabilityGateway(storeFile(context)).also { gateway = it }
    }

    /** 未初始化时返回 null：调用方据此区分"没上下文"与"确实没授权"。 */
    fun sharedOrNull(): PluginCapabilityGateway? = gateway

    private fun storeFile(context: Context): File =
        File(context.applicationContext.filesDir, PluginPermissionStore.FILE_NAME)

    /** 供测试与非 Koin 场景显式重置。 */
    internal fun resetForTests() {
        synchronized(this) { gateway = null }
    }

    /**
     * 拿不到 Context 时的兜底（`SettingsStoreContextHolder` 在 Application.onCreate 就设好了）。
     * 只用于 JsSandbox 这类已经握着 applicationContext 的地方。
     */
    fun sharedFromHolderOrNull(): PluginCapabilityGateway? {
        gateway?.let { return it }
        val context = SettingsStoreContextHolder.applicationContext ?: return null
        return shared(context)
    }
}
