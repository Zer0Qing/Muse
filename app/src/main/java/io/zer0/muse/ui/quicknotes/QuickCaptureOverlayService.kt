package io.zer0.muse.ui.quicknotes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.notification.MuseNotificationTarget
import androidx.core.content.ContextCompat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.quicknote.QuickNoteEntity
import io.zer0.muse.data.session.MuseDb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.UUID
import kotlin.math.roundToInt

/**
 * 快速记录系统悬浮窗。
 *
 * 仅在用户主动开启悬浮窗开关并授予 SYSTEM_ALERT_WINDOW 后运行。
 * 折叠态是小胶囊,展开态是紧凑输入卡;标题栏可拖动,默认位于屏幕垂直中线。
 */
class QuickCaptureOverlayService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings: SettingsRepository by inject()
    private lateinit var windowManager: WindowManager
    private var overlayView: View? = null
    private var expanded = false
    private var windowParams: WindowManager.LayoutParams? = null
    private var positionX = 0
    private var positionY = 0
    private var collapsedVerticalPositionFraction = 0.5f
    private var collapsedPositionReady = false
    private var collapsedShowRequested = false
    private var collapsedPositionLoadJob: Job? = null
    private var themeColors: QuickCaptureThemeColors? = null
    private var themeWatchJob: Job? = null
    private var collapsedButton: ImageButton? = null
    private var expandedPanel: LinearLayout? = null
    private var expandedTitle: TextView? = null
    private var expandedClose: ImageButton? = null
    private var expandedInput: EditText? = null
    private var expandedSave: TextView? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startForegroundCompat(buildNotification())
        themeWatchJob = serviceScope.launch {
            try {
                observeQuickCaptureThemeColors(applicationContext).collectLatest { colors ->
                    themeColors = colors
                    applyThemeColors()
                }
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                Logger.w(TAG, "悬浮窗主题监听失败: ${t.message}")
                themeColors = QuickCaptureThemeColors.fallback(applicationContext)
                applyThemeColors()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        requestShowCollapsed()
        return START_NOT_STICKY
    }

    /** 等待持久化位置加载完成后再创建侧边条,避免先显示中线再跳回用户位置。 */
    private fun requestShowCollapsed() {
        collapsedShowRequested = true
        if (collapsedPositionReady) {
            showCollapsed()
            return
        }
        if (collapsedPositionLoadJob != null) return
        collapsedPositionLoadJob = serviceScope.launch {
            collapsedVerticalPositionFraction = settings.quickCaptureOverlayVerticalPositionFractionFlow.first()
            collapsedPositionReady = true
            collapsedPositionLoadJob = null
            if (collapsedShowRequested) showCollapsed()
        }
    }

    private fun showCollapsed() {
        expanded = false
        removeOverlay()
        val colors = currentThemeColors()
        // 与应用内侧滑把手保持同一语言:窄竖向胶囊 + Chevron 图标,不再显示单个汉字。
        // 20dp 足够承载图标和触摸区域,比原来的 24dp 更贴边、更轻量。
        val handleWidth = dp(20)
        val handleHeight = dp(64)
        val view = ImageButton(this).apply {
            setImageResource(R.drawable.ic_quick_capture_chevron)
            imageTintList = ColorStateList.valueOf(colors.primary)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            contentDescription = getString(R.string.quick_notes_title)
            background = edgeHandleBackground(colors)
            elevation = dp(8).toFloat()
        }
        collapsedButton = view
        val bounds = screenBounds()
        positionX = bounds.right - handleWidth - dp(2)
        positionY = collapsedPositionY(bounds, handleHeight)
        val params = baseParams(handleWidth, handleHeight).apply {
            x = positionX
            y = positionY
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        addOverlay(view, params)
        makeDraggable(view, onClick = { showExpanded() }, onDragEnd = ::saveCollapsedPosition)
    }

    private fun showExpanded() {
        expanded = true
        removeOverlay()
        val colors = currentThemeColors()
        val panelWidth = dp(264)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(colors.surface, dp(22))
            elevation = dp(12).toFloat()
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = getString(R.string.quick_notes_title)
            textSize = 16f
            setTextColor(colors.onSurface)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = getString(R.string.quick_notes_title)
        }
        val close = ImageButton(this).apply {
            setImageResource(R.drawable.ic_quick_capture_close)
            imageTintList = ColorStateList.valueOf(colors.onSurface)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.action_close)
            setOnClickListener { showCollapsed() }
        }
        header.addView(
            title,
            LinearLayout.LayoutParams(0, dp(38), 1f),
        )
        header.addView(close, LinearLayout.LayoutParams(dp(36), dp(36)))
        panel.addView(header)

        val input = EditText(this).apply {
            hint = getString(R.string.quick_notes_input_hint)
            setHintTextColor(withAlpha(colors.onSurfaceVariant, 0xB0))
            setTextColor(colors.onSurface)
            textSize = 15f
            gravity = Gravity.TOP or Gravity.START
            minLines = 3
            maxLines = 6
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = roundedBackground(colors.surfaceVariant, dp(16))
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NONE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        panel.addView(
            input,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(104),
            ).apply { topMargin = dp(8) },
        )

        val save = TextView(this).apply {
            text = getString(R.string.quick_notes_save)
            textSize = 14f
            setTextColor(colors.onPrimary)
            gravity = Gravity.CENTER
            contentDescription = getString(R.string.quick_notes_save)
            background = roundedBackground(colors.primary, dp(18))
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { saveText(input.text?.toString().orEmpty()) }
        }
        panel.addView(
            save,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(40),
            ).apply { topMargin = dp(8) },
        )

        val bounds = screenBounds()
        val panelHeight = dp(220)
        positionX = (bounds.right - panelWidth - dp(16)).coerceAtLeast(dp(8))
        positionY = (bounds.centerY() - panelHeight / 2).coerceAtLeast(dp(8))
        val params = baseParams(panelWidth, WindowManager.LayoutParams.WRAP_CONTENT).apply {
            x = positionX
            y = positionY
            flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        }
        addOverlay(panel, params)
        expandedPanel = panel
        expandedTitle = title
        expandedClose = close
        expandedInput = input
        expandedSave = save
        makeDraggable(header, null)
        input.requestFocus()
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        inputMethod.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
    }

    private fun saveText(rawText: String) {
        val text = rawText.trim()
        if (text.isBlank()) return
        val tags = Regex("#([\\p{L}\\p{N}_-]+)")
            .findAll(text)
            .map { it.groupValues[1].lowercase() }
            .distinct()
            .toList()
        val title = text.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.take(50)
            .orEmpty()
            .ifBlank { getString(R.string.quick_notes_title) }
        serviceScope.launch(Dispatchers.IO) {
            MuseDb.get(applicationContext).quickNoteDao().upsert(
                QuickNoteEntity(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    content = text,
                    tags = tags,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ),
            )
            withContext(Dispatchers.Main.immediate) {
                requestShowCollapsed()
            }
        }
    }

    private fun makeDraggable(
        view: View,
        onClick: (() -> Unit)?,
        onDragEnd: (() -> Unit)? = null,
    ) {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { touchView, event ->
            val params = windowParams ?: return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(4) || kotlin.math.abs(dy) > dp(4)) moved = true
                    params.x = startX + dx
                    params.y = startY + dy
                    clampPosition(params)
                    positionX = params.x
                    positionY = params.y
                    overlayView?.let { windowManager.updateViewLayout(it, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // 保留无障碍点击语义,同时复用拖动区域的点击回调。
                        touchView.performClick()
                        onClick?.invoke()
                    } else {
                        onDragEnd?.invoke()
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }
    }

    private fun addOverlay(view: View, params: WindowManager.LayoutParams) {
        try {
            windowManager.addView(view, params)
            overlayView = view
            windowParams = params
        } catch (e: SecurityException) {
            Logger.w(TAG, "添加快速记录悬浮窗失败: ${e.message}")
            stopSelf()
        } catch (e: RuntimeException) {
            Logger.w(TAG, "快速记录悬浮窗窗口异常: ${e.message}")
            stopSelf()
        }
    }

    private fun removeOverlay() {
        overlayView?.let { view ->
            runCatching { windowManager.removeView(view) }
        }
        overlayView = null
        windowParams = null
        collapsedButton = null
        expandedPanel = null
        expandedTitle = null
        expandedClose = null
        expandedInput = null
        expandedSave = null
    }

    private fun baseParams(width: Int, height: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            format = PixelFormat.TRANSLUCENT
        }

    private fun clampPosition(params: WindowManager.LayoutParams) {
        val bounds = screenBounds()
        val width = if (params.width > 0) params.width else dp(310)
        val height = if (params.height > 0) params.height else dp(260)
        params.x = params.x.coerceIn(dp(8), (bounds.width() - width - dp(8)).coerceAtLeast(dp(8)))
        params.y = params.y.coerceIn(dp(8), (bounds.height() - height - dp(8)).coerceAtLeast(dp(8)))
    }

    /** 根据归一化位置计算侧边条的 Y 坐标,适配不同屏幕尺寸。 */
    private fun collapsedPositionY(bounds: Rect, handleHeight: Int): Int {
        val minY = dp(8)
        val maxY = (bounds.height() - handleHeight - dp(8)).coerceAtLeast(minY)
        return minY + ((maxY - minY) * collapsedVerticalPositionFraction.coerceIn(0f, 1f)).roundToInt()
    }

    /** 拖动结束后持久化侧边条位置,只写一次,不在 MOVE 事件中频繁访问 DataStore。 */
    private fun saveCollapsedPosition() {
        if (expanded) return
        val params = windowParams ?: return
        val bounds = screenBounds()
        val minY = dp(8)
        val maxY = (bounds.height() - params.height - dp(8)).coerceAtLeast(minY)
        val range = (maxY - minY).coerceAtLeast(1)
        collapsedVerticalPositionFraction = ((params.y - minY).toFloat() / range).coerceIn(0f, 1f)
        val fraction = collapsedVerticalPositionFraction
        serviceScope.launch(Dispatchers.IO) {
            try {
                settings.saveQuickCaptureOverlayVerticalPositionFraction(fraction)
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (e: Exception) {
                Logger.w(TAG, "save quick capture overlay position failed: ${e.message}", e)
            }
        }
    }

    private fun screenBounds(): Rect {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            Rect(0, 0, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        }
    }

    private fun roundedBackground(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    /** 应用内侧滑把手的原生悬浮窗版本:右侧贴边,左侧圆角,颜色来自当前 Muse 主题。 */
    private fun edgeHandleBackground(colors: QuickCaptureThemeColors): GradientDrawable =
        GradientDrawable().apply {
            setColor(withAlpha(colors.primary, 52))
            setCornerRadii(
                floatArrayOf(
                    dp(14).toFloat(), dp(14).toFloat(),
                    0f, 0f,
                    0f, 0f,
                    dp(14).toFloat(), dp(14).toFloat(),
                ),
            )
            setStroke(dp(1), withAlpha(colors.primary, 64))
        }

    private fun currentThemeColors(): QuickCaptureThemeColors =
        themeColors ?: QuickCaptureThemeColors.fallback(applicationContext)

    /** 主题切换时更新当前 View,不重建窗口,避免输入内容和拖动位置丢失。 */
    private fun applyThemeColors() {
        val colors = currentThemeColors()
        collapsedButton?.apply {
            imageTintList = ColorStateList.valueOf(colors.primary)
            background = edgeHandleBackground(colors)
        }
        expandedPanel?.background = roundedBackground(colors.surface, dp(22))
        expandedTitle?.setTextColor(colors.onSurface)
        expandedClose?.imageTintList = ColorStateList.valueOf(colors.onSurface)
        expandedInput?.apply {
            setHintTextColor(withAlpha(colors.onSurfaceVariant, 0xB0))
            setTextColor(colors.onSurface)
            background = roundedBackground(colors.surfaceVariant, dp(16))
        }
        expandedSave?.apply {
            setTextColor(colors.onPrimary)
            background = roundedBackground(colors.primary, dp(18))
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun buildNotification(): Notification {
        val channelId = "quick_capture_overlay"
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    getString(R.string.quick_notes_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        val pending = MuseNotificationManager(this).buildMainActivityPendingIntent(
            MuseNotificationTarget.QuickNotes,
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_input_add)
            .setContentTitle(getString(R.string.quick_notes_title))
            .setContentText(getString(R.string.quick_notes_title))
            .setOngoing(true)
            .setContentIntent(pending)
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        removeOverlay()
        themeWatchJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "QuickCaptureOverlay"
        private const val NOTIFICATION_ID = 2701

        fun start(context: Context) {
            val intent = Intent(context, QuickCaptureOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuickCaptureOverlayService::class.java))
        }
    }
}
