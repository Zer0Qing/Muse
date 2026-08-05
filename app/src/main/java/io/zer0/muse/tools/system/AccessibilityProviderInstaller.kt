package io.zer0.muse.tools.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.zer0.common.Logger

/**
 * P3-3: 无障碍服务安装器(引导启用)。
 *
 * 既有实现 方案为内置 APK 安装,但 Muse 采用 library 模块集成(服务已编译进主 APK),
 * 因此「安装」实质是引导用户到系统设置启用无障碍服务。
 *
 * 职责:
 *  1. [isInstalled]: 服务 APK 已就绪(library 模块,恒为 true)
 *  2. [isEnabled]: 服务是否已在系统设置启用(委托 [AccessibilityClient.isEnabled])
 *  3. [openSettings]: 跳转系统无障碍设置页
 *  4. [ensureEnabled]: 检查并引导启用,返回当前状态供 UI 展示
 */
class AccessibilityProviderInstaller(private val context: Context) {

    private val client = AccessibilityClient(context)

    companion object {
        private const val TAG = "A11yInstaller"
    }

    /** 服务模块已编译进 APK,无需单独安装。 */
    fun isInstalled(): Boolean = true

    /** 无障碍服务是否已启用。 */
    fun isEnabled(): Boolean = client.isEnabled()

    /**
     * 引导用户启用无障碍服务(打开系统设置页)。
     * @return true 表示已跳转;false 表示无障碍设置页不可用
     */
    fun openSettings(): Boolean {
        return try {
            client.openAccessibilitySettings()
            true
        } catch (e: Exception) {
            // 必要容错:部分定制 ROM 可能没有标准无障碍设置页
            Logger.e(TAG, "无法打开无障碍设置: ${e.message}", e)
            false
        }
    }

    /**
     * 确保无障碍服务已启用;未启用时返回引导提示。
     * @return [EnsureResult] 包含状态与提示信息
     */
    fun ensureEnabled(): EnsureResult {
        return if (isEnabled()) {
            EnsureResult.Enabled
        } else {
            EnsureResult.NeedsEnable
        }
    }

    sealed class EnsureResult {
        /** 服务已启用,可直接使用。 */
        object Enabled : EnsureResult()
        /** 服务未启用,需引导用户前往系统设置。 */
        object NeedsEnable : EnsureResult()
    }
}
