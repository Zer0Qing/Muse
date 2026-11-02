package io.zer0.muse.ui.settings

import android.content.Context
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginCapabilityGateway

/**
 * 能力的**人话表述**：权限面板、安装确认页共用这一份表。
 *
 * 为什么必须只有一份：这两处以前都在直接显示能力标识符（`fs.read`、`host.model.call`）。
 * 用户批准的是"名字"，不是"这会发生什么" —— 那种授权没有信息量，
 * 点下开关的人其实不知道自己答应了读聊天记录还是只是发通知。
 *
 * 两处各写一份的结局一定是一处过期、一处吓人，所以这里同时提供：
 *  - [text]：一句后果说明；
 *  - [isSensitive]：隐私/成本面要在视觉上区别对待。
 */
object PluginCapabilityCopy {

    /** 能力 → 一句后果说明。缺项会回落显示原始标识符（不显示空白，也不说"未知"）。 */
    private val RES: Map<String, Int> = mapOf(
        "resource.read" to R.string.plugin_cap_resource_read,
        "resource.write" to R.string.plugin_cap_resource_write,
        "network" to R.string.plugin_cap_network,
        "clipboard.read" to R.string.plugin_cap_clipboard_read,
        "clipboard.write" to R.string.plugin_cap_clipboard_write,
        "clipboard" to R.string.plugin_cap_clipboard,
        "notify" to R.string.plugin_cap_notify,
        "device.info" to R.string.plugin_cap_device_info,
        "storage" to R.string.plugin_cap_storage,
        "config.write" to R.string.plugin_cap_config_write,
        "host.tool.invoke" to R.string.plugin_cap_tool_invoke,
        "chat.read" to R.string.plugin_cap_chat_read,
        "host.model.call" to R.string.plugin_cap_model_call,
        "ui.skin" to R.string.plugin_cap_ui_skin,
        PluginCapabilityGateway.UI_INTERACTIVE to R.string.plugin_cap_ui_interactive,
        PluginCapabilityGateway.RUNTIME_PYTHON to R.string.plugin_cap_runtime_python,
        PluginCapabilityGateway.EVENT_ON_MESSAGE to R.string.plugin_cap_event_on_message,
        PluginCapabilityGateway.EVENT_ON_TOOL_CALL to R.string.plugin_cap_event_on_tool_call,
    )

    /**
     * 隐私面、成本面与**会改数据**的面：批准前必须看得见代价。
     *
     * 把"读文件"和"读聊天记录"画成同一视觉权重，等于让用户用同一个手势
     * 签下两件完全不同的事 —— 这不是节省空间，是把授权变成橡皮图章。
     */
    val SENSITIVE: Set<String> = setOf(
        "chat.read",
        "host.model.call",
        PluginCapabilityGateway.UI_INTERACTIVE,
        PluginCapabilityGateway.EVENT_ON_MESSAGE,
        PluginCapabilityGateway.EVENT_ON_TOOL_CALL,
        PluginCapabilityGateway.RUNTIME_PYTHON,
    )

    /**
     * 一句人话：**先归一化再查表**。
     *
     * 顺序很重要：`storage.read` / `storage.write` 是 `resource.*`（文件访问）的同义拼写，
     * 不是 KV 存储那个能力。如果按原名优先命中一条"保存插件自己的记录"式文案，
     * 用户批准的是"记状态"，实际拿到的却是"读写目录里的文件" —— 这是**在授权面上撒谎**。
     */
    fun text(context: Context, capability: String): String {
        val id = RES[canonical(capability)] ?: RES[capability]
        return id?.let { context.getString(it) } ?: capability
    }

    fun isSensitive(capability: String): Boolean =
        capability in SENSITIVE || canonical(capability) in SENSITIVE

    /** 是否有可读表述（测试用来断言"覆盖齐了"，不是用来给 UI 判空的）。 */
    fun hasCopy(capability: String): Boolean =
        RES.containsKey(canonical(capability)) || RES.containsKey(capability)

    private fun canonical(capability: String): String =
        PluginCapabilityGateway.canonicalCapabilities(listOf(capability)).firstOrNull() ?: capability
}
