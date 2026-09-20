package io.zer0.muse.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 头像尺寸三档(CHAT-03:收敛 28/32/40/48dp 四种裸值)。
 *  - [inline]: 消息行内小头像(单聊/群聊消息气泡旁)
 *  - [list]:   列表项头像(会话列表 / 群聊列表)
 *  - [detail]: 详情 / 引导大图(空态问候等)
 */
object MuseAvatarSize {
    val inline: Dp = 28.dp
    val list: Dp = 40.dp
    val detail: Dp = 48.dp
}
