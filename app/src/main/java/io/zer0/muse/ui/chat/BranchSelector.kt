package io.zer0.muse.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R

/**
 * 分支选择器 UI(RikkaHub 消息分支移植版)。
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
        modifier = modifier.padding(start = 48.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = onPrevious,
            enabled = currentIndex > 0,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.branch_previous),
                modifier = Modifier.size(18.dp),
            )
        }

        AnimatedContent(
            targetState = "${currentIndex + 1}/$totalCount",
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "branch-indicator",
        ) { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = onNext,
            enabled = currentIndex < totalCount - 1,
            modifier = Modifier.size(28.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.branch_next),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
