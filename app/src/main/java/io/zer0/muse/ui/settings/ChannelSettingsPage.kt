package io.zer0.muse.ui.settings

import androidx.compose.runtime.Composable
import io.zer0.muse.ui.channel.ChannelHubScreen

/**
 * 消息渠道入口页（已全面重构至 [ChannelHubScreen]）。
 *
 * 保留既有路由入口签名，兼容设置直达与连接中心旧导航。
 */
@Composable
fun ChannelSettingsScreen(
    onBack: () -> Unit,
    onOpenConversations: (String) -> Unit = {},
    onOpenChat: (String, String) -> Unit = { _, _ -> },
) {
    ChannelHubScreen(
        onBack = onBack,
        onOpenConversations = onOpenConversations,
        onOpenChat = onOpenChat,
    )
}
