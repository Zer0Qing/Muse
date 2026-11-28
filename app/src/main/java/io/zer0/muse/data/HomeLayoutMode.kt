package io.zer0.muse.data

/**
 * 首页主布局形态。
 *
 * - [CLASSIC]: 经典三页（顶部胶囊在「任务 / Agent / 群聊」之间切换，保留原有操作习惯）。
 * - [DRAWER]: 极简抽屉（打开即进入主对话画布，左侧抽屉聚合会话搜索、模式切换与资源工具入口）。
 */
enum class HomeLayoutMode(val wireValue: String) {
    CLASSIC("classic"),
    DRAWER("drawer"),
    ;

    companion object {
        fun fromWire(raw: String?): HomeLayoutMode = entries.firstOrNull { it.wireValue == raw } ?: CLASSIC
    }
}
