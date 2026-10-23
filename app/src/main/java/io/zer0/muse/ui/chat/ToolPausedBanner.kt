package io.zer0.muse.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseShapes

/**
 * v2.6.7: 工具执行被全局暂停时的聊天页横幅。
 *
 * 背景（用户反馈）: 打开设置里的「暂停工具执行」后，每个工具都返回
 * "工具 xxx 已暂停，本轮未执行真实操作"、耗时 0ms，一轮下来 12 步全"失败"。
 * 用户视角非常像"联网坏了 / 工具挂了"，而真相是一个设置开关。
 *
 * 这个横幅只做两件事:
 *  1. 说明白现在不会真的执行工具；
 *  2. 给一键恢复出口，不必再翻两层设置。
 *
 * 故意不做成警告色：这是用户自己的选择，不是错误。
 */
@Composable
fun ToolPausedBanner(onResume: () -> Unit = {}, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MuseShapes.medium,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = MuseIcons.pause,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.chat_tool_paused_banner),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = stringResource(R.string.chat_tool_paused_banner_action),
                onClick = onResume,
                fillWidth = false,
                variant = io.zer0.muse.ui.common.form.IosCapsuleButtonVariant.Secondary,
            )
        }
    }
}
