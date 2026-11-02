package io.zer0.muse.data.plugin

/**
 * 插件桥接调用的**近期记录**（插件计划 P4 调试台）。
 *
 * 现在"为什么被拒绝"只写进日志文件，作者得去翻 logcat。而拒绝原因是插件开发中最常见的问题：
 * 能力没声明、声明了没授权、路径越界、动作不在表里……每一种都在同一个地方以不同话术出现。
 *
 * 这里把最近若干次调用留在内存里，界面可以直接列出来 —— **只保留最近 N 条**：
 * 调试信息越积越多会把内存和界面一起拖垮，而作者真正要看的就是刚刚发生了什么。
 */
object PluginBridgeCallLog {

    /** 最多保留多少条。 */
    const val MAX_ENTRIES = 200

    data class Entry(
        val pluginId: String,
        val action: String,
        val allowed: Boolean,
        val detail: String?,
        val atMillis: Long,
    )

    private val entries = ArrayDeque<Entry>()

    /** 记录一次调用。[action] 取不到时写 "(未知)"，仍然记录 —— 至少能看出"发生过一次调用"。 */
    fun record(pluginId: String, action: String?, allowed: Boolean, detail: String?, atMillis: Long) {
        synchronized(entries) {
            entries.addLast(
                Entry(
                    pluginId = pluginId,
                    action = action?.takeIf { it.isNotBlank() } ?: "(未知)",
                    allowed = allowed,
                    detail = detail?.takeIf { it.isNotBlank() },
                    atMillis = atMillis,
                ),
            )
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    /** 最近的记录，最新的在前。 */
    fun recent(limit: Int = 50): List<Entry> = synchronized(entries) {
        entries.takeLast(limit.coerceAtLeast(1)).reversed()
    }

    /** 从桥接请求文本里取动作名；解析不出来返回 null（不抛异常）。 */
    fun actionOf(requestJson: String): String? = runCatching {
        val parsed = io.zer0.common.AppJson.parseToJsonElement(requestJson)
        (parsed as? kotlinx.serialization.json.JsonObject)
            ?.get("action")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            // 动作名必须是字符串：数字/布尔是插件写错了，显示成 "123" 只会误导排查
            ?.takeIf { it.isString }
            ?.content
    }.getOrNull()

    internal fun resetForTests() {
        synchronized(entries) { entries.clear() }
    }
}
