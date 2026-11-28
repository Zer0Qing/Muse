package io.zer0.muse.ui.memory

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.R
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing

/**
 * 置顶核心记忆管理区（支持折叠、全中文化提示、精确去重与独立取消置顶/删除）。
 */
@Composable
fun PinnedMemorySection(
    pinnedEntries: List<PinnedMemoryStore.PinnedEntry>,
    onUnpin: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDedup: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable(pinnedEntries.size <= 3) {
        mutableStateOf(pinnedEntries.size <= 3)
    }

    AnimatedVisibility(
        visible = pinnedEntries.isNotEmpty(),
        modifier = modifier,
        enter = MuseMotion.expandFadeEnter(),
        exit = MuseMotion.expandFadeExit(),
    ) {
        MuseSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.28f),
        ) {
            Column(modifier = Modifier.padding(MuseSpacing.md)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = MuseIconSizes.touchTarget)
                        .clickable { expanded = !expanded },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    Icon(
                        imageVector = MuseIcons.pin,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Text(
                        text = stringResource(R.string.memory_pinned_title, pinnedEntries.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (pinnedEntries.size > 1) {
                        MuseTactileButton(
                            icon = MuseIcons.check,
                            onClick = onDedup,
                            contentDescription = stringResource(R.string.memory_pinned_dedup_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Icon(
                        imageVector = if (expanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                        contentDescription = stringResource(
                            if (expanded) R.string.memory_pinned_collapse_cd else R.string.memory_pinned_expand_cd,
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }

                AnimatedVisibility(visible = expanded) {
                    Column(
                        modifier = Modifier.padding(top = MuseSpacing.xs),
                        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        pinnedEntries.forEach { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = MuseSpacing.xxs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = entry.content,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                MuseTactileButton(
                                    icon = MuseIcons.pin,
                                    onClick = { onUnpin(entry.id) },
                                    contentDescription = stringResource(R.string.memory_pinned_unpin_cd),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                                MuseTactileButton(
                                    icon = MuseIcons.trash,
                                    onClick = { onDelete(entry.id) },
                                    contentDescription = stringResource(R.string.common_delete),
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
