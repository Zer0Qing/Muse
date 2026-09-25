package io.zer0.muse.ui.chat

import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.theme.MuseMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R

/**
 * 分支选择器 UI(既有实现 消息分支实现版)。
 *
 * Displays left/right arrows with "1/3" indicator to switch between
 * alternative assistant responses at the same conversation position.
 *
 * 仅在存在多个分支时可见(hasBranches = true)。
 */
@Composable
fun BranchSelector(
    currentIndex: Int,
    totalCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    if (totalCount <= 1) return

    Row(
        modifier = modifier.padding(top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MuseTactileButton(
            icon = MuseIcons.chevronLeft,
            onClick = onPrevious,
            contentDescription = stringResource(R.string.branch_previous),
            enabled = currentIndex > 0,
            size = 48.dp,
            iconSize = 18.dp,
        )

        AnimatedContent(
            targetState = "${currentIndex + 1}/$totalCount",
            transitionSpec = { MuseMotion.fadeEnter() togetherWith MuseMotion.fadeExit() },
            label = "branch-indicator",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        MuseTactileButton(
            icon = MuseIcons.chevronRight,
            onClick = onNext,
            contentDescription = stringResource(R.string.branch_next),
            enabled = currentIndex < totalCount - 1,
            size = 48.dp,
            iconSize = 18.dp,
        )
    }
}
