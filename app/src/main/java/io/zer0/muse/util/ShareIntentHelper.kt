package io.zer0.muse.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.FileProvider
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseDateFormats
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    private const val EXTRA_TEXT_MAX_BYTES = 96 * 1024

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
            MuseToast.show(context.getString(R.string.tool_share_failed))
        }
    }

    /**
     * 安全分享文本：超过 Binder 事务安全阈值（96 KB）时自动落盘临时文件改走 EXTRA_STREAM，
     * 避免大段 Markdown/JSON 直接触发 TransactionTooLargeException 崩溃。
     */
    fun shareTextOrFileSafely(
        context: Context,
        mime: String,
        content: String,
        subject: String,
        chooserTitle: String? = null,
        filePrefix: String = "muse-share",
        fileExtension: String = "txt",
    ) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size <= EXTRA_TEXT_MAX_BYTES) {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_TEXT, content)
                putExtra(Intent.EXTRA_SUBJECT, subject)
            }
            startChooserSafely(context, shareIntent, chooserTitle)
            return
        }
        runCatching {
            val exportDir = File(context.cacheDir, "export").apply { mkdirs() }
            val timestamp = SimpleDateFormat(MuseDateFormats.FILE_TIMESTAMP, Locale.US).format(Date())
            val file = File(exportDir, "$filePrefix-$timestamp.$fileExtension")
            file.writeBytes(bytes)
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
            val fileIntent = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, subject)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startChooserSafely(context, fileIntent, chooserTitle)
        }.onFailure { error ->
            Logger.w("ShareIntentHelper", "大文本改写导出文件分享失败: ${error.message}", error)
            MuseToast.show(context.getString(R.string.chat_share_failed_no_uri))
        }
    }

    /** 构造带正确启动 flag 的 chooser，供调用方和 JVM 回归测试复用。 */
    internal fun buildChooserIntent(context: Context, shareIntent: Intent, chooserTitle: String? = null): Intent {
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

/**
 * 安全触发系统 ActivityResultLauncher：在阉割了文件选择器/语音识别/拍照 Activity 的 ROM 上
 * 捕获 ActivityNotFoundException / SecurityException 并弹轻提示，不让页面直接闪退。
 */
fun <I> ActivityResultLauncher<I>.launchSafely(input: I, context: Context, fallbackMessage: String? = null): Boolean = runCatching {
    launch(input)
    true
}.onFailure { error ->
    Logger.w("ActivityLauncher", "无法启动系统选择器或授权页: ${error.message}", error)
    val message = fallbackMessage ?: context.getString(
        R.string.chat_err_open_file_picker,
        error.localizedMessage ?: error.javaClass.simpleName,
    )
    MuseToast.show(message)
}.getOrDefault(false)
