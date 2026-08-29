package io.zer0.muse.automation.core

/**
 * UI 自动化权限层级。
 *
 * 三层梯度对应不同能力范围：
 * - [ACCESSIBILITY]: 无障碍服务,读屏+手势注入,无需连接电脑
 * - [SHELL]: Shizuku/adb shell,系统级命令,静默安装、settings、input 等
 * - [ROOT]: su 权限,完全系统控制
 *
 * 执行器按层级递增注册,动作分发时自动选择满足需求的最低层级(降级策略)。
 */
enum class PermissionLevel {
    /** 无障碍服务。 */
    ACCESSIBILITY,

    /** Shizuku/adb shell。 */
    SHELL,

    /** Root (su)。 */
    ROOT,

    /** 无任何自动化权限。 */
    NONE,
    ;

    /** 该层级是否至少包含 [other] 的能力。 */
    fun covers(other: PermissionLevel): Boolean = rank >= other.rank

    /** 显式能力等级，避免枚举声明顺序改变语义。NONE 没有任何能力。 */
    private val rank: Int
        get() = when (this) {
            NONE -> 0
            ACCESSIBILITY -> 1
            SHELL -> 2
            ROOT -> 3
        }

    companion object {
        /** 从可用标志位组合解析。 */
        fun fromFlags(
            accessibility: Boolean,
            shell: Boolean,
            root: Boolean,
        ): Set<PermissionLevel> = buildSet {
            if (accessibility) add(ACCESSIBILITY)
            if (shell) add(SHELL)
            if (root) add(ROOT)
        }
    }
}
