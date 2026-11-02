package io.zer0.muse.ui.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.zer0.common.Logger
import io.zer0.muse.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * 朗读期间的处置动作。
 *
 * 由音频焦点变化推出来 —— 朗读最怕的不是"声音小"，是**来电时还在念**。
 */
enum class ReadAloudAction { CONTINUE, PAUSE, STOP }

/**
 * 音频焦点 → 处置。
 *
 * `CAN_DUCK`（别人要短暂播提示音）也按暂停处理：语音被压低到 20% 之后基本听不清，
 * 与其让用户听一串糊掉的字，不如暂停、恢复焦点后再继续。
 */
object ReadAloudFocusPolicy {
    fun onFocusChange(focusChange: Int): ReadAloudAction = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN -> ReadAloudAction.CONTINUE
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK,
        -> ReadAloudAction.PAUSE
        AudioManager.AUDIOFOCUS_LOSS -> ReadAloudAction.STOP
        else -> ReadAloudAction.CONTINUE
    }
}

/**
 * 服务 → 播放器 的单向信号。
 *
 * 服务负责"占住前台 + 持有音频焦点"，播放本身仍在 [TtsManager]（MediaPlayer 必须在主线程操作）。
 * 焦点变化通过这条信号推给播放器，而不是让服务直接去动 MediaPlayer —— 两边各管一件事。
 */
object ReadAloudControl {

    private val _signals = MutableSharedFlow<ReadAloudAction>(extraBufferCapacity = 8)

    val signals: SharedFlow<ReadAloudAction> = _signals

    fun emit(action: ReadAloudAction) {
        _signals.tryEmit(action)
    }
}

/**
 * 朗读的前台播放服务（对比清单 P1-10）。
 *
 * 解决的问题：`TtsManager` 只用 MediaPlayer + 一个 Main 线程的 scope，
 * 没有前台服务 —— 应用退到后台或锁屏后，进程随时可能被回收，朗读会莫名中断。
 * 这里用 `mediaPlayback` 类型的前台服务占住进程，并接管音频焦点：
 * 来电/其他应用要声音时暂停或停止，而不是继续念。
 */
class ReadAloudPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {

    private var audioManager: AudioManager? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AudioManager::class.java)
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ReadAloudControl.emit(ReadAloudAction.STOP)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        requestFocus()
        return START_NOT_STICKY
    }

    private fun requestFocus() {
        val manager = audioManager ?: return
        val result = runCatching {
            manager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        }.onFailure { Logger.w(TAG, "申请音频焦点失败: ${it.message}") }
            .getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // 拿不到焦点就别硬念：让播放器停下，用户能看出"没声音"是因为被别的东西占着
            ReadAloudControl.emit(ReadAloudAction.STOP)
        }
    }

    override fun onAudioFocusChange(focusChange: Int) {
        ReadAloudControl.emit(ReadAloudFocusPolicy.onFocusChange(focusChange))
    }

    override fun onDestroy() {
        runCatching { audioManager?.abandonAudioFocus(this) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.read_aloud_channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ReadAloudPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.read_aloud_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .addAction(0, getString(R.string.read_aloud_notification_stop), stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "ReadAloudService"
        private const val CHANNEL_ID = "read_aloud"
        private const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "io.zer0.muse.action.READ_ALOUD_STOP"

        /** 朗读开始时调用（幂等：重复调用只是再发一次 startForeground）。 */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReadAloudPlaybackService::class.java)) }
                .onFailure { Logger.w(TAG, "启动朗读服务失败: ${it.message}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ReadAloudPlaybackService::class.java)) }
                .onFailure { Logger.w(TAG, "停止朗读服务失败: ${it.message}") }
        }
    }
}
