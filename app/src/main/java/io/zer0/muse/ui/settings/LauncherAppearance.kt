package io.zer0.muse.ui.settings

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import io.zer0.common.Logger

/**
 * v2.6.9: 启动器外观（显示名 + 图标）切换。
 *
 * 用户需求：不想被人一眼看出在用 AI 伴侣。这是合理的隐私诉求，
 * Android 的正规做法是 activity-alias：每个别名可有自己的 label/icon，
 * 通过 PackageManager.setComponentEnabledSetting 启用其中一个。
 *
 * **这不是伪装**：
 *  - 包名不变（仍是 io.zer0.muse），系统设置里看到的仍是真实应用
 *  - 别名只用中性词汇（笔记 / 备忘录 / 学习记录），不仿冒任何第三方应用
 *  - 不隐藏图标、不试图绕过任何系统限制
 *
 * 已知行为（必须让用户知道）：
 *  - 切换别名会**让桌面上的图标消失再重新出现**，部分 ROM 需要几秒
 *  - 已放到桌面的旧图标可能变成「应用已卸载」的空白入口，需要手动删除
 *  - 切换后长按快捷方式（shortcuts）会短暂失效
 */
object LauncherAppearance {

    private const val TAG = "LauncherAppearance"

    /** 支持的启动器外观。每个对应 manifest 里一个 activity-alias。 */
    enum class Style(
        val aliasClass: String,
        /** 显示名资源。用资源而非硬编码，七语言才能跟随切换。 */
        @androidx.annotation.StringRes val labelRes: Int,
    ) {
        MUSE("io.zer0.muse.LauncherDefault", io.zer0.muse.R.string.launcher_alias_muse),
        NOTES("io.zer0.muse.LauncherNotes", io.zer0.muse.R.string.launcher_alias_notes),
        MEMO("io.zer0.muse.LauncherMemo", io.zer0.muse.R.string.launcher_alias_memo),
        STUDY("io.zer0.muse.LauncherStudy", io.zer0.muse.R.string.launcher_alias_study),
        ;

        companion object {
            fun fromAlias(alias: String?): Style = entries.firstOrNull { it.aliasClass == alias } ?: MUSE
        }
    }

    /**
     * 切换到指定外观。
     *
     * 顺序很重要：**先启用新的，再禁用旧的**？
     * 不——会短暂出现两个图标。正确做法是先禁用全部其它别名，再启用目标别名。
     * 这样最坏情况是短暂「没有图标」，不会出现两个同名 App。
     */
    fun apply(context: Context, target: Style): Boolean {
        val pm = context.packageManager
        return runCatching {
            Style.entries.forEach { style ->
                val component = ComponentName(context.packageName, style.aliasClass)
                val state = if (style == target) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                }
                pm.setComponentEnabledSetting(
                    component,
                    state,
                    PackageManager.DONT_KILL_APP,
                )
            }
            Logger.i(TAG, "启动器外观已切换为 ${target.name}")
            true
        }.onFailure { t ->
            Logger.w(TAG, "切换启动器外观失败: ${t.message}", t)
        }.getOrDefault(false)
    }

    /** 读取当前启用的外观。 */
    fun current(context: Context): Style {
        val pm = context.packageManager
        return Style.entries.firstOrNull { style ->
            val component = ComponentName(context.packageName, style.aliasClass)
            pm.getComponentEnabledSetting(component) ==
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: Style.MUSE
    }

    /** 读取当前外观的原始 alias 字符串（持久化用，不依赖读取时系统状态）。 */
    fun currentAlias(context: Context): String = current(context).aliasClass
}
