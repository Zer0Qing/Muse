package io.zer0.muse.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.data.AppearanceSettingsStore
import io.zer0.muse.data.HomeLayoutMode
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.findPresetTheme
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.titleSmallStrong
import kotlinx.coroutines.launch

/**
 * 首页布局双形态选择器与实时可视化预览（外观设置页用）。
 *
 * 与 [BubbleSkinPreviewPanel] 保持同一套「选项列表 + 浅/深实时预览面板」心智，
 * 让用户在切换「经典三页」与「极简抽屉」前直接看到结构差异。
 */
@Composable
internal fun HomeLayoutSection(settings: SettingsRepository, defaultDark: Boolean) {
    val scope = rememberCoroutineScope()
    val layoutMode by settings.homeLayoutModeFlow.collectAsStateWithLifecycle(
        initialValue = HomeLayoutMode.CLASSIC,
    )
    val themeId by settings.themeIdFlow.collectAsStateWithLifecycle(
        initialValue = AppearanceSettingsStore.DEFAULT_THEME_ID,
    )
    var previewDark by rememberSaveable { mutableStateOf(defaultDark) }

    val selectMode: (HomeLayoutMode) -> Unit = { target ->
        scope.launch { settings.saveHomeLayoutMode(target) }
    }

    CardGroup(
        title = { Text(stringResource(R.string.settings_home_layout_title)) },
    ) {
        content(key = "home_layout_classic") {
            HomeLayoutOptionRow(
                title = stringResource(R.string.settings_home_layout_classic),
                subtitle = stringResource(R.string.settings_home_layout_classic_desc),
                selected = layoutMode == HomeLayoutMode.CLASSIC,
                onClick = { selectMode(HomeLayoutMode.CLASSIC) },
            )
        }
        content(key = "home_layout_drawer") {
            HomeLayoutOptionRow(
                title = stringResource(R.string.settings_home_layout_drawer),
                subtitle = stringResource(R.string.settings_home_layout_drawer_desc),
                selected = layoutMode == HomeLayoutMode.DRAWER,
                onClick = { selectMode(HomeLayoutMode.DRAWER) },
            )
        }
    }

    HomeLayoutPreviewPanel(
        selectedMode = layoutMode,
        themeId = themeId,
        dark = previewDark,
        onDarkChange = { previewDark = it },
        onSelectMode = selectMode,
    )
}

@Composable
private fun HomeLayoutOptionRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MuseIconSizes.touchTarget)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(MusePaddings.cardInner),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallStrong,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.heightIn(min = MuseSpacing.xxs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = MuseIcons.check,
                contentDescription = stringResource(R.string.settings_theme_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

@Composable
private fun HomeLayoutPreviewPanel(
    selectedMode: HomeLayoutMode,
    themeId: String,
    dark: Boolean,
    onDarkChange: (Boolean) -> Unit,
    onSelectMode: (HomeLayoutMode) -> Unit,
) {
    val presetTheme = remember(themeId) { findPresetTheme(themeId) }
    val previewScheme = if (dark) presetTheme.darkScheme else presetTheme.lightScheme

    CardGroup(
        title = { Text(stringResource(R.string.settings_home_layout_preview)) },
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    ) {
        content(key = "home_layout_preview_body") {
            Column(modifier = Modifier.fillMaxWidth()) {
                MuseCapsuleTab(
                    tabs = listOf(
                        stringResource(R.string.settings_theme_light),
                        stringResource(R.string.settings_theme_dark),
                    ),
                    selectedIndex = if (dark) 1 else 0,
                    onSelect = { onDarkChange(it == 1) },
                    modifier = Modifier.padding(MusePaddings.cardInner),
                )

                MaterialTheme(colorScheme = previewScheme) {
                    HomeLayoutPreviewChoices(selectedMode, onSelectMode)
                }

                Text(
                    text = stringResource(R.string.settings_home_layout_preview_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = MusePaddings.screen,
                        vertical = MuseSpacing.sm,
                    ),
                )
            }
        }
    }
}

@Composable
internal fun HomeLayoutPreviewChoices(selectedMode: HomeLayoutMode, onSelectMode: (HomeLayoutMode) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.cardInner.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
    ) {
        HomeLayoutMiniPreviewCard(
            label = stringResource(R.string.settings_home_layout_classic),
            selected = selectedMode == HomeLayoutMode.CLASSIC,
            onClick = { onSelectMode(HomeLayoutMode.CLASSIC) },
            modifier = Modifier.weight(1f),
        ) {
            ClassicLayoutThumbnail()
        }
        HomeLayoutMiniPreviewCard(
            label = stringResource(R.string.settings_home_layout_drawer),
            selected = selectedMode == HomeLayoutMode.DRAWER,
            onClick = { onSelectMode(HomeLayoutMode.DRAWER) },
            modifier = Modifier.weight(1f),
        ) {
            DrawerLayoutThumbnail()
        }
    }
}

@Composable
private fun HomeLayoutMiniPreviewCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    thumbnail: @Composable () -> Unit,
) {
    Surface(
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.background,
        border = BorderStroke(
            width = if (selected) MuseSpacing.xxs else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = modifier
            .clip(MuseShapes.semiLarge)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MuseSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        ) {
            thumbnail()
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (selected) {
                    Icon(
                        imageVector = MuseIcons.check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassicLayoutThumbnail() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 132.dp)
            .clip(MuseShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(MuseSpacing.smTight),
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.smTight),
    ) {
        // Top 3-island capsule bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(MuseSpacing.md)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
            Surface(
                shape = MuseShapes.pill,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = MuseSpacing.xs),
            ) {
                Text(
                    text = stringResource(R.string.settings_home_layout_preview_classic_bar),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = MuseSpacing.xs, vertical = MuseSpacing.xxs),
                )
            }
            Box(
                modifier = Modifier
                    .size(MuseSpacing.md)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            )
        }

        // Greeting + session list rows
        Box(
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .heightIn(min = MuseSpacing.sm)
                .clip(MuseShapes.small)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.14f)),
        )
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = MuseSpacing.lg)
                    .clip(MuseShapes.small)
                    .background(MaterialTheme.colorScheme.surface),
            )
        }

        // Bottom-right quick action capsule
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                shape = MuseShapes.pill,
                color = MaterialTheme.colorScheme.primaryContainer,
            ) {
                Box(
                    modifier = Modifier
                        .width(MuseSpacing.xxxl)
                        .heightIn(min = MuseSpacing.md),
                )
            }
        }
    }
}

@Composable
private fun DrawerLayoutThumbnail() {
    val colors = MaterialTheme.colorScheme
    val previewLine = colors.onSurface.copy(alpha = MuseAlpha.statusTint)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(HomeLayoutThumbnailHeight)
            .clip(MuseShapes.medium)
            .background(colors.surfaceContainerHigh),
    ) {
        // Keep conversation history visible below the drawer actions.
        Column(
            modifier = Modifier
                .weight(0.62f)
                .fillMaxHeight()
                .background(colors.surface)
                .padding(MuseSpacing.smTight),
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MuseShapes.small)
                    .background(colors.surfaceContainerHigh)
                    .padding(MuseSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
            ) {
                Icon(MuseIcons.search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(MuseSpacing.md))
                Box(
                    Modifier.fillMaxWidth(0.7f).height(MuseSpacing.xs)
                        .clip(MuseShapes.pill).background(previewLine),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xxs),
            ) {
                repeat(3) { index ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = MuseSpacing.md)
                            .clip(MuseShapes.small)
                            .background(
                                if (index == 2) colors.primary else previewLine,
                            ),
                    )
                }
            }
            HorizontalDivider(color = previewLine)
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth(if (index % 2 == 0) 0.87f else 0.65f)
                        .height(MuseSpacing.smTight)
                        .clip(MuseShapes.small)
                        .background(previewLine),
                )
            }
        }

        // Right active chat canvas
        Column(
            modifier = Modifier
                .weight(0.38f)
                .fillMaxHeight()
                .padding(MuseSpacing.smTight),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.smTight)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(MuseSpacing.xl)
                            .heightIn(min = MuseSpacing.sm)
                            .clip(MuseShapes.pill)
                            .background(previewLine),
                    )
                    Box(
                        modifier = Modifier
                            .size(MuseSpacing.sm)
                            .clip(CircleShape)
                            .background(colors.primary),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .heightIn(min = MuseSpacing.lg)
                        .clip(MuseShapes.medium)
                        .background(colors.surface),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.7f)
                            .heightIn(min = MuseSpacing.lg)
                            .clip(MuseShapes.medium)
                            .background(previewLine),
                    )
                }
            }

            Surface(
                shape = MuseShapes.pill,
                color = colors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MuseSpacing.sm),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MuseSpacing.xs, vertical = MuseSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                ) {
                    Icon(MuseIcons.plus, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(MuseSpacing.md))
                    Box(Modifier.weight(1f).height(MuseSpacing.xs).clip(MuseShapes.pill).background(previewLine))
                }
            }
        }
    }
}

private val HomeLayoutThumbnailHeight = 132.dp
