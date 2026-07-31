package io.zer0.muse.tools.system

/**
 * P3-3: Android 权限等级 — 三通道权限模型。
 *
 * 等级递增,高级别包含低级别能力:
 *  - [NONE]: 未授权任何通道,无法执行 UI 自动化或 shell 命令
 *  - [ACCESSIBILITY]: 无障碍服务已启用,可执行 UI 操作(点击/滑动/输入/截图/读取 UI 树)
 *  - [SHIZUKU]: Shizuku 已授权(API 23+,以 shell 权限执行命令,无需 root)
 *  - [ROOT]: 设备已 root,可执行任意命令(最高权限)
 *
 * 路由规则([ShellExecutor] / [UIToolsSkill]):
 *  - UI 操作:需 [ACCESSIBILITY] 及以上
 *  - Shell 命令:优先 [SHIZUKU](更安全,无需 root),降级到 [ROOT];[ACCESSIBILITY] 无法执行 shell
 */
enum class AndroidPermissionLevel {
    NONE,
    ACCESSIBILITY,
    SHIZUKU,
    ROOT,;

    /** 当前等级是否达到 [required] 级别。 */
    fun atLeast(required: AndroidPermissionLevel): Boolean = this.ordinal >= required.ordinal

    companion object {
        /** 选取多个通道可用性的最高等级。 */
        fun highestOf(vararg available: Pair<AndroidPermissionLevel, Boolean>): AndroidPermissionLevel {
            var best = NONE
            for ((level, ok) in available) {
                if (ok && level.ordinal > best.ordinal) best = level
            }
            return best
        }
    }
}
