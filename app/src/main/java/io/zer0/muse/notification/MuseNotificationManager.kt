package io.zer0.muse.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.MainActivity
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.theme.WarmPaperTheme

/**
 * Phase 8.10: 消息生成通知管理器。
 *
 * 3 个通知渠道:
 *  - [CHANNEL_CHAT_COMPLETED]: 后台流式生成完成时弹出(应用在后台)
 *  - [CHANNEL_CHAT_LIVE_UPDATE]: 流式生成中的实时更新通知(前台进度条/后台可见)
 *  - [CHANNEL_WEB_SERVER]: Phase 8.11 Web 服务器运行状态通知(常驻)
 *
 * 设计:
 *  - 所有渠道 LOW importance(不发声,仅状态栏 + 横幅)
 *  - 渠道在 [MuseApp.onCreate] 通过 [ensureChannels] 一次性创建
 *  - PendingIntent 始终指向 [MainActivity],点击通知回到聊天页
 *  - 前台时不发 chat_completed 通知(避免干扰),仅后台生成完成才发
 *
 * 权限:
 *  - Android 13+ 需 POST_NOTIFICATIONS 运行时权限(由调用方在 UI 层申请)
 *  - Android 12- 不需要运行时权限,直接通知
 */
class MuseNotificationManager(private val context: Context) {

    private val nm: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // 问题6.4: 主动消息通知 ID 自增序列,保证每条主动消息分配唯一 ID(不覆盖旧通知)。
    // 与 NOTIF_ID_PROACTIVE_MESSAGE_BASE 做 OR 运算生成最终 ID,取低 12 位避免溢出范围。
    private val proactiveNotifIdSeq = java.util.concurrent.atomic.AtomicInteger(0)

    /** 一次性创建全部渠道(幂等,Android 8.0+ 必需)。 */
    fun ensureChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_CHAT_COMPLETED,
                context.getString(R.string.notif_channel_chat_completed_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_chat_completed_desc)
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_CHAT_LIVE_UPDATE,
                context.getString(R.string.notif_channel_chat_live_update_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_chat_live_update_desc)
                setShowBadge(false)
            },
            NotificationChannel(
                CHANNEL_WEB_SERVER,
                context.getString(R.string.notif_channel_web_server_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_web_server_desc)
                setShowBadge(false)
            },
            // 主动消息:IMPORTANCE_HIGH,有声音,因为这是"助手主动联系用户",要像微信来消息一样吸引注意
            NotificationChannel(
                CHANNEL_PROACTIVE_MESSAGE,
                context.getString(R.string.notif_channel_proactive_message_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.notif_channel_proactive_message_desc)
                setShowBadge(true)
            },
        )
        channels.forEach { nm.createNotificationChannel(it) }
        Logger.i(TAG, "Notification channels ensured")
    }

    /**
     * 显示"消息生成完成"通知(应用在后台时调用)。
     * @param sessionTitle 会话标题
     * @param preview 预览文本(消息前 50 字)
     */
    fun notifyChatCompleted(sessionTitle: String, preview: String) {
        val notif = NotificationCompat.Builder(context, CHANNEL_CHAT_COMPLETED)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_chat_completed_title, sessionTitle))
            .setContentText(preview.take(NOTIF_PREVIEW_MAX_LEN))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(buildMainActivityIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // M2-1: 改用 resultOf{}
        resultOf { nm.notify(NOTIF_ID_CHAT_COMPLETED, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notifyChatCompleted failed: $msg") }
    }

    /**
     * v0.32: 带策略的"回复完成"通知。
     *
     * 根据用户设置的通知策略决定是否发通知:
     *  - "never": 从不发通知
     *  - "when_unfocused": 应用不在前台时才发(默认行为,避免前台干扰)
     *  - "always": 总是发通知
     *
     * @param policy 通知策略字符串
     * @param sessionTitle 会话标题
     * @param preview 预览文本
     */
    fun notifyChatCompletedWithPolicy(
        policy: String,
        sessionTitle: String,
        preview: String,
    ) {
        when (policy) {
            "never" -> return
            "always" -> notifyChatCompleted(sessionTitle, preview)
            // when_unfocused 或未知值:默认 when_unfocused 行为(仅应用不在前台时通知)
            else -> {
                if (!isAppInForeground()) {
                    notifyChatCompleted(sessionTitle, preview)
                }
            }
        }
    }

    /**
     * v0.32: 检查应用是否在前台(用 ActivityManager)。
     * 用于通知策略判断,避免在前台时打扰用户。
     */
    @android.annotation.SuppressLint("DiscouragedApi")
    private fun isAppInForeground(): Boolean {
        // M2-1: 改用 resultOf{}
        return resultOf {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.appTasks.firstOrNull()?.taskInfo?.topActivity?.packageName == context.packageName
            } else {
                @Suppress("DEPRECATION")
                am.runningAppProcesses.any {
                    it.importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND &&
                        it.processName == context.packageName
                }
            }
        }.onError { msg, _ -> Logger.w(TAG, "isAppInForeground failed: $msg") }.getOrNull() ?: false
    }

    /**
     * 显示"主动消息"通知(助手像真人一样主动给用户发消息时调用)。
     *
     * v0.44: 通知栏大图标改用助手头像(图片/Emoji/首字母),不再统一用 app 图标。
     * 头像加载失败或无头像时回退到默认小图标。
     *
     * 用 IMPORTANCE_HIGH 渠道,有声音 + 横幅,吸引像微信来消息一样的注意。
     * 标题用"$assistantName 来消息了",正文是消息预览(截断 100 字),
     * BigTextStyle 展开完整内容;点击跳转 MainActivity 回到聊天页。
     *
     * @param assistant 助手实体(取 name + 头像)
     * @param preview 主动消息完整内容
     */
    suspend fun notifyProactiveMessage(assistant: AssistantEntity, preview: String) {
        val assistantName = assistant.name.ifBlank { "muse" }
        val avatar = loadAssistantAvatar(assistant)
        val builder = NotificationCompat.Builder(context, CHANNEL_PROACTIVE_MESSAGE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_proactive_message_title, assistantName))
            .setContentText(preview.take(NOTIF_PREVIEW_MAX_LEN))
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(buildMainActivityIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        // v0.44: 头像加载成功才设 largeIcon,否则用默认小图标
        if (avatar != null) builder.setLargeIcon(avatar)
        val notif = builder.build()
        // 问题6.4: 每条主动消息分配唯一 ID(基于助手 id hash + 自增序列),
        // 避免与 notifyReminder 的固定 ID 冲突,且不让新通知覆盖旧通知。
        // 范围 [NOTIF_ID_PROACTIVE_MESSAGE_BASE, NOTIF_ID_PROACTIVE_MESSAGE_BASE + 0x0FFF],
        // 与 NOTIF_ID_PROACTIVE_MESSAGE(1004)/NOTIF_ID_CHAT_COMPLETED(1001) 等固定 ID 错开。
        val notifId = NOTIF_ID_PROACTIVE_MESSAGE_BASE or
            (proactiveNotifIdSeq.getAndIncrement() and 0x0FFF)
        // M2-1: 改用 resultOf{}
        resultOf { nm.notify(notifId, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notifyProactiveMessage failed: $msg") }
    }

    /**
     * v0.44: 加载助手头像为 Bitmap(用于通知栏大图标)。
     *
     * 优先级:① 图片头像(avatarImageUrl,Coil 加载)→ ② Emoji 头像(Canvas 画)→ ③ 名称首字母(Canvas 画)。
     * 任一失败返回 null,调用方回退到默认小图标。
     *
     * 颜色取自默认暖纸主题(WarmPaperTheme.lightScheme)的 primary / surfaceVariant / onSurfaceVariant,
     * 通知栏不依赖 Compose 运行时,故直接取静态预设主题的色值。
     */
    private suspend fun loadAssistantAvatar(assistant: AssistantEntity): Bitmap? {
        // H2-1: 改用 resultOf{}(正确重抛 CancellationException,loader.execute 是 suspend)
        return resultOf {
            when {
                // ① 图片头像:用 Coil 加载为 Bitmap
                assistant.hasImageAvatar() -> {
                    val loader = Coil.imageLoader(context)
                    val req = ImageRequest.Builder(context)
                        .data(assistant.avatarImageUrl)
                        .size(AVATAR_SIZE)
                        .build()
                    val result = loader.execute(req)
                    (result as? SuccessResult)?.drawable?.let { d ->
                        (d as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    }
                }
                // ② Emoji 头像:Canvas 画到 AVATAR_SIZE x AVATAR_SIZE,背景圆形 primary 色
                assistant.avatarEmoji.isNotBlank() -> renderTextAvatar(
                    text = assistant.avatarEmoji,
                    bgColor = WarmPaperTheme.lightScheme.primary.toArgb(),
                    textColor = WarmPaperTheme.lightScheme.onPrimary.toArgb(),
                )
                // ③ 名称首字母:Canvas 画到 AVATAR_SIZE x AVATAR_SIZE,背景圆形 surfaceVariant 色,文字 onSurfaceVariant 色
                else -> {
                    val initial = assistant.name.firstOrNull()?.toString()?.ifBlank { "A" } ?: "A"
                    renderTextAvatar(
                        text = initial,
                        bgColor = WarmPaperTheme.lightScheme.surfaceVariant.toArgb(),
                        textColor = WarmPaperTheme.lightScheme.onSurfaceVariant.toArgb(),
                    )
                }
            }
        }.onError { msg, _ -> Logger.w(TAG, "加载头像失败: $msg") }.getOrNull()
    }

    /**
     * v0.44: 用 Canvas 把文字(Emoji 或首字母)画到 96x96 圆形 Bitmap 上。
     *
     * @param text 要画的文字
     * @param bgColor 圆形背景色(Android int)
     * @param textColor 文字色(Android int)
     */
    private fun renderTextAvatar(text: String, bgColor: Int, textColor: Int): Bitmap {
        val size = AVATAR_SIZE
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = bgColor
            style = Paint.Style.FILL
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.color = textColor
        paint.textSize = size * TEXT_SIZE_RATIO
        paint.textAlign = Paint.Align.CENTER
        val baseline = size / 2f - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2
        canvas.drawText(text, size / 2f, baseline, paint)
        return bmp
    }

    /**
     * 更新流式生成进度通知(可被同一 NOTIF_ID 反复更新)。
     * @param sessionTitle 会话标题
     * @param currentChars 当前已生成字符数
     * @param isStreaming 是否仍在生成(false 时取消通知)
     */
    fun updateLiveProgress(sessionTitle: String, currentChars: Int, isStreaming: Boolean) {
        if (!isStreaming) {
            nm.cancel(NOTIF_ID_LIVE_UPDATE)
            return
        }
        val notif = buildGenerationNotification(sessionTitle, currentChars)
        // M2-1: 改用 resultOf{}
        resultOf { nm.notify(NOTIF_ID_LIVE_UPDATE, notif) }
            .onError { msg, _ -> Logger.w(TAG, "updateLiveProgress failed: $msg") }
    }

    /**
     * v1.43: 构建前台服务使用的流式生成通知。
     *
     * 与 [updateLiveProgress] 共用同一渠道和 ID,保证服务启动与后续更新一致。
     */
    fun buildGenerationNotification(sessionTitle: String, currentChars: Int) =
        NotificationCompat.Builder(context, CHANNEL_CHAT_LIVE_UPDATE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_live_progress_title, sessionTitle))
            .setContentText(context.getString(R.string.notif_live_progress_text, currentChars))
            .setOngoing(true)
            .setProgress(0, 0, true) // 不确定进度条
            .setContentIntent(buildMainActivityIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * 显示/更新 Web 服务器运行状态通知(常驻,直到服务器停止)。
     * @param port 监听端口
     * @param isRunning true 显示"运行中",false 取消通知
     */
    fun updateWebServerStatus(port: Int, isRunning: Boolean) {
        if (!isRunning) {
            nm.cancel(NOTIF_ID_WEB_SERVER)
            return
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_WEB_SERVER)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(context.getString(R.string.notif_web_server_title))
            .setContentText(context.getString(R.string.notif_web_server_text, port))
            .setOngoing(true)
            .setContentIntent(buildMainActivityIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        // M2-1: 改用 resultOf{}
        resultOf { nm.notify(NOTIF_ID_WEB_SERVER, notif) }
            .onError { msg, _ -> Logger.w(TAG, "updateWebServerStatus failed: $msg") }
    }

    /**
     * v1.136: 显示定时提醒通知。
     *
     * @param title 通知标题
     * @param message 通知正文
     * @param notificationId 通知 id(用提醒 id 的 hashCode,便于取消)
     */
    fun notifyReminder(title: String, message: String, notificationId: Int) {
        val notif = NotificationCompat.Builder(context, CHANNEL_PROACTIVE_MESSAGE)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(message.take(NOTIF_PREVIEW_MAX_LEN))
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(buildMainActivityIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        resultOf { nm.notify(notificationId, notif) }
            .onError { msg, _ -> Logger.w(TAG, "notifyReminder failed: $msg") }
    }

    /** 取消所有 muse 发出的通知。 */
    fun cancelAll() {
        nm.cancel(NOTIF_ID_CHAT_COMPLETED)
        nm.cancel(NOTIF_ID_LIVE_UPDATE)
        nm.cancel(NOTIF_ID_WEB_SERVER)
        nm.cancel(NOTIF_ID_PROACTIVE_MESSAGE)
    }

    /** 构建 MainActivity 的 PendingIntent(点击通知回到聊天页)。 */
    private fun buildMainActivityIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(context, 0, intent, flags)
    }

    companion object {
        private const val TAG = "MuseNotifMgr"
        const val CHANNEL_CHAT_COMPLETED = "chat_completed"
        const val CHANNEL_CHAT_LIVE_UPDATE = "chat_live_update"
        const val CHANNEL_WEB_SERVER = "web_server"
        const val CHANNEL_PROACTIVE_MESSAGE = "proactive_message"
        private const val NOTIF_ID_CHAT_COMPLETED = 1001
        private const val NOTIF_ID_LIVE_UPDATE = 1002
        private const val NOTIF_ID_WEB_SERVER = 1003
        private const val NOTIF_ID_PROACTIVE_MESSAGE = 1004
        // 问题6.4: 主动消息唯一通知 ID 的基址,与上面固定 ID 错开。
        // 最终 ID = BASE or (seq & 0x0FFF),范围 [0x10000000, 0x10000FFF],不会与固定 ID(1001~1004)冲突。
        private const val NOTIF_ID_PROACTIVE_MESSAGE_BASE = 0x1000_0000
        private const val NOTIF_PREVIEW_MAX_LEN = 200
        // L2-1: 头像尺寸(像素),用于 Coil 加载与 Canvas 绘制
        private const val AVATAR_SIZE = 96
        // L2-2: 文字头像中文字字号占头像尺寸的比例
        private const val TEXT_SIZE_RATIO = 0.5f
    }
}
