package io.zer0.muse.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import compose.icons.TablerIcons
import compose.icons.tablericons.Check
import compose.icons.tablericons.FileImport
import compose.icons.tablericons.Share
import compose.icons.tablericons.Trash
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.theme.BubbleRole
import io.zer0.muse.ui.theme.BubbleSkin
import io.zer0.muse.ui.theme.BubbleSkinCatalog
import io.zer0.muse.ui.theme.BubbleSkinResolver
import io.zer0.muse.ui.theme.BubbleSkinStore
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.PluginSkinSource
import kotlinx.coroutines.launch

/**
 * Phase 4: 气泡皮肤选择与预览区。
 *
 * 复用 [CustomThemeSection] 的"列表 + 导入/导出 + 选中"模式:
 *  - 存储:[BubbleSkinStore](与 AppearanceSettingsStore 共用 `muse_settings` DataStore);
 *  - 来源:内置默认 + 用户导入/自定义 + 已安装插件(ui-skin,由 [PluginSkinSource] 重新校验后提供);
 *  - 预览:与聊天页共用 [BubbleSkinResolver],覆盖 user / assistant / 群聊助手三种角色、
 *    light/dark 两种模式,并按当前字号档位与皮肤 fontScale 缩放样例文字;
 *  - 未选中或选中皮肤非法/插件被禁用时预览与真实渲染都回退内置 default(null 皮肤),
 *    确认页行为与改造前完全一致。
 *
 * @param fontSizeScale 当前字号档位("small"/"medium"/"large"/"xlarge"),
 * 用于让预览随字号设置变化。
 * @param defaultDark 预览初始模式,取当前主题深浅。
 */
@Composable
internal fun BubbleSkinSection(
    fontSizeScale: String,
    defaultDark: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Phase 4: 插件皮肤来源 — 已安装 ui-skin 插件重新校验后进入可选列表;
    // 禁用/卸载/信任撤销时 store 不再返回它,预览与真实渲染都回退内置 default。
    val pluginSkinSource: PluginSkinSource = org.koin.compose.koinInject()
    val store = remember(context, pluginSkinSource) {
        BubbleSkinStore(context, pluginSkinSource)
    }
    val customSkins by store.customSkinsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val pluginSkins by store.pluginSkinsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val selectedId by store.selectedSkinIdFlow.collectAsStateWithLifecycle(
        initialValue = BubbleSkinCatalog.DEFAULT_SKIN_ID,
    )
    val selectedSkin by store.selectedSkinFlow.collectAsStateWithLifecycle(initialValue = null)
    var previewDark by rememberSaveable { mutableStateOf(defaultDark) }
    var deletingSkin by remember { mutableStateOf<BubbleSkin?>(null) }

    // E1 同款:SAF 选文件导入皮肤 JSON;解析/校验失败保持当前皮肤不变并提示。
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
        }.onFailure { e ->
            Logger.w("BubbleSkinSection", "皮肤导入读取失败: ${e.message}")
        }.getOrNull()
        scope.launch {
            val imported = store.importSkin(text)
            if (imported != null) {
                // 导入后自动切换到新皮肤,与自定义主题导入行为一致
                store.saveSelectedSkinId(imported.id)
                MuseToast.show(context.getString(R.string.settings_bubble_skin_import_success))
            } else {
                MuseToast.show(context.getString(R.string.settings_bubble_skin_import_failed))
            }
        }
    }

    val shareSkin: (BubbleSkin) -> Unit = { skin ->
        val json = AppJson.encodeToString(BubbleSkin.serializer(), skin)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Muse Bubble Skin: ${skin.name}")
            putExtra(Intent.EXTRA_TEXT, json)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.settings_bubble_skin_export)),
        )
    }

    SectionLabel(stringResource(R.string.settings_bubble_skin_section))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        // 内置默认皮肤:选中即清空自定义选择,渲染保持既有外观
        BubbleSkinRow(
            name = stringResource(R.string.settings_bubble_skin_builtin),
            subtitle = stringResource(R.string.settings_bubble_skin_builtin_desc),
            isSelected = selectedId == BubbleSkinCatalog.DEFAULT_SKIN_ID,
            onClick = { scope.launch { store.saveSelectedSkinId(BubbleSkinCatalog.DEFAULT_SKIN_ID) } },
            onExport = null,
            onDelete = null,
        )
        customSkins.forEach { skin ->
            SettingsGroupDivider()
            BubbleSkinRow(
                name = skin.name,
                subtitle = skin.author.ifBlank {
                    stringResource(R.string.settings_bubble_skin_custom_desc)
                },
                isSelected = skin.id == selectedId,
                onClick = { scope.launch { store.saveSelectedSkinId(skin.id) } },
                onExport = { shareSkin(skin) },
                onDelete = { deletingSkin = skin },
            )
        }
        SettingsGroupDivider()
        // 导入操作行(与自定义主题区的导入入口同款)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream"),
                    )
                }
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = TablerIcons.FileImport,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Text(
                text = stringResource(R.string.settings_bubble_skin_import),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    // ── Phase 4: 已安装插件的声明式皮肤(只读展示/选择,不支持导出与删除) ──
    if (pluginSkins.isNotEmpty()) {
        SectionLabel(stringResource(R.string.settings_bubble_skin_plugin_section))
        SettingsGroup(
            modifier = Modifier.padding(top = 8.dp),
        ) {
            pluginSkins.forEachIndexed { index, installed ->
                if (index > 0) SettingsGroupDivider()
                BubbleSkinRow(
                    name = installed.skin.name,
                    subtitle = stringResource(
                        R.string.settings_bubble_skin_plugin_source,
                        installed.pluginName,
                        installed.version,
                    ),
                    isSelected = installed.skin.id == selectedId,
                    onClick = { scope.launch { store.saveSelectedSkinId(installed.skin.id) } },
                    onExport = null,
                    onDelete = null,
                    enabled = installed.enabled,
                    note = if (installed.enabled) {
                        null
                    } else {
                        stringResource(R.string.settings_bubble_skin_plugin_disabled)
                    },
                )
            }
        }
    }

    // 选中 id 存在但皮肤非法/已丢失(旧版本写入、校验规则收紧)→ 明确提示已回退默认;
    // 命中已安装但不可用的插件皮肤时给出插件维度原因,避免用户误以为皮肤损坏。
    if (selectedId != BubbleSkinCatalog.DEFAULT_SKIN_ID && selectedSkin == null) {
        val pluginEntry = pluginSkins.firstOrNull { it.skin.id == selectedId }
        Text(
            text = stringResource(
                if (pluginEntry != null && !pluginEntry.enabled) {
                    R.string.settings_bubble_skin_plugin_unavailable_fallback
                } else {
                    R.string.settings_bubble_skin_invalid_fallback
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 16.dp, top = 6.dp, end = 16.dp),
        )
    }

    BubbleSkinPreviewPanel(
        skin = selectedSkin,
        dark = previewDark,
        fontSizeScale = fontSizeScale,
        onDarkChange = { previewDark = it },
    )

    deletingSkin?.let { skin ->
        MuseDialog(
            onDismissRequest = { deletingSkin = null },
            title = stringResource(R.string.settings_bubble_skin_delete_confirm),
            content = {
                Text(
                    text = stringResource(R.string.settings_bubble_skin_delete_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.settings_bubble_skin_delete),
            onConfirm = {
                scope.launch { store.deleteSkin(skin.id) }
                deletingSkin = null
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { deletingSkin = null },
            destructive = true,
        )
    }
}

/**
 * 单张皮肤行 — 名称 + 作者/说明 + 选中态 + 导出/删除。
 *
 * [enabled] = false(插件未确认/已禁用/信任失效)时不可点击,并用 [note] 说明不可用原因;
 * 该状态下既不能选中,也不会改变当前生效皮肤。
 */
@Composable
private fun BubbleSkinRow(
    name: String,
    subtitle: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    onExport: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    enabled: Boolean = true,
    note: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                    isSelected -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = TablerIcons.Check,
                contentDescription = stringResource(R.string.settings_theme_selected),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
        if (onExport != null) {
            // CMP-11: 裸 IconButton → 收敛到 Muse 组件 MuseTopBarIconButton
            MuseTopBarIconButton(
                icon = TablerIcons.Share,
                contentDescription = stringResource(R.string.settings_bubble_skin_export),
                onClick = onExport,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDelete != null) {
            MuseTopBarIconButton(
                icon = TablerIcons.Trash,
                contentDescription = stringResource(R.string.settings_bubble_skin_delete),
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * 皮肤预览面板 — 覆盖 user / assistant / 群聊助手 × light / dark × 字体缩放。
 *
 * 每个气泡都用 [BubbleSkinResolver.resolve] 解析,与 MessageBubble /
 * GroupChatMessageBubble 走同一条回退链路:皮肤为 null 或校验失败时解析结果即内置 default。
 */
@Composable
private fun BubbleSkinPreviewPanel(
    skin: BubbleSkin?,
    dark: Boolean,
    fontSizeScale: String,
    onDarkChange: (Boolean) -> Unit,
) {
    SectionLabel(stringResource(R.string.settings_bubble_skin_preview))
    SettingsGroup(
        modifier = Modifier.padding(top = 8.dp),
    ) {
        MuseCapsuleTab(
            tabs = listOf(
                stringResource(R.string.settings_theme_light),
                stringResource(R.string.settings_theme_dark),
            ),
            selectedIndex = if (dark) 1 else 0,
            onSelect = { onDarkChange(it == 1) },
            modifier = Modifier.padding(MusePaddings.cardInner),
        )
        SettingsGroupDivider()
        BubbleSkinPreviewBubble(
            role = BubbleRole.USER,
            roleLabel = stringResource(R.string.settings_bubble_skin_role_user),
            text = stringResource(R.string.settings_theme_preview_hello),
            skin = skin,
            dark = dark,
            fontSizeScale = fontSizeScale,
            alignEnd = true,
        )
        SettingsGroupDivider()
        BubbleSkinPreviewBubble(
            role = BubbleRole.ASSISTANT,
            roleLabel = stringResource(R.string.settings_bubble_skin_role_assistant),
            text = stringResource(R.string.settings_theme_preview_reply),
            skin = skin,
            dark = dark,
            fontSizeScale = fontSizeScale,
            alignEnd = false,
        )
        SettingsGroupDivider()
        BubbleSkinPreviewBubble(
            role = BubbleRole.GROUP_ASSISTANT,
            roleLabel = stringResource(R.string.settings_bubble_skin_role_group_assistant),
            text = stringResource(R.string.settings_theme_preview_reply),
            skin = skin,
            dark = dark,
            fontSizeScale = fontSizeScale,
            alignEnd = false,
        )
        Text(
            text = stringResource(R.string.settings_bubble_skin_preview_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        )
    }
}

/**
 * 单个预览气泡 — 直接渲染 resolver 输出的圆角/底色/前景色/内边距/最大宽度/字体缩放。
 */
@Composable
private fun BubbleSkinPreviewBubble(
    role: BubbleRole,
    roleLabel: String,
    text: String,
    skin: BubbleSkin?,
    dark: Boolean,
    fontSizeScale: String,
    alignEnd: Boolean,
) {
    val resolved = remember(skin, role, dark) {
        BubbleSkinResolver.resolve(skin, role, darkTheme = dark)
    }
    val style = resolved.style
    // 与 ThemeSection.FontSizePreview 相同的档位系数,让预览随字号设置实时变化;
    // 皮肤自身的 fontScale(0.75~1.5)在此之上叠加。
    val fontScale = fontSizeScaleFactor(fontSizeScale) * style.fontScale
    val contentStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = (MaterialTheme.typography.bodyMedium.fontSize.value * fontScale).sp,
        lineHeight = (MaterialTheme.typography.bodyMedium.lineHeight.value * fontScale).sp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.cardInner),
    ) {
        Text(
            text = roleLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Surface(
                color = Color(style.surfaceArgb),
                shape = RoundedCornerShape(style.radiusDp.dp),
                border = if (style.outlineWidthDp > 0f && style.outlineArgb != 0L) {
                    BorderStroke(style.outlineWidthDp.dp, Color(style.outlineArgb))
                } else {
                    null
                },
                modifier = Modifier.fillMaxWidth(style.maxWidthFraction.coerceIn(0.35f, 1f)),
            ) {
                Text(
                    text = text,
                    style = contentStyle,
                    color = Color(style.contentArgb),
                    modifier = Modifier.padding(
                        horizontal = style.paddingHorizontalDp.dp,
                        vertical = style.paddingVerticalDp.dp,
                    ),
                )
            }
        }
    }
}

/** 字号档位系数,与 MuseTypography.scaled 保持一致。 */
private fun fontSizeScaleFactor(scale: String): Float = when (scale) {
    "small" -> 0.85f
    "large" -> 1.15f
    "xlarge" -> 1.3f
    else -> 1f
}
