package io.zer0.muse.ui.agentbot

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentTeachRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 示范录制的悬浮控制球与前台通知降级。
 *
 * 有悬浮窗权限时显示一条紧凑胶囊（步数 / 暂停 / 完成）；没有权限时把同一组操作
 * 放进常驻通知。两者都只操作 [AgentTeachRecorder] 的公开状态，不额外复制录制逻辑。
 *
 * 暂停通过“快照 + 取消录制 + 恢复时重放已录步骤”实现：录制器本身不新增暂停态，
 * 从而避免影响已经接好的无障碍事件入口。
 */
class TeachOverlayController(
    private val context: Context,
    private val recorder: AgentTeachRecorder,
    private val onFinishRequested: () -> Unit,
) {

    enum class Presentation {
        NONE,
        OVERLAY,
        NOTIFICATION,
    }

    data class UiState(
        val active: Boolean = false,
        val paused: Boolean = false,
        val stepCount: Int = 0,
        val presentation: Presentation = Presentation.NONE,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val notificationManager = NotificationManagerCompat.from(context)

    private var overlayView: View? = null
    private var stepLabel: TextView? = null
    private var pauseButton: TextView? = null
    private var pausedSnapshot: AgentTeachRecorder.State? = null
    private var receiverRegistered = false

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(receiverContext: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_TOGGLE_PAUSE -> togglePause()
                ACTION_FINISH -> onFinishRequested()
                else -> Unit
            }
        }
    }

    init {
        registerActionReceiver()
        scope.launch {
            recorder.state.collect { recorded -> onRecorderState(recorded) }
        }
    }

    fun canShowOverlay(): Boolean = Settings.canDrawOverlays(context)

    fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { error ->
                Logger.w(TAG, "打开悬浮窗权限设置失败: ${error.message}", error)
            }
    }

    /** 工作台按钮与通知按钮共用：暂停时保留已录步骤，恢复时重放。 */
    fun togglePause() {
        val snapshot = pausedSnapshot
        if (snapshot == null) {
            val current = recorder.state.value
            if (!current.recording) return
            pausedSnapshot = current
            recorder.cancel()
            _state.value = UiState(
                active = true,
                paused = true,
                stepCount = current.steps.size,
                presentation = _state.value.presentation,
            )
            renderCurrent()
            return
        }
        restoreSnapshot(snapshot)
        pausedSnapshot = null
        _state.value = UiState(
            active = true,
            paused = false,
            stepCount = snapshot.steps.size,
            presentation = _state.value.presentation,
        )
        renderCurrent()
    }

    /** 结束录制并返回草稿；暂停中结束会先恢复步骤再交给录制器收尾。 */
    fun finish(): AgentTeachRecorder.FinishedDraft? {
        val snapshot = pausedSnapshot
        if (snapshot != null) {
            restoreSnapshot(snapshot)
            pausedSnapshot = null
        }
        val draft = recorder.finish()
        hideVisuals()
        _state.value = UiState()
        return draft
    }

    fun cancel() {
        pausedSnapshot = null
        recorder.cancel()
        hideVisuals()
        _state.value = UiState()
    }

    fun release() {
        cancel()
        if (receiverRegistered) {
            runCatching { context.unregisterReceiver(actionReceiver) }
                .onFailure { error -> Logger.w(TAG, "注销示范录制通知接收器失败: ${error.message}", error) }
            receiverRegistered = false
        }
        scope.cancel()
    }

    private fun onRecorderState(recorded: AgentTeachRecorder.State) {
        if (recorded.recording) {
            pausedSnapshot = null
            _state.value = UiState(
                active = true,
                paused = false,
                stepCount = recorded.steps.size,
                presentation = preferredPresentation(),
            )
            renderCurrent()
            return
        }
        val snapshot = pausedSnapshot
        if (snapshot != null) {
            _state.value = UiState(
                active = true,
                paused = true,
                stepCount = snapshot.steps.size,
                presentation = preferredPresentation(),
            )
            renderCurrent()
            return
        }
        hideVisuals()
        _state.value = UiState()
    }

    private fun restoreSnapshot(snapshot: AgentTeachRecorder.State) {
        recorder.start(snapshot.botId, snapshot.titleDraft)
        snapshot.steps.forEach { step -> recorder.recordStep(step.action, step.targetApp) }
    }

    private fun preferredPresentation(): Presentation = when {
        canShowOverlay() -> Presentation.OVERLAY
        notificationsAvailable() -> Presentation.NOTIFICATION
        else -> Presentation.NONE
    }

    private fun notificationsAvailable(): Boolean = notificationManager.areNotificationsEnabled()

    private fun renderCurrent() {
        val current = _state.value
        if (!current.active) {
            hideVisuals()
            return
        }
        if (canShowOverlay()) {
            val overlayReady = if (overlayView != null) {
                updateOverlay(current)
            } else {
                showOverlay(current)
            }
            if (overlayReady) {
                if (_state.value.presentation != Presentation.OVERLAY) {
                    _state.value = _state.value.copy(presentation = Presentation.OVERLAY)
                }
                notificationManager.cancel(NOTIFICATION_ID)
                return
            }
        }
        removeOverlay()
        if (notificationsAvailable()) {
            showNotification(current)
            if (_state.value.presentation != Presentation.NOTIFICATION) {
                _state.value = _state.value.copy(presentation = Presentation.NOTIFICATION)
            }
        } else {
            hideVisuals()
            if (_state.value.presentation != Presentation.NONE) {
                _state.value = _state.value.copy(presentation = Presentation.NONE)
            }
        }
    }

    private fun showOverlay(current: UiState): Boolean {
        if (overlayView != null) return true
        return runCatching {
            val capsule = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(7), dp(8), dp(7))
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(themeColor(android.R.attr.colorPrimary))
                }
                elevation = dp(10).toFloat()
            }
            val label = TextView(context).apply {
                text = context.getString(R.string.agent_hub_teach_recording, current.stepCount)
                setTextColor(android.graphics.Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
            }
            stepLabel = label
            capsule.addView(label, LinearLayout.LayoutParams(0, dp(36), 1f))
            val pause = controlButton(
                text = pauseLabel(current.paused),
                contentDescription = pauseLabel(current.paused),
                onClick = ::togglePause,
            )
            pauseButton = pause
            capsule.addView(pause, LinearLayout.LayoutParams(dp(58), dp(36)))
            val finish = controlButton(
                text = context.getString(R.string.agent_hub_teach_finish),
                contentDescription = context.getString(R.string.agent_hub_teach_finish),
                onClick = onFinishRequested,
            )
            capsule.addView(finish, LinearLayout.LayoutParams(dp(62), dp(36)))
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(48)
            }
            windowManager.addView(capsule, params)
            overlayView = capsule
            true
        }.onFailure { error ->
            Logger.w(TAG, "显示示范录制悬浮球失败: ${error.message}", error)
        }.getOrDefault(false)
    }

    private fun updateOverlay(current: UiState): Boolean {
        val view = overlayView ?: return false
        stepLabel?.text = context.getString(R.string.agent_hub_teach_recording, current.stepCount)
        pauseButton?.text = pauseLabel(current.paused)
        pauseButton?.contentDescription = pauseLabel(current.paused)
        return view.isAttachedToWindow
    }

    private fun showNotification(current: UiState) {
        ensureChannel()
        val toggle = PendingIntent.getBroadcast(
            context,
            REQUEST_TOGGLE,
            Intent(ACTION_TOGGLE_PAUSE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val finish = PendingIntent.getBroadcast(
            context,
            REQUEST_FINISH,
            Intent(ACTION_FINISH).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(context.getString(R.string.agent_hub_quick_teach))
            .setContentText(context.getString(R.string.agent_hub_teach_recording, current.stepCount))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .addAction(0, pauseLabel(current.paused), toggle)
            .addAction(0, context.getString(R.string.agent_hub_teach_finish), finish)
            .build()
        runCatching { notificationManager.notify(NOTIFICATION_ID, notification) }
            .onFailure { error -> Logger.w(TAG, "显示示范录制通知失败: ${error.message}", error) }
    }

    private fun hideVisuals() {
        removeOverlay()
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun removeOverlay() {
        val view = overlayView ?: return
        runCatching { windowManager.removeView(view) }
            .onFailure { error -> Logger.w(TAG, "移除示范录制悬浮球失败: ${error.message}", error) }
        overlayView = null
        stepLabel = null
        pauseButton = null
    }

    private fun controlButton(text: String, contentDescription: String, onClick: () -> Unit): TextView =
        TextView(context).apply {
            this.text = text
            this.contentDescription = contentDescription
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setOnClickListener { onClick() }
        }

    private fun pauseLabel(paused: Boolean): String = context.getString(
        if (paused) R.string.speech_resume_cd else R.string.speech_pause_cd,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.agent_hub_quick_teach),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.agent_hub_teach_recording, 0)
                setShowBadge(false)
            },
        )
    }

    private fun registerActionReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_TOGGLE_PAUSE)
            addAction(ACTION_FINISH)
        }
        receiverRegistered = runCatching {
            ContextCompat.registerReceiver(
                context,
                actionReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }.onFailure { error ->
            Logger.w(TAG, "注册示范录制通知接收器失败: ${error.message}", error)
        }.isSuccess
    }

    private fun overlayType(): Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

    private fun themeColor(attr: Int, fallback: Int = android.graphics.Color.BLACK): Int {
        val value = TypedValue()
        return if (context.theme.resolveAttribute(attr, value, true)) {
            if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                value.data
            } else {
                fallback
            }
        } else {
            fallback
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "TeachOverlayController"
        private const val CHANNEL_ID = "teach_recording"
        private const val NOTIFICATION_ID = 7402
        private const val REQUEST_TOGGLE = 7403
        private const val REQUEST_FINISH = 7404
        private const val ACTION_TOGGLE_PAUSE = "io.zer0.muse.action.TEACH_TOGGLE_PAUSE"
        private const val ACTION_FINISH = "io.zer0.muse.action.TEACH_FINISH"
    }
}