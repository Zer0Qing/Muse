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
    /**
     * 本次读取**不完整**的原因；`null` 表示读全了。
     *
     * 为什么要有这个字段：控件树遍历会中途抛（系统回收节点）、也会受深度上限影响，
     * 以前这两种情况都只写日志，`ScreenInfo` 里看起来就是一次正常读取。
     * 模型于是把"没读到"当成"屏幕上没有" —— 结果是找不到控件、误点、或者报告"界面没有该按钮"。
     * 残缺必须写在数据里，让看数据的人（和模型）第一眼就看到。
     */
    val incompleteReason: String? = null,
) {
    /**
     * 在控件树中选择最有可能代表用户目标的节点。
     * 评分优先级：可用性 → 精确匹配 → 前缀/包含匹配 → 可点击性 → 文本字段。
     * 这样视觉 Agent 和语义工作流不会因树遍历顺序变化而随机点击同名装饰文本。
     */
    fun findBestTextNode(query: String, exact: Boolean = false): UiNode? {
        val normalized = query.trim()
        if (normalized.isEmpty()) return null
        return nodes.asSequence()
            .filter { it.isEnabled }
            .mapNotNull { node ->
                val text = node.text?.trim()?.takeIf { it.isNotEmpty() }
                val description = node.contentDescription?.trim()?.takeIf { it.isNotEmpty() }
                val label = text ?: description ?: return@mapNotNull null
                val normalizedLabel = label.trim()
                val matches = if (exact) {
                    normalizedLabel == normalized
                } else {
                    normalizedLabel.equals(normalized, ignoreCase = true) ||
                        normalizedLabel.startsWith(normalized, ignoreCase = true) ||
                        normalizedLabel.contains(normalized, ignoreCase = true)
                }
                if (!matches) return@mapNotNull null
                val matchScore = when {
                    normalizedLabel == normalized -> 1_000
                    normalizedLabel.equals(normalized, ignoreCase = true) -> 900
                    normalizedLabel.startsWith(normalized, ignoreCase = true) -> 700
                    else -> 500
                }
                val fieldScore = if (text != null) 30 else 0
                val interactionScore = when {
                    node.isClickable -> 120
                    node.isEditable -> 60
                    else -> 0
                }
                node to (matchScore + interactionScore + fieldScore)
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    /** Choose a stable resource-id target, accepting either the full id or the suffix after '/'. */
    fun findBestViewIdNode(viewId: String): UiNode? {
        val query = viewId.trim().removePrefix("@").removePrefix("id/")
        if (query.isEmpty()) return null
        return nodes.asSequence()
            .filter { it.isEnabled }
            .mapNotNull { node ->
                val candidate = node.viewIdResourceName?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val suffix = candidate.substringAfterLast('/')
                if (candidate != query && suffix != query && candidate != "id/$query") return@mapNotNull null
                val score = (if (candidate == query) 1_000 else 900) +
                    (if (node.isClickable) 120 else if (node.isEditable) 60 else 0)
                node to score
            }
            .sortedByDescending { it.second }
            .firstOrNull()
            ?.first
    }

    /** 生成给 AI 阅读的紧凑文本摘要。 */
    fun toSummary(maxNodes: Int = 40): String = buildString {
        appendLine("当前应用: ${packageName ?: "未知"}")
        if (!activityName.isNullOrBlank()) appendLine("界面: $activityName")
        appendLine("分辨率: ${screenWidth}x$screenHeight")
        appendLine("控件数: ${nodes.size}")
        // 残缺要排在控件清单**之前**：模型读完前半截就可能已经决定点哪儿了，
        // 提示放在末尾等于没放。
        incompleteReason?.let { appendLine("⚠ 本次读取不完整：$it") }
        appendLine("---")
        nodes.take(maxNodes).forEachIndexed { i, n ->
            appendLine("[$i] ${n.toShortString()}")
        }
        if (nodes.size > maxNodes) appendLine("... 还有 ${nodes.size - maxNodes} 个节点")
    }
}

/**
 * 合成「本次屏幕读取是否残缺」的原因（纯函数，供各读取实现复用与单测）。
 *
 * @param depthTruncated 是否因为超过 [maxDepth] 层而丢掉了更深的控件
 * @param error 遍历中途抛出的异常描述（null 表示没抛）
 * @param collected 实际收集到的控件数
 */
internal fun screenReadIssue(
    depthTruncated: Boolean,
    error: String?,
    collected: Int,
    maxDepth: Int,
): String? = buildList {
    if (error != null) {
        add("控件树遍历中断（$error），只拿到中断前的 $collected 个控件")
    }
    if (depthTruncated) {
        add("层级超过 $maxDepth 层，更深的控件没有读到")
    }
}.joinToString("；").takeIf { it.isNotEmpty() }

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
        val bounds = "($centerX,$centerY)"
        return "$label$tagStr $bounds"
    }
}
