package io.zer0.muse.ui.theme

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * 动画偏好工具(A3/H1 reduced-motion 适配)。
 *
 * 用户关闭系统动画(开发者选项 → 关闭动画, ANIMATOR_DURATION_SCALE=0)时,
 * 流式消息段级淡入等动画应降级为立即显示,尊重系统无障碍偏好
 * (对应总计划书 前端专项 H1 "含 reduced-motion 降级")。
 */
object MuseMotion {
    /**
     * 是否处于 reduced-motion 状态(系统动画时长缩放为 0)。
     * 结果按 context 缓存,会话内稳定,不会在重组间抖动。
     */
    @Composable
    fun isReducedMotion(): Boolean {
        val context = LocalContext.current
        return remember(context) {
            try {
                Settings.Global.getFloat(
                    context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f,
                ) == 0f
            } catch (e: SecurityException) {
                // 个别定制 ROM 拒绝读取系统设置;按非降级处理(不强制关动画)并记录,便于排查
                android.util.Log.w("MuseMotion", "read animator scale denied", e)
                false
            }
        }
    }
}
