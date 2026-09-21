package io.zer0.muse.ui.dev

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.MuseFloatingActionMenu
import io.zer0.muse.ui.common.MusePopover
import io.zer0.muse.ui.common.feedback.MuseAlertDialog
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseIconContainer
import io.zer0.muse.ui.common.form.MuseSelectionSheet
import io.zer0.muse.ui.common.form.MuseSlider
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.media.AttachmentChip
import io.zer0.muse.ui.common.media.SuggestionBubbles
import io.zer0.muse.ui.common.media.VoiceMessageBar
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSegmentedRow
import io.zer0.muse.ui.common.settings.SettingsSliderRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.settings.StatusDot
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseErrorStateBox
import io.zer0.muse.ui.common.state.MuseIndeterminateProgressBar
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.state.MuseProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.AvatarRowSkeleton
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseCardPress
import io.zer0.muse.ui.common.surface.MuseDivider
import io.zer0.muse.ui.common.surface.MuseGlassContainer
import io.zer0.muse.ui.common.surface.MuseIsland
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.common.surface.SessionCardSkeleton
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

/**
 * 组件画廊 — 组件库的验收页。
 *
 * 目的：把 `ui/common` 里每个组件的全部形态并排渲染出来，一眼看清是否统一。
 * 新页面作者从这里抄；改组件后也在这里复核。
 *
 * 说明：
 * - 变体名（Primary / Secondary / Text / Tonal / Solid …）沿用代码里的词汇，不翻译。
 * - 字号档用 [LocalDensity] 覆盖 fontScale，用于检查放大字号下的截断和挤压。
 * - 覆盖层（弹窗/面板/菜单）用按钮真实触发，看到的就是实际渲染。
 */
@Composable
fun ComponentGalleryScreen(onBack: () -> Unit) {
    var fontScale by rememberSaveable { mutableFloatStateOf(1f) }
    var overlay by remember { mutableStateOf<GalleryOverlay?>(null) }
    var popoverAnchor by remember { mutableStateOf(Rect.Zero) }
    var confirmDelete by remember { mutableStateOf(false) }

    MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.gallery_title),
                onBack = onBack,
            )
        },
    ) { innerPadding ->
        val density = LocalDensity.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    start = MusePaddings.screen,
                    end = MusePaddings.screen,
                    top = MusePaddings.sectionGap,
                    bottom = MusePaddings.largeGap,
                ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            Text(
                text = stringResource(R.string.gallery_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MuseActionColors.mutedContent,
            )
            GalleryFontScaleRow(
                fontScale = fontScale,
                onSelect = { fontScale = it },
            )
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap)) {
                    ButtonsSection()
                    IconButtonsSection()
                    InputsSection()
                    SettingsRowsSection()
                    SurfacesSection()
                    StatesSection()
                    OverlaysSection(
                        onOpen = { overlay = it },
                        onPopoverAnchor = { popoverAnchor = it },
                        onConfirmDelete = { confirmDelete = true },
                    )
                    MediaSection()
                }
            }
        }
    }

    when (overlay) {
        GalleryOverlay.Dialog -> MuseDialog(
            onDismissRequest = { overlay = null },
            title = "MuseDialog",
            content = { GalleryDemoBody("Two capsule buttons; confirm uses the theme primary.") },
            onConfirm = { overlay = null },
        )

        GalleryOverlay.DangerDialog -> MuseDialog(
            onDismissRequest = { overlay = null },
            title = "MuseDialog · destructive",
            content = { GalleryDemoBody("Destructive confirm uses the theme error color.") },
            confirmText = "Delete",
            onConfirm = { overlay = null },
            destructive = true,
        )

        GalleryOverlay.AlertDialog -> MuseAlertDialog(
            onDismissRequest = { overlay = null },
            title = "MuseAlertDialog",
            message = "Single-button alert dialog.",
        )

        GalleryOverlay.FormDialog -> MuseFormDialog(
            onDismissRequest = { overlay = null },
            title = "MuseFormDialog",
            subtitle = "Short form, 1-4 fields",
            onConfirm = { overlay = null },
        ) {
            var value by remember { mutableStateOf("") }
            MuseTextField(
                value = value,
                onValueChange = { value = it },
                placeholder = { Text("Field") },
                singleLine = true,
            )
        }

        GalleryOverlay.BottomSheet -> MuseBottomSheet(
            onDismissRequest = { overlay = null },
        ) {
            Text(
                text = "MuseBottomSheet",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(MusePaddings.itemGap))
            MuseCapsuleButton(text = "Close", onClick = { overlay = null })
        }

        GalleryOverlay.SelectionSheet -> MuseSelectionSheet(
            title = "MuseSelectionSheet",
            subtitle = "Browsing selection list",
            onDismissRequest = { overlay = null },
        ) {
            listOf("Option A", "Option B", "Option C").forEach { label ->
                SettingsItemRow(
                    title = label,
                    onClick = { overlay = null },
                    trailing = { ChevronRight() },
                )
            }
        }

        GalleryOverlay.Popover -> MusePopover(
            anchorBounds = popoverAnchor,
            gapDp = 8,
            onDismiss = { overlay = null },
        ) {
            Column(modifier = Modifier.padding(MusePaddings.itemGap)) {
                Text(text = "MusePopover", style = MaterialTheme.typography.labelLarge)
                Text(
                    text = "Long-press menu anchored at the press point",
                    style = MaterialTheme.typography.bodySmall,
                    color = MuseActionColors.mutedContent,
                )
            }
        }

        GalleryOverlay.FloatingMenu -> MuseFloatingActionMenu(
            items = listOf(
                MuseFloatingActionItem(
                    key = "copy",
                    icon = Icons.Outlined.Share,
                    label = "Share",
                    onClick = { overlay = null },
                ),
                MuseFloatingActionItem(
                    key = "checked",
                    icon = Icons.Outlined.Check,
                    label = "Checked item",
                    checked = true,
                    onClick = { overlay = null },
                ),
                MuseFloatingActionItem(
                    key = "disabled",
                    icon = Icons.Outlined.Delete,
                    label = "Disabled item",
                    enabled = false,
                    onClick = { overlay = null },
                ),
            ),
            onDismiss = { overlay = null },
        )

        null -> Unit
    }

    if (confirmDelete) {
        ConfirmDeleteDialog(
            title = "ConfirmDeleteDialog",
            itemName = "Sample item",
            consequence = "This cannot be undone.",
            onConfirm = { confirmDelete = false },
            onDismiss = { confirmDelete = false },
        )
    }
}

private enum class GalleryOverlay {
    Dialog,
    DangerDialog,
    AlertDialog,
    FormDialog,
    BottomSheet,
    SelectionSheet,
    Popover,
    FloatingMenu,
}

@Composable
private fun GalleryDemoBody(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MuseActionColors.mutedContent,
    )
}

/** 分组：小节标题 + 一张卡片容器。 */
@Composable
private fun GallerySection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(title)
        CardGroup {
            item(
                headlineContent = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                horizontal = MusePaddings.screen,
                                vertical = MusePaddings.itemGap,
                            ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                        content = content,
                    )
                },
            )
        }
    }
}

@Composable
private fun GalleryLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MuseActionColors.mutedContent,
    )
}

@Composable
private fun GalleryFontScaleRow(fontScale: Float, onSelect: (Float) -> Unit) {
    val scales = listOf(1f, 1.3f, 1.6f)
    val labels = listOf("100%", "130%", "160%")
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        GalleryLabel(stringResource(R.string.gallery_font_scale))
        MuseCapsuleTab(
            tabs = labels,
            selectedIndex = scales.indexOfFirst { it == fontScale }.coerceAtLeast(0),
            onSelect = { onSelect(scales[it]) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ButtonsSection() {
    GallerySection(stringResource(R.string.gallery_group_buttons)) {
        MuseCapsuleButton(
            text = stringResource(R.string.settings_common_save),
            onClick = {},
        )
        MuseCapsuleButton(
            text = "Primary + icon",
            leadingIcon = Icons.Outlined.Check,
            onClick = {},
        )
        MuseCapsuleButton(
            text = stringResource(R.string.gallery_state_loading),
            loading = true,
            onClick = {},
        )
        MuseCapsuleButton(
            text = stringResource(R.string.gallery_state_disabled),
            enabled = false,
            onClick = {},
        )
        MuseCapsuleButton(
            text = "Destructive",
            leadingIcon = Icons.Outlined.Delete,
            destructive = true,
            onClick = {},
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
            MuseCapsuleButton(
                text = "Secondary",
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            MuseCapsuleButton(
                text = "Text",
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
        GalleryLabel("MuseCapsuleButton: Primary / Secondary / Text / Destructive / Loading / Disabled")
    }
}

@Composable
private fun IconButtonsSection() {
    GallerySection(stringResource(R.string.gallery_group_icon_buttons)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTactileButton(
                icon = Icons.Outlined.Settings,
                onClick = {},
                contentDescription = "plain",
            )
            MuseTactileButton(
                icon = Icons.Outlined.Star,
                onClick = {},
                contentDescription = "tonal",
                container = MuseIconContainer.Tonal,
            )
            MuseTactileButton(
                icon = Icons.Outlined.Add,
                onClick = {},
                contentDescription = "solid",
                container = MuseIconContainer.Solid,
            )
            MuseTactileButton(
                icon = Icons.Outlined.Refresh,
                onClick = {},
                contentDescription = "neutral",
                container = MuseIconContainer.Neutral,
            )
            MuseTactileButton(
                icon = Icons.Outlined.Delete,
                onClick = {},
                contentDescription = "disabled",
                container = MuseIconContainer.Tonal,
                enabled = false,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseTactileButton(
                icon = Icons.Outlined.Person,
                onClick = {},
                contentDescription = "large",
                size = MuseIconSizes.touchTarget,
                iconSize = MuseIconSizes.iconLarge,
                container = MuseIconContainer.Solid,
                visualSize = MuseIconSizes.touchTarget,
            )
            MuseFloatingButton(
                icon = Icons.Outlined.Add,
                onClick = {},
                contentDescription = "fab",
            )
        }
        GalleryLabel("MuseTactileButton: None / Tonal / Solid / Neutral / Disabled, touch target 48dp")
    }
}

@Composable
private fun InputsSection() {
    var text by rememberSaveable { mutableStateOf("") }
    var switchA by rememberSaveable { mutableStateOf(true) }
    var switchB by rememberSaveable { mutableStateOf(false) }
    var slider by rememberSaveable { mutableFloatStateOf(0.4f) }
    var dropdown by rememberSaveable { mutableStateOf("a") }
    var chipA by rememberSaveable { mutableStateOf(true) }
    var chipB by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }

    GallerySection(stringResource(R.string.gallery_group_inputs)) {
        MuseTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { Text("MuseTextField") },
            leadingIcon = { Text("@") },
            singleLine = true,
        )
        MuseTextField(
            value = "error",
            onValueChange = {},
            label = { Text("isError = true") },
            isError = true,
            singleLine = true,
        )
        MuseTextField(
            value = "disabled",
            onValueChange = {},
            enabled = false,
            singleLine = true,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseSwitch(checked = switchA, onCheckedChange = { switchA = it })
            MuseSwitch(checked = switchB, onCheckedChange = { switchB = it })
            MuseSwitch(checked = true, onCheckedChange = null)
            GalleryLabel("MuseSwitch: on / off / disabled")
        }
        MuseSlider(value = slider, onValueChange = { slider = it })
        MuseDropdown(
            value = dropdown,
            onValueChange = { dropdown = it },
            label = "MuseDropdown",
            options = listOf("a" to "Option A", "b" to "Option B"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
            MuseChip(selected = chipA, onClick = { chipA = !chipA }, label = "Chip on")
            MuseChip(selected = chipB, onClick = { chipB = !chipB }, label = "Chip off")
        }
        MuseCapsuleTab(
            tabs = listOf("Tab A", "Tab B", "Tab C"),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth(),
        )
        GalleryLabel("MuseCapsuleTab: segmented switcher")
    }
}

@Composable
private fun SettingsRowsSection() {
    var switchOn by rememberSaveable { mutableStateOf(true) }
    var slider by rememberSaveable { mutableFloatStateOf(0.6f) }
    var segmented by rememberSaveable { mutableIntStateOf(1) }

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        SectionLabel(stringResource(R.string.gallery_group_settings))
        GalleryCard {
            SettingsItemRow(
                icon = Icons.Outlined.Settings,
                title = "SettingsItemRow",
                subtitle = "icon + subtitle + chevron",
                onClick = {},
                trailing = { ChevronRight() },
            )
            SettingsGroupDivider()
            SettingsItemRow(
                icon = Icons.Outlined.Notifications,
                title = "SettingsItemRow · trailing",
                subtitle = "status dot in the trailing slot",
                trailing = { StatusDot(color = MaterialTheme.colorScheme.primary, pulse = true) },
            )
            SettingsGroupDivider()
            SettingsSwitchRow(
                icon = Icons.Outlined.Check,
                title = "SettingsSwitchRow",
                subtitle = "row with built-in padding",
                checked = switchOn,
                onCheckedChange = { switchOn = it },
            )
            SettingsGroupDivider()
            SettingsSliderRow(
                icon = Icons.Outlined.Star,
                title = "SettingsSliderRow",
                subtitle = "slider row",
                value = slider,
                valueRange = 0f..1f,
                steps = 0,
                valueText = "${(slider * 100).toInt()}%",
                onValueChange = { slider = it },
            )
            SettingsGroupDivider()
            SettingsItemRow(
                icon = Icons.Outlined.Person,
                title = "SettingsItemRow · disabled",
                subtitle = stringResource(R.string.gallery_state_disabled),
                enabled = false,
                onClick = {},
            )
        }
        GalleryCard {
            SettingsSegmentedRow(
                icon = Icons.Outlined.Info,
                title = "SettingsSegmentedRow",
                subtitle = "segmented row",
                options = listOf("A", "B", "C"),
                selectedIndex = segmented,
                onSelectedChange = { segmented = it },
            )
            MuseDivider(startIndent = 0.dp)
            Row(
                modifier = Modifier.padding(MusePaddings.cardInner),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(color = MaterialTheme.colorScheme.primary)
                StatusDot(color = MaterialTheme.colorScheme.error)
                StatusDot(color = MaterialTheme.colorScheme.tertiary, pulse = true)
                GalleryLabel("StatusDot: normal / error / pulse")
            }
        }
    }
}

/** 设置行容器：与 SettingsGroup 同形（Surface + Column），但用未废弃的 MuseSurface。 */
@Composable
private fun GalleryCard(content: @Composable ColumnScope.() -> Unit) {
    MuseSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(MuseIconSizes.controlTouch)
            .clip(MuseShapes.semiLarge)
            .background(MuseActionColors.tonalContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MuseActionColors.tonalContent,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
    }
}

@Composable
private fun SurfacesSection() {
    GallerySection(stringResource(R.string.gallery_group_surfaces)) {
        MuseSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.extraLarge,
            elevation = MuseElevation.card,
        ) {
            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                Text(text = "MuseSurface · extraLarge + card elevation", style = MaterialTheme.typography.bodyMedium)
            }
        }
        MuseSurface(
            onClick = {},
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.semiLarge,
            color = MuseActionColors.neutralContainer,
        ) {
            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                Text(text = "MuseSurface · clickable + neutral container", style = MaterialTheme.typography.bodyMedium)
            }
        }
        MuseIsland(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                Text(text = "MuseIsland", style = MaterialTheme.typography.bodyMedium)
            }
        }
        MuseGlassContainer(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                Text(text = "MuseGlassContainer", style = MaterialTheme.typography.bodyMedium)
            }
        }
        MuseCardPress(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
                Text(text = "MuseCardPress", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column {
            MuseListItem(
                onClick = {},
                leadingContent = { IconTile(icon = Icons.Outlined.Edit) },
                supportingContent = { Text("headline / supporting / leading / trailing") },
                trailingContent = { ChevronRight() },
                headlineContent = { Text("MuseListItem") },
            )
            MuseDivider(startIndent = 0.dp)
            MuseListItem(
                headlineContent = { Text("MuseListItem · not clickable") },
                supportingContent = { Text("onClick = null") },
            )
        }
        AttachmentChip(filePath = "/demo/attachment.pdf", fileSize = 1_048_576L)
    }
}

@Composable
private fun StatesSection() {
    GallerySection(stringResource(R.string.gallery_group_states)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MuseSpinner(size = MuseIconSizes.iconSmallTiny)
            MuseSpinner(size = MuseIconSizes.icon)
            MuseSpinner(size = MuseIconSizes.iconLarge)
            Spacer(Modifier.width(MusePaddings.contentGap))
            MuseCapsuleButton(
                text = "Toast",
                fillWidth = false,
                onClick = { MuseToast.show("MuseToast") },
            )
        }
        GalleryLabel("MuseSpinner: 14 / 24 / 32dp, tinted by LocalContentColor")
        MuseProgressBar(progress = 0.35f)
        MuseIndeterminateProgressBar()
        GalleryLabel("MuseProgressBar (0.35) / MuseIndeterminateProgressBar")
        MuseLoadingState(message = "MuseLoadingState")
        MuseEmptyState(
            icon = Icons.Outlined.Info,
            title = "MuseEmptyState",
            subtitle = "icon + title + subtitle + optional action",
            actionText = "Action",
            onAction = {},
        )
        MuseErrorStateBox(
            message = "MuseErrorStateBox",
            onRetry = {},
        )
        SessionCardSkeleton()
        AvatarRowSkeleton()
    }
}

@Composable
private fun OverlaysSection(
    onOpen: (GalleryOverlay) -> Unit,
    onPopoverAnchor: (Rect) -> Unit,
    onConfirmDelete: () -> Unit,
) {
    var localAnchor by remember { mutableStateOf(Rect.Zero) }
    GallerySection(stringResource(R.string.gallery_group_overlays)) {
        MuseCapsuleButton(text = "MuseDialog", onClick = { onOpen(GalleryOverlay.Dialog) })
        MuseCapsuleButton(
            text = "MuseDialog · destructive",
            destructive = true,
            onClick = { onOpen(GalleryOverlay.DangerDialog) },
        )
        MuseCapsuleButton(
            text = "MuseAlertDialog",
            variant = IosCapsuleButtonVariant.Secondary,
            onClick = { onOpen(GalleryOverlay.AlertDialog) },
        )
        MuseCapsuleButton(
            text = "MuseFormDialog",
            variant = IosCapsuleButtonVariant.Secondary,
            onClick = { onOpen(GalleryOverlay.FormDialog) },
        )
        MuseCapsuleButton(
            text = "ConfirmDeleteDialog",
            variant = IosCapsuleButtonVariant.Secondary,
            onClick = onConfirmDelete,
        )
        MuseCapsuleButton(
            text = "MuseBottomSheet",
            variant = IosCapsuleButtonVariant.Secondary,
            onClick = { onOpen(GalleryOverlay.BottomSheet) },
        )
        MuseCapsuleButton(
            text = "MuseSelectionSheet",
            variant = IosCapsuleButtonVariant.Secondary,
            onClick = { onOpen(GalleryOverlay.SelectionSheet) },
        )
        MuseCapsuleButton(
            text = "MuseFloatingActionMenu",
            variant = IosCapsuleButtonVariant.Secondary,
            onClick = { onOpen(GalleryOverlay.FloatingMenu) },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    localAnchor = coords.boundsInWindow()
                },
        ) {
            MuseCapsuleButton(
                text = "MusePopover",
                variant = IosCapsuleButtonVariant.Secondary,
                onClick = {
                    onPopoverAnchor(localAnchor)
                    onOpen(GalleryOverlay.Popover)
                },
            )
        }
    }
}

@Composable
private fun MediaSection() {
    GallerySection(stringResource(R.string.gallery_group_misc)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantAvatar(
                assistant = AssistantEntity(id = "gallery_preview", name = "Muse"),
                avatarSize = 40.dp,
            )
            AssistantAvatar(
                assistant = AssistantEntity(id = "gallery_preview_large", name = "Muse"),
                avatarSize = 56.dp,
            )
            GalleryLabel("AssistantAvatar")
        }
        SuggestionBubbles(
            suggestions = listOf("SuggestionBubbles", "Tap me", "Third one"),
            onSuggestionClick = {},
        )
        VoiceMessageBar(
            isRecording = true,
            durationSeconds = 12,
        )
    }
}
