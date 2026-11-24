package io.zer0.muse.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.runtime.RuntimeInstallStatus
import io.zer0.muse.runtime.RuntimeInstaller
import io.zer0.muse.runtime.RuntimeState
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes

/**
 * 会话内的运行环境安装卡。
 *
 * 只在检测到工具返回 runtime_missing 时由 ChatScreen 插入，不占消息流位置：
 * 它属于「当前这次操作的前置条件」，跟审批卡一样贴在输入栏上方。
 * 安装进度来自 [RuntimeInstallStatus]，与前台服务同源。
 */
@Composable
fun RuntimeInstallBanner(
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    collapsed: Boolean = false,
) {
    val context = LocalContext.current
    val progress by RuntimeInstallStatus.progress.collectAsStateWithLifecycle()
    val state by RuntimeInstallStatus.state.collectAsStateWithLifecycle()
    val error by RuntimeInstallStatus.lastError.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        RuntimeInstallStatus.refresh(context)
    }

    if (collapsed && progress == null && state !is RuntimeState.Ready) {
        Surface(
            color = scheme.surfaceVariant.copy(alpha = 0.72f),
            shape = MuseShapes.medium,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Icon(
                    imageVector = io.zer0.muse.ui.common.icons.MuseIcons.terminal,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.runtime_card_title),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.runtime_card_install),
                    onClick = onInstall,
                    variant = IosCapsuleButtonVariant.Text,
                    size = MuseButtonSize.Compact,
                    fillWidth = false,
                )
            }
        }
        return
    }

    Surface(
        color = scheme.surfaceVariant.copy(alpha = 0.86f),
        shape = MuseShapes.medium,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = 2.dp),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = when {
                        state is RuntimeState.Ready -> stringResource(R.string.runtime_notif_done)
                        progress != null -> stringResource(R.string.runtime_notif_installing)
                        else -> stringResource(R.string.runtime_card_title)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.runtime_card_later),
                    onClick = onDismiss,
                    variant = IosCapsuleButtonVariant.Text,
                    size = MuseButtonSize.Compact,
                    fillWidth = false,
                )
            }

            if (state is RuntimeState.Ready) {
                Text(
                    text = stringResource(R.string.runtime_notif_done),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.runtime_card_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                val downloading = progress as? RuntimeInstaller.Progress.Downloading
                if (downloading != null && downloading.total > 0) {
                    val fraction = (downloading.downloaded.toFloat() / downloading.total.toFloat()).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${((fraction * 100).toInt())}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                } else if (progress != null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error?.let { message ->
                    Text(
                        text = stringResource(R.string.runtime_card_failed_detail, message),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                MuseCapsuleButton(
                    text = stringResource(R.string.runtime_card_install),
                    onClick = onInstall,
                    size = MuseButtonSize.Compact,
                    fillWidth = false,
                )
            }
        }
    }
}