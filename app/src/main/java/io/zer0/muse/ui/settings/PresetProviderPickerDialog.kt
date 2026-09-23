package io.zer0.muse.ui.settings

import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.surface.museBottomBarInsets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.navigation.MuseTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.ProviderCategory
import io.zer0.muse.R
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.ui.theme.MuseShapes
import org.koin.compose.koinInject
import compose.icons.TablerIcons
import compose.icons.tablericons.*

/**
 * v1.38: 预置供应商选择器 — 全屏页面(替代原 ModalBottomSheet,根治关闭后卡死 bug)。
 *
 * 设计:
 *  - v1.0.6: 43 个预置供应商(海外官方 15 + 国产官方 22 + 中转站 6),
 *    全量对齐 既有实现 37 个内置供应商 + 1muse 特有 8 个(deepinfra / lingyi /
 *    github-copilot / 6 个中转站模板)。覆盖主流模型厂商。
 *  - 顶部三段式导航(左返回 + 中标题 + 右空),与 [SettingsSubPageScaffold] 保持一致
 *  - 搜索栏实时过滤 displayName / baseUrl / id
 *  - 卡片:surface 背景 + 1dp outlineVariant 细边框 + 20dp 大圆角(无 elevation)
 *  - 头像:colorScheme.primary/secondary/tertiary 派生的低饱和渐变
 *  - 分组标题:small label + 横向分隔线(克制,无数量徽标)
 *  - 末尾固定"自定义供应商"入口
 *
 * 调用方: [SettingsModelPage](点"添加 Provider"时进入全屏页面,选择后返回编辑页)。
 *
 * 替代历史:
 *  - v1.4 ~ v1.36:使用 ModalBottomSheet,在部分真机上 scrim 遮罩无法正确移除,关闭后页面卡死
 *  - v1.37:临时砍掉该功能,直接进入空白编辑页
 *  - v1.38:改用全屏 Scaffold,系统 inset 从 Activity 窗口直接传递,稳定可靠
 */
@Composable
fun PresetProviderPickerDialog(
    onDismiss: () -> Unit,
    onPickPreset: (ProviderConfig) -> Unit,
    onPickCustom: () -> Unit,
) {
    // 注意:保留原函数名 PresetProviderPickerDialog 以兼容现有调用方,仅内部实现改为全屏
    PresetProviderPickerPage(
        onDismiss = onDismiss,
        onPickPreset = onPickPreset,
        onPickCustom = onPickCustom,
    )
}

/**
 * 预置供应商选择全屏页面 — 实际实现。
 *
 * iOS 风格顶部栏 + 搜索栏 + LazyColumn。
 */
@Composable
private fun PresetProviderPickerPage(
    onDismiss: () -> Unit,
    onPickPreset: (ProviderConfig) -> Unit,
    onPickCustom: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val presetProviders = koinInject<PresetProviders>()

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.settings_preset_select_provider),
                onBack = onDismiss,
            )
        },
        modifier = Modifier
            .fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .museBottomBarInsets()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 副标题(说明文字)
            item {
                Text(
                    text = stringResource(R.string.settings_preset_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 4.dp),
                )
            }

            // 搜索栏(固定在列表顶部,随滚动)
            item {
                SearchBar(
                    query = query,
                    onQueryChange = { query = it },
                )
            }

            // 自定义供应商 — 置顶,触手可及
            item(key = "custom_top") {
                CustomItem(onClick = onPickCustom, highlighted = true)
            }

            // 预设列表(按分类过滤)
            val allPresets = presetProviders.all
            val filtered = if (query.isBlank()) {
                allPresets
            } else {
                val q = query.lowercase().trim()
                allPresets.filter { p ->
                    p.displayName.lowercase().contains(q) ||
                        p.baseUrl.lowercase().contains(q) ||
                        p.id.lowercase().contains(q)
                }
            }

            // 海外官方分组
            val overseas = filtered.filter {
                it.category == ProviderCategory.OFFICIAL &&
                    presetProviders.overseas.any { o -> o.id == it.id }
            }
            if (overseas.isNotEmpty()) {
                item(key = "label_overseas") { GroupLabel(stringResource(R.string.settings_preset_group_overseas)) }
                items(items = overseas, key = { it.id }) { preset ->
                    PresetItem(preset = preset, onClick = { onPickPreset(preset) })
                }
            }

            // 国产官方分组
            val domestic = filtered.filter {
                it.category == ProviderCategory.OFFICIAL &&
                    presetProviders.domestic.any { d -> d.id == it.id }
            }
            if (domestic.isNotEmpty()) {
                item(key = "label_domestic") { GroupLabel(stringResource(R.string.settings_preset_group_domestic)) }
                items(items = domestic, key = { it.id }) { preset ->
                    PresetItem(preset = preset, onClick = { onPickPreset(preset) })
                }
            }

            // 中转站分组
            val relay = filtered.filter { it.category == ProviderCategory.RELAY }
            if (relay.isNotEmpty()) {
                item(key = "label_relay") { GroupLabel(stringResource(R.string.settings_preset_group_relay)) }
                items(items = relay, key = { it.id }) { preset ->
                    PresetItem(preset = preset, onClick = { onPickPreset(preset) })
                }
            }

            // 搜索无结果
            if (filtered.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.settings_preset_no_match),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
            }

        }
    }
}

/**
 * 搜索栏:24dp 胶囊圆角 + surfaceVariant 背景 + 无边框 + 清除按钮。
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    MuseTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = {
            Text(
                text = stringResource(R.string.settings_preset_search_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = TablerIcons.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                MuseTactileButton(
                    icon = TablerIcons.X,
                    onClick = { onQueryChange("") },
                    contentDescription = stringResource(R.string.settings_preset_clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    iconSize = 18.dp,
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { /* 默认行为 */ }),
    )
}

/**
 * 分组标签:small label + 横向分隔线(克制风格,无数量徽标)。
 */
@Composable
private fun GroupLabel(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        // 横向分隔线 — outlineVariant 细线
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

/**
 * 预设供应商列表项:左侧品牌 logo 砖 + 右侧信息(域名、模型数、尾部箭头)。
 *
 * 卡片:surface 背景 + 1dp outlineVariant 边框 + 20dp 圆角(无 elevation)。
 */
@Composable
private fun PresetItem(preset: ProviderConfig, onClick: () -> Unit) {
    val needBaseUrlText = stringResource(R.string.settings_preset_need_baseurl)
    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 左侧:品牌 logo 砖
            ProviderLogo(
                type = preset.type,
                name = preset.displayName,
                size = 40.dp,
            )
            Spacer(Modifier.width(12.dp))
            // 中间:信息
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = preset.displayName,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = extractDomain(preset.baseUrl).ifBlank { needBaseUrlText },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    Text(
                        text = stringResource(R.string.settings_preset_model_count, preset.models.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = TablerIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 自定义入口:独立大卡片;highlighted 时用主题主色浅底突出(置顶入口)。
 */
@Composable
private fun CustomItem(onClick: () -> Unit, highlighted: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.extraLarge,
        color = if (highlighted) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (highlighted) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.outlineVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // + 号圆形
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = TablerIcons.Plus,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_preset_custom),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.settings_preset_custom_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 从 baseUrl 提取域名(用于预设卡片显示);空返回空字符串。 */
private fun extractDomain(baseUrl: String): String {
    if (baseUrl.isBlank()) return ""
    return runCatching {
        val uri = java.net.URI(baseUrl)
        uri.host ?: baseUrl
    }.getOrElse { baseUrl }
}
