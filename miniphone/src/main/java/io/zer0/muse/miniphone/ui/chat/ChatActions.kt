package io.zer0.muse.miniphone.ui.chat

import androidx.compose.runtime.Immutable

/**
 * 输入栏要的三个回调。
 *
 * 合成一个值随身携带:聊天页的参数表里大半都是「某块区域要的回调」,
 * 按区域分组之后,读的人一眼能看出这几个是同一件事的出口。
 *
 * @param onSend 发一条文本
 * @param onPickImages 拉起系统图片选择器
 * @param onTranscribeVoice 把录音转成文字;转不了返回 null
 */
@Immutable
data class ChatInputActions(
    val onSend: (String) -> Unit = {},
    val onPickImages: () -> Unit = {},
    val onTranscribeVoice: suspend (ByteArray) -> String? = { null },
)

/**
 * 单条消息上的两个操作:重发与删除。
 *
 * 参数都是消息 id —— 界面不知道这条消息该走哪条链路,只知道用户点了哪一条。
 */
@Immutable
data class ChatMessageActions(
    val onResend: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
)
