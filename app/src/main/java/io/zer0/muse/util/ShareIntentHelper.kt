package io.zer0.muse.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger

/**
 * 分享/Chooser 启动安全工具。
 *
 * 崩溃背景: Compose 的 LocalContext 在部分容器内(如 Popup/悬浮窗/后台任务)拿到的是
 * 应用上下文而非 Activity。非 Activity context 启动 Activity 时若不携带
 * FLAG_ACTIVITY_NEW_TASK,Android 会抛 AndroidRuntimeException 崩溃。
 *
 * [startChooserSafely] 统一在非 Activity 场景下补 NEW_TASK,并自动附加
 * EXTRA_STREAM 读取权限(分享文件用)。所有分享入口都应走本方法。
 */
object ShareIntentHelper {

    /**
     * 安全启动分享 Chooser。
     *
     * @param context 任意 context(Activity 或应用上下文均可)
     * @param shareIntent 已配置的 ACTION_SEND intent(可含 EXTRA_STREAM)
     * @param chooserTitle chooser 标题(可空)
     */
    fun startChooserSafely(context: Context, shareIntent: Intent, chooserTitle: String? = null) {
        runCatching {
            context.startActivity(buildChooserIntent(context, shareIntent, chooserTitle))
        }.onFailure { error ->
            // 没有可处理分享的应用或 URI 授权失败时，分享入口不能再次让应用崩溃。
            Logger.w("ShareIntentHelper", "无法启动分享 chooser: ${error.message}", error)
        }
    }

    /** 构造带正确启动 flag 的 chooser，供调用方和 JVM 回归测试复用。 */
    internal fun buildChooserIntent(
        context: Context,
        shareIntent: Intent,
        chooserTitle: String? = null,
    ): Intent {
        // 分享文件需授予读取权限(EXTRA_STREAM 场景)
        if (shareIntent.hasExtra(Intent.EXTRA_STREAM)) {
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = if (chooserTitle != null) {
            Intent.createChooser(shareIntent, chooserTitle)
        } else {
            Intent.createChooser(shareIntent, null)
        }
        // 非 Activity context 必须带 NEW_TASK,否则 AndroidRuntimeException 崩溃
        if (context !is Activity) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return chooser
    }
}
