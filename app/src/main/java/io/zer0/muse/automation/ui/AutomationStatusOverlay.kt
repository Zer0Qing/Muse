package io.zer0.muse.automation.ui

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 前台自动化执行时的悬浮状态胶囊 + 急停按钮。
 *
 * 仅在拥有悬浮窗权限（[Settings.canDrawOverlays]）时展示；无权限时静默降级为无界面
 * （与音量下键急停并行，不阻塞自动化主流程）。
 */
internal class AutomationStatusOverlay(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager = runCatching {
        appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    }.getOrNull()
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + GlobalCoroutineExceptionHandler)

    private var rootView: View? = null
    private var statusView: TextView? = null

    fun show(initialText: String, onAbort: () -> Unit) {
        mainScope.launch {
            if (windowManager == null) return@launch
            val canDraw = runCatching { Settings.canDrawOverlays(appContext) }.getOrDefault(false)
            if (!canDraw) return@launch
            if (rootView != null) {
                statusView?.text = initialText
                return@launch
            }
            runCatching {
                val container = LinearLayout(appContext).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    val padH = dp(14)
                    val padV = dp(8)
                    setPadding(padH, padV, padH, padV)
                    background = GradientDrawable().apply {
                        setColor(Color.argb(230, 24, 28, 36))
                        cornerRadius = dp(24).toFloat()
                    }
                }
                val label = TextView(appContext).apply {
                    text = initialText
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                }
                val stopBtn = TextView(appContext).apply {
                    text = appContext.getString(R.string.automation_overlay_stop)
                    setTextColor(Color.WHITE)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    background = GradientDrawable().apply {
                        setColor(Color.rgb(220, 38, 38))
                        cornerRadius = dp(16).toFloat()
                    }
                    setOnClickListener { onAbort() }
                }
                val gap = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(10) }
                container.addView(label, gap)
                container.addView(stopBtn)

                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = dp(48)
                }
                windowManager.addView(container, params)
                rootView = container
                statusView = label
            }.onFailure { err ->
                Logger.w(TAG, "显示自动化悬浮状态条失败: ${err.message}")
            }
        }
    }

    fun update(text: String) {
        mainScope.launch {
            statusView?.text = text
        }
    }

    fun dismiss() {
        mainScope.launch {
            val view = rootView ?: return@launch
            rootView = null
            statusView = null
            runCatching { windowManager?.removeViewImmediate(view) }
                .onFailure { Logger.d(TAG, "移除悬浮状态条失败: ${it.message}") }
        }
    }

    private fun dp(value: Int): Int = (value * appContext.resources.displayMetrics.density).toInt().coerceAtLeast(1)

    private companion object {
        private const val TAG = "AutomationOverlay"
    }
}
