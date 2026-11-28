package io.zer0.muse.data.agentbot

import io.zer0.muse.R

/** 连接器与本机能力健康灯状态。 */
enum class ConnectorHealthStatus { OK, WARN, ERROR, UNKNOWN }

/** 一行健康状态，文案用资源 id，界面按当前语言解析。 */
data class ConnectorHealthItem(
    val key: String,
    val status: ConnectorHealthStatus,
    val titleRes: Int,
    val detailRes: Int,
    val fixable: Boolean = true,
)

/** 工作台「设备与连接状态」的完整快照。 */
data class AgentConnectorHealthSnapshot(
    val items: List<ConnectorHealthItem> = emptyList(),
) {
    fun withMcpReadiness(ready: Boolean): AgentConnectorHealthSnapshot = copy(
        items = items.map { item ->
            if (item.key != "mcp") {
                item
            } else {
                item.copy(
                    status = if (ready) ConnectorHealthStatus.OK else ConnectorHealthStatus.WARN,
                    detailRes = if (ready) R.string.agent_connector_ready else R.string.agent_connector_needs_action,
                )
            }
        },
    )

    val hasIssue: Boolean
        get() = items.any { it.status == ConnectorHealthStatus.WARN || it.status == ConnectorHealthStatus.ERROR }
}

/** 将布尔能力探测结果组装为稳定、可测的健康快照。 */
object AgentConnectorHealthSnapshotFactory {
    fun from(
        prootReady: Boolean,
        accessibilityReady: Boolean,
        virtualDisplayReady: Boolean,
        notificationReady: Boolean,
        calendarReady: Boolean,
        mcpEnabledCount: Int,
        channelCount: Int,
        channelOnlineCount: Int = channelCount,
        channelIssueCount: Int = 0,
    ): AgentConnectorHealthSnapshot {
        fun status(ready: Boolean): ConnectorHealthStatus = if (ready) ConnectorHealthStatus.OK else ConnectorHealthStatus.WARN

        val channelStatus = when {
            channelIssueCount > 0 -> ConnectorHealthStatus.ERROR
            channelOnlineCount > 0 -> ConnectorHealthStatus.OK
            else -> ConnectorHealthStatus.WARN
        }

        return AgentConnectorHealthSnapshot(
            items = listOf(
                item("proot", status(prootReady), R.string.agent_connector_proot, prootReady),
                item("accessibility", status(accessibilityReady), R.string.agent_connector_accessibility, accessibilityReady),
                item("virtual_display", status(virtualDisplayReady), R.string.agent_connector_virtual_display, virtualDisplayReady),
                item("notification", status(notificationReady), R.string.agent_connector_notification, notificationReady),
                item("calendar", status(calendarReady), R.string.agent_connector_calendar, calendarReady),
                item("mcp", status(mcpEnabledCount > 0), R.string.agent_connector_mcp, mcpEnabledCount > 0),
                item("channel", channelStatus, R.string.agent_connector_channel, channelOnlineCount > 0 && channelIssueCount == 0),
            ),
        )
    }

    private fun item(key: String, status: ConnectorHealthStatus, titleRes: Int, ready: Boolean) = ConnectorHealthItem(
        key = key,
        status = status,
        titleRes = titleRes,
        detailRes = if (ready) R.string.agent_connector_ready else R.string.agent_connector_needs_action,
    )
}
