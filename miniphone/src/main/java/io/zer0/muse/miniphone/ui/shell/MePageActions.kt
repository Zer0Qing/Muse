package io.zer0.muse.miniphone.ui.shell

import androidx.compose.runtime.Immutable

/**
 * 我页的四个动作回调。
 *
 * 聚成一份而不是逐个当参数:两个开关加两个入口已经四项,散着传会让参数表比内容还长。
 */
@Immutable
internal class MePageActions(
    val onToggleAutoMoment: (Boolean) -> Unit = {},
    val onToggleChatProactive: (Boolean) -> Unit = {},
    val onOpenMyMoments: () -> Unit = {},
    val onClearConversations: () -> Unit = {},
)
