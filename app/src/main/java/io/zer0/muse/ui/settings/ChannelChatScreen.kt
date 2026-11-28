package io.zer0.muse.ui.settings

import androidx.compose.runtime.Composable
import io.zer0.muse.ui.channel.ChannelTakeoverChatScreen

/**
 * 单个联系人的渠道会话流与人工接管台（已升级至 [ChannelTakeoverChatScreen]）。
 */
@Composable
fun ChannelChatScreen(channelId: String, from: String, onBack: () -> Unit) {
    ChannelTakeoverChatScreen(
        channelId = channelId,
        from = from,
        onBack = onBack,
    )
}
