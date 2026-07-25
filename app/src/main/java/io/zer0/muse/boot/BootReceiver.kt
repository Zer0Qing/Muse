package io.zer0.muse.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.MainActivity

/**
 * v0.32: 开机自启动 receiver。
 *
 * 工作机制:
 *  - AndroidManifest 中 [BootReceiver] 的 `android:enabled` 默认为 false,
 *    系统不会向它分发 BOOT_COMPLETED 广播。
 *  - 用户在「设置 → 记忆与通知 → 开机自启动」开启时,[MuseApp] 调用
 *    [PackageManager.setComponentEnabledSetting] 把组件切到 ENABLED,
 *    下次开机系统才会把 BOOT_COMPLETED 投递过来。
 *  - 收到广播后启动 [MainActivity](主入口),不直接拉起 Service
 *    (muse 的 memory ticker / scheduled task runner 都由 MuseApp.onCreate 接管)。
 *
 * 安全:
 *  - 仅监听 BOOT_COMPLETED(系统级广播,需要 RECEIVE_BOOT_COMPLETED 权限)
 *  - 不接收任意第三方 Intent(由 manifest 的 permission 属性保证)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Logger.i(TAG, "BOOT_COMPLETED received, launching MainActivity")
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // M3-1: 改用 resultOf{}
        resultOf { context.startActivity(launchIntent) }
            .onError { msg, _ -> Logger.w(TAG, "launch MainActivity failed: $msg") }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
