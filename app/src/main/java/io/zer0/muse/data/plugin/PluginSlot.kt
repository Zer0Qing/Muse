package io.zer0.muse.data.plugin

import kotlinx.serialization.Serializable

/**
 * 界面槽位（`ui.slot`）：插件可以把自己的界面片段放进宿主的指定位置。
 *
 * 为什么是"槽位"而不是"任意布局替换"：
 *
 *  - 任意替换会让宿主界面变成插件代码的形状，一次升级就可能整屏崩掉，
 *    而且用户报"界面坏了"时我们无法判断是谁弄坏的；
 *  - 槽位是**宿主定义的空位**，位置、宽度、滚动行为都由宿主决定，插件只填内容。
 *    这样插件能改的东西足够多（聊天页顶部、消息下方、设置页分组），
 *    但宿主仍然能保证"最坏情况下只是某个槽位不显示"。
 *
 * 新增槽位必须同时在这里登记：宿主没实现的槽位名会让插件"贡献成功但永远看不到"，
 * 那是最难查的一类失败，所以宁可让未登记的槽位在校验阶段就被拒。
 */
object PluginSlot {

    /** 一个槽位最多渲染几个插件的片段：再多会把页面挤满，也会拖慢首屏。 */
    const val MAX_SLOT_PLUGINS = 3

    /** 当前宿主**真的会渲染**的槽位。 */
    enum class Id(val wire: String) {
        /** 聊天页顶部（消息列表上方）。 */
        CHAT_TOP("chat.top"),
        ;

        companion object {
            fun parse(raw: String?): Id? = entries.firstOrNull { it.wire == raw?.trim() }
        }
    }

    /** 校验一条贡献：槽位名必须是已登记的，界面文件必须非空。 */
    fun validate(contribution: SlotContribution): String? {
        if (Id.parse(contribution.slot) == null) {
            return "未知槽位 '${contribution.slot}'；可用：" +
                Id.entries.joinToString("、") { it.wire }
        }
        if (contribution.view.isBlank()) return "槽位 '${contribution.slot}' 没有指定界面文件"
        return null
    }

    /** 全部可用槽位名（给作者文档与错误提示用）。 */
    fun availableNames(): List<String> = Id.entries.map { it.wire }
}

/**
 * 一条槽位贡献：把包内哪个声明式界面文件放进哪个槽位。
 *
 * 声明式界面（`uiView` 那套 JSON）而不是 HTML：槽位嵌在宿主页面里，
 * HTML 会带来一次 WebView 初始化与一层独立的滚动，放在列表顶部会明显卡顿。
 */
@Serializable
data class SlotContribution(
    /** 槽位名，见 [PluginSlot.Id]。 */
    val slot: String,
    /** 声明式界面文件（相对插件包根，如 `ui/chat-top.json`）。 */
    val view: String,
)
