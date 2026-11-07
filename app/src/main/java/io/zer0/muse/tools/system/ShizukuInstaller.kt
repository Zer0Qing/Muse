package io.zer0.muse.tools.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.zer0.common.Logger

/**
 * P3-3: Shizuku 安装器(引导安装)。
 *
 * Muse 不内置 Shizuku APK(体积大 + 版本更新频繁),改为引导用户从官方渠道安装:
 *  - GitHub Releases: https://github.com/RikkaApps/Shizuku/releases
 *  - Google Play: com.rikka.shizuku
 *
 * 职责:
 *  1. [isInstalled]: Shizuku APK 是否已安装
 *  2. [openDownloadPage]: 打开官方下载页(优先 Play Store,回退 GitHub)
 *  3. [ensureInstalled]: 检查并引导安装
 */
class ShizukuInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuInstaller"

        /** Shizuku APK 包名(不是 SDK 的 Java namespace)。 */
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** Shizuku GitHub Releases 页。 */
        const val DOWNLOAD_URL = "https://github.com/RikkaApps/Shizuku/releases"
    }

    /** Shizuku APK 是否已安装。 */
    fun isInstalled(): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) != null
    } catch (e: Exception) {
        // PackageManager.NameNotFoundException 时 getPackageInfo 抛异常
        false
    }

    /**
     * 直接打开已安装的 Shizuku 应用（启动服务/手动授权都在它里面完成）。
     *
     * 「服务未运行」时把用户扔去下载页是旧实现的错误引导：非 root 设备重启后
     * Shizuku 必掉，用户需要的是打开 App 点「启动」，不是再下载一遍。
     * 未安装或拉起失败时才回落到下载页。
     *
     * @return true 成功打开应用；false 回落到下载页（仍可能失败）
     */
    fun openApp(): Boolean {
        val launchIntent = runCatching {
            context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
        }.getOrNull()
        if (launchIntent != null) {
            val opened = runCatching {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }.isSuccess
            if (opened) return true
            Logger.w(TAG, "拉起 Shizuku 应用失败,回落下载页")
        }
        return openDownloadPage()
    }

    /**
     * 打开 Shizuku 下载页(优先 Play Store,回退浏览器打开 GitHub)。
     * @return true 跳转成功;false 跳转失败
     */
    fun openDownloadPage(): Boolean {
        // 优先尝试 Play Store 详情页
        val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$SHIZUKU_PACKAGE")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (playIntent.resolveActivity(context.packageManager) != null) {
            return try {
                context.startActivity(playIntent)
                true
            } catch (e: Exception) {
                Logger.w(TAG, "打开 Play Store 失败: ${e.message}")
                openGitHubInBrowser()
            }
        }
        return openGitHubInBrowser()
    }

    private fun openGitHubInBrowser(): Boolean {
        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(browserIntent)
            true
        } catch (e: Exception) {
            Logger.e(TAG, "无法打开下载页: ${e.message}", e)
            false
        }
    }

    /**
     * 确保 Shizuku 已安装;未安装时返回引导提示。
     * @return [EnsureResult]
     */
    fun ensureInstalled(): EnsureResult {
        return if (isInstalled()) EnsureResult.Installed else EnsureResult.NeedsInstall
    }

    sealed class EnsureResult {
        /** Shizuku APK 已安装(但不代表服务已运行,需启动 Shizuku 应用)。 */
        object Installed : EnsureResult()

        /** Shizuku 未安装,需引导下载。 */
        object NeedsInstall : EnsureResult()
    }
}
