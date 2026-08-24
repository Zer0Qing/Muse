package io.zer0.muse.automation.core

/**
 * 屏幕信息快照。
 *
 * 由无障碍控件树 + dumpsys 摘要合成,供 AI 判断"屏幕上有什么、该点哪里"。
 */
data class ScreenInfo(
    /** 当前前台包名。 */
    val packageName: String? = null,
    /** 当前 Activity 类名(若可取)。 */
    val activityName: String? = null,
    /** 可交互/可读的控件节点列表。 */
    val nodes: List<UiNode> = emptyList(),
    /** 截屏的宽度(像素),用于把归一化坐标换算成绝对坐标。 */
    val screenWidth: Int = 0,
    /** 截屏的高度(像素)。 */
    val screenHeight: Int = 0,
    /** 数据来源标记: accessibility / shell / root。 */
    val source: String = "",
) {
    /** 生成给 AI 阅读的紧凑文本摘要。 */
    fun toSummary(maxNodes: Int = 40): String = buildString {
        appendLine("当前应用: ${packageName ?: "未知"}")
        if (!activityName.isNullOrBlank()) appendLine("界面: $activityName")
        appendLine("分辨率: ${screenWidth}x$screenHeight")
        appendLine("控件数: ${nodes.size}")
        appendLine("---")
        nodes.take(maxNodes).forEachIndexed { i, n ->
            appendLine("[$i] ${n.toShortString()}")
        }
        if (nodes.size > maxNodes) appendLine("... 还有 ${nodes.size - maxNodes} 个节点")
    }
}

/**
 * UI 控件节点(跨层统一表示,屏蔽无障碍/dumpsys 差异)。
 */
data class UiNode(
    val text: String? = null,
    val contentDescription: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    /** 控件左上角 x(绝对像素)。 */
    val boundsLeft: Int = 0,
    val boundsTop: Int = 0,
    /** 控件右下角 x。 */
    val boundsRight: Int = 0,
    val boundsBottom: Int = 0,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isChecked: Boolean? = null,
    val isEnabled: Boolean = true,
) {
    /** 中心 x,供 AI 返回点击目标时换算。 */
    val centerX: Int get() = (boundsLeft + boundsRight) / 2
    val centerY: Int get() = (boundsTop + boundsBottom) / 2

    fun toShortString(): String {
        val label = text?.take(30)
            ?: contentDescription?.take(30)
            ?: viewIdResourceName?.substringAfterLast('/')
            ?: className?.substringAfterLast('.')
            ?: "未命名"
        val tags = buildList {
            if (isClickable) add("可点")
            if (isEditable) add("可输入")
            if (isScrollable) add("可滚动")
            if (isChecked == true) add("已勾选")
            if (!isEnabled) add("禁用")
        }
        val tagStr = if (tags.isEmpty()) "" else " [${tags.joinToString(",")}]"
        val bounds = "(${centerX},${centerY})"
        return "$label$tagStr $bounds"
    }
}
