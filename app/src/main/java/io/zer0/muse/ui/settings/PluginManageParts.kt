package io.zer0.muse.ui.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.plugin.ProviderPlugin
import io.zer0.muse.R
import io.zer0.muse.data.plugin.ConfigItem
import io.zer0.muse.data.plugin.ConfigItemType
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import io.zer0.muse.data.plugin.PluginCapabilityLedgers
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginRuntimeHealth
import io.zer0.muse.data.plugin.PluginSecurityGate
import io.zer0.muse.data.plugin.PluginVersion
import io.zer0.muse.data.plugin.market.MarketVersionAdvice
import io.zer0.muse.data.plugin.market.MarketVersionView
import io.zer0.muse.data.plugin.market.PluginCatalogEntry
import io.zer0.muse.data.plugin.market.PluginInstallPlan
import io.zer0.muse.data.plugin.market.PluginMarketVersions
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseAnchoredMenu
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseIndeterminateProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.CardGroup
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.RichContentCard
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.largeCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 插件管理页的私有组件：列表行、配置字段、市场条目、安装预览。
 *
 * 从 `PluginManagePage.kt` 切出——主页面函数超过 1,200 行，这些组件与页面骨架
 * 无耦合，独立成文件后可以单独预览与修改。
 *
 * 全部 `private`，只被同包的 `PluginManagePage` 使用，所以可见性不用改。
 */

internal data class PendingExternalInstall(
    val uri: Uri,
    val preview: PluginSecurityGate.InstallPreview,
)

/**
 * 区块小标题 — 视觉与 [io.zer0.muse.ui.common.surface.CardGroup] 的分组标题同档
 * （弱化 onSurfaceVariant + labelLarge），但自身不承载卡片容器。
 *
 * 用于"下面每一项各自是一张卡"的场合（例如每个已安装插件一张卡）：那种结构里
 * 外层不能再套一层分组卡，否则会出现卡里套卡。水平边距由页骨架统一提供，这里不再叠加。
 */
@Composable
internal fun PluginSectionLabel(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = MuseAlpha.titleMuted),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MusePaddings.cardGap, bottom = MusePaddings.contentGap),
    )
}

@Composable
internal fun InstalledPluginRow(
    plugin: PluginManager.InstalledPlugin,
    onToggle: () -> Unit,
    onConfirm: (() -> Unit)?,
    onDelete: () -> Unit,
    /** 未签名草稿的「签名并启用」入口；null 表示该行不提供（非草稿或已确认）。 */
    onSignAndEnable: (() -> Unit)? = null,
    /** 可回滚到的历史版本；null 表示没有可回退的副本。 */
    rollbackVersion: String? = null,
    onRollback: (String) -> Unit = {},
    /** B7-01: 插件配置项(声明+当前值)；为 null 表示该插件未声明配置或无法读取 manifest。 */
    configEntries: List<Pair<ConfigItem, JsonElement?>>? = null,
    /** v1.0.92: 插件 UI 面板 HTML;null 表示未声明。(调用方预加载,避免行内读盘) */
    panelHtml: String? = null,
    onConfigChanged: suspend (String, JsonElement) -> Unit = { _, _ -> },
) {
    // 行自身就是一张卡：页面按单个插件逐张渲染，外层没有分组容器可依附，
    // 而权限面板展开后内部还有分组卡 —— 那时再套一层就成了卡里套卡。
    MuseSurface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.largeCard,
        color = MaterialTheme.colorScheme.surface,
        elevation = MuseElevation.micro,
    ) {
        Column(modifier = Modifier.padding(MusePaddings.cardInner)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // P0-5: Python 档在列表上就要看得见 —— 它等同高信任，不能只藏在折叠面板里。
                    // 判据用入口扩展名：安全门强制 runtime=python 的包入口必须是 .py
                    // （PluginSecurityGate.kt:319-322），因此不需要给 InstalledPlugin 加字段、
                    // 也不需要改 InstalledPluginRow 签名（它被三条 detekt 基线按签名压着）。
                    if (plugin.entry.lowercase().endsWith(".py")) {
                        val trusted = PluginCapabilityLedgers.shared(LocalContext.current)
                            .isApproved(plugin.id, PluginCapabilityGateway.RUNTIME_PYTHON)
                        Text(
                            text = stringResource(
                                if (trusted) R.string.plugin_row_python_trusted else R.string.plugin_row_python_locked,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (trusted) {
                                MaterialTheme.colorScheme.tertiary
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                        )
                    }
                    // PL1 收尾:熔断窗口要在插件页看得见,而且必须自己走表。
                    // 停在一个不再变化的「剩余 300 秒」上等于骗人:用户据此判断「还要等多久」,
                    // 数字不动他就会一直等下去。因此冷却中每秒重读一次,解冻即停表。
                    val health = PluginRuntimeHealth.shared
                    // 这里读计数是为了让本行订阅它:计数 +1 → 本行重组 → 视图重新取值。
                    // 视图本身不 remember,否则冷却中的秒数会被冻在第一次取值上。
                    val circuitTick = remember(plugin.id) { mutableIntStateOf(0) }
                    val circuit = circuitTick.intValue.let { health.circuitView(plugin.id) }
                    if (circuit.status != PluginRuntimeHealth.CircuitStatus.None) {
                        Text(
                            text = when (circuit.status) {
                                PluginRuntimeHealth.CircuitStatus.Probing -> stringResource(R.string.plugin_row_circuit_probing)
                                else -> stringResource(R.string.plugin_row_circuit_cooling, circuit.remainingSeconds)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    LaunchedEffect(plugin.id, circuit.status) {
                        // 循环里读的是真状态,不是重组时抓到的那份快照,否则冷却结束后这个循环永远停不下来。
                        while (health.circuitView(plugin.id).status != PluginRuntimeHealth.CircuitStatus.None) {
                            delay(1_000L)
                            circuitTick.intValue += 1
                        }
                    }
                    Text(
                        // ST-04: 面向用户只显示版本/工具数;插件 id/指纹/sha256 收进「开发者信息」折叠区
                        text = stringResource(R.string.plugin_summary_brief, plugin.version, plugin.tools.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (plugin.description.isNotBlank()) {
                        Text(
                            text = plugin.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = signatureStatusLabel(plugin.signatureStatus),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (!plugin.installationConfirmed) {
                        stringResource(R.string.muse_plugins_draft)
                    } else {
                        stringResource(if (plugin.enabled) R.string.common_state_enabled else R.string.common_state_disabled)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (plugin.enabled && plugin.installationConfirmed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(MusePaddings.itemGap))
            // 多动作收拢进右侧菜单：原先"启用/禁用 + 删除"两个等宽胶囊按钮并排，
            // 一旦行内再出现"签名并启用""确认安装""回滚"，四个按钮挤一行就只剩图标宽度。
            // 现在默认只留一个常驻状态入口，其余动作点「更多」再选；删除仍在菜单里走二次确认。
            val primaryActionLabel = when {
                plugin.installationConfirmed -> stringResource(
                    if (plugin.enabled) R.string.common_state_disabled else R.string.common_state_enabled,
                )
                // 助手自写草稿：签名并启用是本行唯一能把它变成可执行的入口。
                onSignAndEnable != null -> stringResource(R.string.muse_plugins_sign_enable_action)
                onConfirm != null -> stringResource(
                    if (plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED) {
                        R.string.muse_plugins_install_trust_publisher_action
                    } else {
                        R.string.muse_plugins_install_confirm_action
                    },
                )
                else -> null
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            ) {
                if (plugin.installationConfirmed) {
                    MuseCapsuleButton(
                        text = primaryActionLabel.orEmpty(),
                        onClick = onToggle,
                        modifier = Modifier.weight(1f),
                        variant = IosCapsuleButtonVariant.Secondary,
                    )
                } else if (onSignAndEnable != null) {
                    MuseCapsuleButton(
                        text = primaryActionLabel.orEmpty(),
                        onClick = onSignAndEnable,
                        modifier = Modifier.weight(1f),
                        variant = IosCapsuleButtonVariant.Secondary,
                    )
                } else if (onConfirm != null) {
                    MuseCapsuleButton(
                        text = primaryActionLabel.orEmpty(),
                        onClick = { onConfirm?.invoke() },
                        modifier = Modifier.weight(1f),
                        variant = IosCapsuleButtonVariant.Secondary,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.muse_plugins_unsigned_draft_only),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = MusePaddings.itemGap),
                    )
                }
                var actionsOpen by remember { mutableStateOf(false) }
                Box {
                    MuseTactileButton(
                        icon = MuseIcons.moreHorizontal,
                        onClick = { actionsOpen = true },
                        contentDescription = stringResource(R.string.action_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        compact = true,
                    )
                    MuseAnchoredMenu(
                        expanded = actionsOpen,
                        onDismissRequest = { actionsOpen = false },
                        alignEnd = true,
                    ) {
                        if (!plugin.installationConfirmed && onSignAndEnable != null) {
                            MuseListItem(
                                onClick = {
                                    actionsOpen = false
                                    onSignAndEnable.invoke()
                                },
                                leadingContent = { MenuActionIcon(MuseIcons.key) },
                                headlineContent = { Text(stringResource(R.string.muse_plugins_sign_enable_action)) },
                            )
                        }
                        if (!plugin.installationConfirmed && onConfirm != null) {
                            MuseListItem(
                                onClick = {
                                    actionsOpen = false
                                    onConfirm.invoke()
                                },
                                leadingContent = { MenuActionIcon(MuseIcons.shieldCheck) },
                                headlineContent = {
                                    Text(
                                        stringResource(
                                            if (plugin.signatureStatus ==
                                                PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED
                                            ) {
                                                R.string.muse_plugins_install_trust_publisher_action
                                            } else {
                                                R.string.muse_plugins_install_confirm_action
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                        // 回滚入口：只有存在「非当前版本」的历史副本时才出现；真正的校验在回滚时重做。
                        rollbackVersion?.let { version ->
                            MuseListItem(
                                onClick = {
                                    actionsOpen = false
                                    onRollback(version)
                                },
                                leadingContent = { MenuActionIcon(MuseIcons.history) },
                                headlineContent = { Text(stringResource(R.string.muse_plugins_rollback_action, version)) },
                            )
                        }
                        MuseListItem(
                            onClick = {
                                actionsOpen = false
                                // 只交给页面层的 pendingDeleteExternal：确认弹窗在那里统一渲染，
                                // 行内再弹一次会变成"点一次、确认两次"。
                                onDelete()
                            },
                            leadingContent = {
                                MenuActionIcon(
                                    icon = MuseIcons.trash,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            headlineContent = {
                                Text(
                                    text = stringResource(R.string.provider_plugins_delete),
                                    color = MaterialTheme.colorScheme.error,
                                )
                            },
                        )
                    }
                }
            }
            // ST-04: 开发者信息默认折叠 — id/发布者指纹/内容摘要仅供排查,不打扰普通用户
            var showDevInfo by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDevInfo = !showDevInfo }
                    .padding(vertical = MusePaddings.tightGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.muse_plugins_developer_info),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                    // LAYOUT-01: 折叠头取宽 + 限行，防被右侧 chevron 图标挤成一列
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(
                    imageVector = if (showDevInfo) {
                        MuseIcons.chevronUp
                    } else {
                        MuseIcons.chevronDown
                    },
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            }
            if (showDevInfo) {
                Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    Text(
                        text = stringResource(R.string.plugin_summary, plugin.id, plugin.version, plugin.tools.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (plugin.publisherId.isNotBlank() && plugin.publisherKeyFingerprint.isNotBlank()) {
                        Text(
                            text = stringResource(
                                R.string.muse_plugins_publisher_fingerprint,
                                plugin.publisherId,
                                plugin.publisherKeyFingerprint,
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (plugin.contentSha256.isNotBlank()) {
                        Text(
                            text = plugin.contentSha256,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // v1.0.92: 插件 UI 面板 — 声明 uiPanel 的插件提供只读自定义界面
            if (panelHtml != null) {
                var showPanel by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPanel = true }
                        .padding(vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.muse_plugins_open_panel),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: 同上，折叠头限 1 行
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = MuseIcons.chevronUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                if (showPanel) {
                    MuseDialog(
                        onDismissRequest = { showPanel = false },
                        title = plugin.name,
                        content = {
                            RichContentCard(
                                language = "html",
                                content = panelHtml,
                                showPreviewButton = false,
                            )
                        },
                        confirmText = stringResource(R.string.common_close),
                        onConfirm = { showPanel = false },
                    )
                }
            }
            // B7-01: 插件配置表单 — 仅当插件声明了配置项且已启用时才显示
            if (!configEntries.isNullOrEmpty()) {
                val configScope = rememberCoroutineScope()
                var showConfig by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showConfig = !showConfig }
                        .padding(vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.muse_plugins_config),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: 同上，折叠头限 1 行
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Icon(
                        imageVector = if (showConfig) {
                            MuseIcons.chevronUp
                        } else {
                            MuseIcons.chevronDown
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                }
                if (showConfig) {
                    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                        configEntries.forEach { (item, value) ->
                            ConfigFieldRow(
                                item = item,
                                currentValue = value,
                                onChanged = { newValue ->
                                    configScope.launch {
                                        onConfigChanged(item.key, newValue)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun signatureStatusLabel(status: PluginSecurityGate.SignatureStatus): String = when (status) {
    PluginSecurityGate.SignatureStatus.VALID_TRUSTED -> stringResource(R.string.muse_plugins_signature_trusted)
    PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED -> stringResource(R.string.muse_plugins_signature_untrusted)
    PluginSecurityGate.SignatureStatus.UNSIGNED -> stringResource(R.string.muse_plugins_signature_unsigned)
    PluginSecurityGate.SignatureStatus.INVALID -> stringResource(R.string.muse_plugins_signature_invalid)
    PluginSecurityGate.SignatureStatus.UNSUPPORTED -> stringResource(R.string.muse_plugins_signature_unsupported)
}

/** B7-01: 单个配置字段行 —— 按 manifest 声明类型渲染(string/boolean/number/select)。 */
@Composable
internal fun ConfigFieldRow(item: ConfigItem, currentValue: JsonElement?, onChanged: (JsonElement) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        when (item.type) {
            ConfigItemType.boolean -> {
                val checked = (currentValue as? JsonPrimitive)?.booleanOrNull ?: false
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    Text(
                        text = item.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: 配置字段名取宽 + 限行，防被右侧开关挤成一列
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseSwitch(
                        checked = checked,
                        onCheckedChange = { onChanged(JsonPrimitive(it)) },
                    )
                }
            }
            ConfigItemType.number -> {
                val committed = (currentValue as? JsonPrimitive)?.contentOrNull.orEmpty()
                var text by remember(currentValue) { mutableStateOf(committed) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    Text(
                        text = item.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: 同上，配置字段名限 1 行
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTextField(
                        value = text,
                        onValueChange = { input -> text = input.filter { c -> c.isDigit() || c == '-' || c == '.' } },
                        singleLine = true,
                        modifier = Modifier
                            .width(120.dp)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && text != committed) {
                                    text.toDoubleOrNull()?.let { onChanged(JsonPrimitive(it)) }
                                }
                            },
                    )
                }
            }
            ConfigItemType.select -> {
                val selected = (currentValue as? JsonPrimitive)?.contentOrNull.orEmpty()
                MuseDropdown(
                    value = selected,
                    onValueChange = { onChanged(JsonPrimitive(it)) },
                    label = item.key,
                    options = item.options.map { option -> option.value to option.label },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> {
                val committed = (currentValue as? JsonPrimitive)?.contentOrNull.orEmpty()
                var text by remember(currentValue) { mutableStateOf(committed) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                ) {
                    Text(
                        text = item.key,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                        // LAYOUT-01: 同上，配置字段名限 1 行
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseTextField(
                        value = text,
                        onValueChange = { text = it },
                        singleLine = true,
                        modifier = Modifier
                            .width(200.dp)
                            .onFocusChanged { focus ->
                                if (!focus.isFocused && text != committed) {
                                    onChanged(JsonPrimitive(text))
                                }
                            },
                    )
                }
            }
        }
        if (item.description.isNotBlank()) {
            Text(
                text = item.description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun PluginCard(plugin: ProviderPlugin, onDelete: () -> Unit, onConvert: () -> Unit) {
    // 转为供应商 / 删除都收进「更多」：这两个动作一个是低频迁移、一个是危险操作，
    // 都不该在每一行常驻一个高对比色块，把插件名挤成省略号。
    var actionsOpen by remember { mutableStateOf(false) }
    CardGroup {
        item(
            key = plugin.id,
            headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MuseIcons.puzzle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(MuseIconSizes.iconMedium),
                    )
                    Spacer(Modifier.size(MusePaddings.iconPadding))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        Text(
                            text = plugin.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (plugin.description.isNotBlank()) {
                            Text(
                                text = plugin.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = stringResource(R.string.plugin_models_summary, plugin.models.size, plugin.baseUrl),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        MuseTactileButton(
                            icon = MuseIcons.moreHorizontal,
                            onClick = { actionsOpen = true },
                            contentDescription = stringResource(R.string.action_more),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            compact = true,
                        )
                        MuseAnchoredMenu(
                            expanded = actionsOpen,
                            onDismissRequest = { actionsOpen = false },
                            alignEnd = true,
                        ) {
                            MuseListItem(
                                onClick = {
                                    actionsOpen = false
                                    onConvert()
                                },
                                leadingContent = { MenuActionIcon(MuseIcons.swapHorizontal) },
                                headlineContent = { Text(stringResource(R.string.provider_plugins_convert)) },
                            )
                            MuseListItem(
                                onClick = {
                                    actionsOpen = false
                                    onDelete()
                                },
                                leadingContent = {
                                    MenuActionIcon(
                                        icon = MuseIcons.trash,
                                        tint = MaterialTheme.colorScheme.error,
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        text = stringResource(R.string.provider_plugins_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

/** 菜单行前置图标 — 统一小图标 + 中性色，危险项由调用方传入 error 色。 */
@Composable
private fun MenuActionIcon(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(MuseIconSizes.iconSmall),
    )
}

/** 市场安装任务阶段；Failed 携带面向用户的原因。 */
internal sealed interface MarketInstallPhase {
    data object Downloading : MarketInstallPhase
    data object Preparing : MarketInstallPhase
    data object AwaitingConfirm : MarketInstallPhase
    data object Installing : MarketInstallPhase
    data class Failed(val reason: String) : MarketInstallPhase
}

/** 一次市场安装的 UI 状态；[plan] 只在下载+审查+计划全部成功后存在。 */
internal data class MarketInstallJob(
    val entryId: String,
    val phase: MarketInstallPhase,
    val plan: PluginInstallPlan? = null,
    /** 未知发行者的显式信任标志；生成计划时固定，确认时按原值提交。 */
    val trustPublisher: Boolean = false,
)

@Composable
internal fun MarketLoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.screen),
        contentAlignment = Alignment.Center,
    ) {
        MuseSpinner(
            size = MuseIconSizes.iconMedium,
        )
    }
}

@Composable
internal fun MarketErrorRow(reason: String, onRetry: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Icon(
            imageVector = MuseIcons.alertCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        MuseCapsuleButton(
            text = stringResource(R.string.common_retry),
            onClick = onRetry,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/** 目录版本低于已安装版本时不允许安装：降级会被版本策略拒绝，界面不应给出死路入口。 */
internal fun isDowngrade(installedVersion: String?, catalogVersion: String): Boolean {
    val installed = installedVersion?.let(PluginVersion::parse) ?: return false
    val candidate = PluginVersion.parse(catalogVersion) ?: return false
    return candidate < installed
}

/**
 * 市场网格卡片 —— 图标 + 名称 + 版本/发行者 + 状态，点开进详情页。
 */
@Composable
internal fun MarketEntryCell(
    entry: PluginCatalogEntry,
    installedVersion: String?,
    phase: MarketInstallPhase?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** PL3:本机留档的版本号(可切回的历史版本),由调用方从 PluginVersionStore 取。 */
    retainedVersions: List<String> = emptyList(),
    appVersion: String = io.zer0.muse.BuildConfig.VERSION_NAME,
) {
    // PL3:结论只由纯函数算,这里不重复比版本 —— 重复一遍就会有两套答案。
    val versionView = PluginMarketVersions.view(
        catalogVersion = entry.version,
        installedVersion = installedVersion,
        retainedVersions = retainedVersions,
        appVersion = appVersion,
    )
    // 双平面：层次靠"底色 vs 卡面"的明度差，不再画描边 ——
    // 描边在同一屏里会和卡片组的无边框叠成两套分层语言。
    MuseSurface(
        onClick = onClick,
        modifier = modifier,
        shape = MuseShapes.largeCard,
        color = MaterialTheme.colorScheme.surface,
        elevation = MuseElevation.micro,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Box(
                modifier = Modifier
                    .size(MuseIconSizes.controlTouch)
                    .clip(MuseShapes.medium)
                    .background(MuseActionColors.tonalContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = MuseIcons.puzzle,
                    contentDescription = null,
                    tint = MuseActionColors.tonalContent,
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.muse_plugins_market_entry_meta, entry.version, entry.publisherId),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MarketEntryStateLine(
                entryVersion = entry.version,
                installedVersion = installedVersion,
                phase = phase,
                advice = versionView.advice,
            )
        }
    }
}

/** 卡片上的状态行（下载中 / 安装中 / 已安装 / 失败 / 可安装）。 */
@Composable
internal fun MarketEntryStateLine(
    entryVersion: String,
    installedVersion: String?,
    phase: MarketInstallPhase?,
    advice: MarketVersionAdvice,
) {
    val (label, color) = when {
        phase == MarketInstallPhase.Downloading || phase == MarketInstallPhase.Preparing ->
            stringResource(R.string.muse_plugins_market_downloading) to MuseActionColors.mutedContent
        phase == MarketInstallPhase.Installing ->
            stringResource(R.string.muse_plugins_market_installing) to MuseActionColors.mutedContent
        phase is MarketInstallPhase.Failed ->
            stringResource(R.string.muse_plugins_market_install_failed, phase.reason) to MaterialTheme.colorScheme.error
        // PL3:版本号认不出来 / 目录更旧 / 要求更新的 App —— 这些比"可安装"更要紧,先说。
        advice == MarketVersionAdvice.UnknownVersion ->
            stringResource(R.string.muse_plugins_market_advice_unknown_version) to MaterialTheme.colorScheme.error
        advice == MarketVersionAdvice.NeedsNewerApp ->
            stringResource(R.string.muse_plugins_market_advice_needs_newer_app) to MaterialTheme.colorScheme.error
        advice == MarketVersionAdvice.DowngradeAvailable ->
            stringResource(R.string.muse_plugins_market_advice_downgrade) to MuseActionColors.mutedContent
        installedVersion == entryVersion ->
            stringResource(R.string.muse_plugins_market_installed) to MaterialTheme.colorScheme.primary
        else -> stringResource(
            if (installedVersion != null) {
                R.string.muse_plugins_market_update_action
            } else {
                R.string.muse_plugins_market_install_action
            },
        ) to MuseActionColors.mutedContent
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * 市场插件详情面板 —— 完整信息 + 安装/取消/重试动作。
 */
@Composable
internal fun MarketEntryDetailSheet(
    entry: PluginCatalogEntry,
    installedVersion: String?,
    phase: MarketInstallPhase?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissFailure: () -> Unit,
    onDismiss: () -> Unit,
    /** PL3:同一套判定,面板与列表行不许各算一次。 */
    versionView: MarketVersionView,
) {
    MuseBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.muse_plugins_market_entry_meta, entry.version, entry.publisherId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // PL3:面板要说清"能不能装、装了是什么"。未安装时不谎称兼容 ——
        // 兼容要求写在插件 manifest 里,而 manifest 要下载后才拿得到。
        Spacer(Modifier.height(MusePaddings.tightGap))
        Text(
            text = when (versionView.advice) {
                MarketVersionAdvice.DowngradeAvailable ->
                    stringResource(R.string.muse_plugins_market_advice_downgrade)

                MarketVersionAdvice.NeedsNewerApp ->
                    stringResource(R.string.muse_plugins_market_advice_needs_newer_app)

                MarketVersionAdvice.UnknownVersion ->
                    stringResource(R.string.muse_plugins_market_advice_unknown_version)

                else -> stringResource(R.string.muse_plugins_market_advice_compat_unconfirmed)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (
                versionView.advice == MarketVersionAdvice.NeedsNewerApp ||
                versionView.advice == MarketVersionAdvice.UnknownVersion
            ) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.outline
            },
        )
        val switchable = PluginMarketVersions.switchableVersions(versionView.installedVersion, versionView.retainedVersions)
        if (switchable.isNotEmpty()) {
            Spacer(Modifier.height(MusePaddings.tightGap))
            Text(
                text = stringResource(R.string.muse_plugins_market_advice_retained, switchable.joinToString(" · ")),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (entry.description.isNotBlank()) {
            Spacer(Modifier.height(MusePaddings.contentGap))
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(
                R.string.muse_plugins_market_entry_capabilities,
                entry.capabilities.ifEmpty { listOf(stringResource(R.string.muse_plugins_none)) }
                    .joinToString(", "),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(MusePaddings.itemGap))
        MarketInstallActions(
            entryVersion = entry.version,
            installedVersion = installedVersion,
            phase = phase,
            onInstall = onInstall,
            onCancel = onCancel,
            onRetry = onRetry,
            onDismissFailure = onDismissFailure,
        )
    }
}

/** 安装动作行（网格卡片与详情面板共用）。 */
@Composable
internal fun MarketInstallActions(
    entryVersion: String,
    installedVersion: String?,
    phase: MarketInstallPhase?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            phase == MarketInstallPhase.Downloading || phase == MarketInstallPhase.Preparing -> {
                MuseIndeterminateProgressBar(
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        if (phase == MarketInstallPhase.Downloading) {
                            R.string.muse_plugins_market_downloading
                        } else {
                            R.string.muse_plugins_market_preparing
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = onCancel,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
            phase == MarketInstallPhase.AwaitingConfirm -> Text(
                text = stringResource(R.string.muse_plugins_market_awaiting_confirm),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            phase == MarketInstallPhase.Installing -> {
                MuseIndeterminateProgressBar(
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.muse_plugins_market_installing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            phase is MarketInstallPhase.Failed -> {
                Text(
                    text = stringResource(R.string.muse_plugins_market_install_failed, phase.reason),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_retry),
                    onClick = onRetry,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
                MuseCapsuleButton(
                    text = stringResource(R.string.common_close),
                    onClick = onDismissFailure,
                    variant = IosCapsuleButtonVariant.Secondary,
                    fillWidth = false,
                )
            }
            installedVersion == entryVersion -> Text(
                text = stringResource(R.string.muse_plugins_market_installed),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            isDowngrade(installedVersion, entryVersion) -> Text(
                // 目录版本低于已安装版本：安装会被版本策略拒绝，不给死路入口。
                text = stringResource(R.string.muse_plugins_market_downgrade_blocked, installedVersion.orEmpty()),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            else -> MuseCapsuleButton(
                text = stringResource(
                    if (installedVersion != null) {
                        R.string.muse_plugins_market_update_action
                    } else {
                        R.string.muse_plugins_market_install_action
                    },
                ),
                onClick = onInstall,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * P0-9: 信任的发行者行 — 展示 id + 指纹，右侧「更多」里放撤销。
 *
 * 撤销是不可逆的授权回收（撤销后该发行者的插件会被自动禁用），因此不放在行面上常驻：
 * 常驻按钮会让"看一眼指纹"变成"顺手点一下撤销"。真正的二次确认在页面层的弹窗里。
 */
@Composable
internal fun TrustedPublisherRow(publisherId: String, fingerprint: String, onRevoke: () -> Unit) {
    var actionsOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.inputPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = publisherId,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.muse_plugins_trust_publisher_fingerprint, fingerprint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            MuseTactileButton(
                icon = MuseIcons.moreHorizontal,
                onClick = { actionsOpen = true },
                contentDescription = stringResource(R.string.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                compact = true,
            )
            MuseAnchoredMenu(
                expanded = actionsOpen,
                onDismissRequest = { actionsOpen = false },
                alignEnd = true,
            ) {
                MuseListItem(
                    onClick = {
                        actionsOpen = false
                        onRevoke()
                    },
                    leadingContent = { MenuActionIcon(MuseIcons.ban, MaterialTheme.colorScheme.error) },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.muse_plugins_trust_revoke_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }
}

/**
 * P0-9: 目录信任根行 — 展示 keyId，右侧「更多」里放移除（内置根不进这个列表，不可移除）。
 *
 * 移除会让该信任根签发的目录条目全部失效，属于配置级破坏操作，所以同样收进菜单。
 */
@Composable
internal fun CatalogRootKeyRow(keyId: String, onRemove: () -> Unit) {
    var actionsOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.inputPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Text(
            text = keyId,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box {
            MuseTactileButton(
                icon = MuseIcons.moreHorizontal,
                onClick = { actionsOpen = true },
                contentDescription = stringResource(R.string.action_more),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                compact = true,
            )
            MuseAnchoredMenu(
                expanded = actionsOpen,
                onDismissRequest = { actionsOpen = false },
                alignEnd = true,
            ) {
                MuseListItem(
                    onClick = {
                        actionsOpen = false
                        onRemove()
                    },
                    leadingContent = { MenuActionIcon(MuseIcons.trash, MaterialTheme.colorScheme.error) },
                    headlineContent = {
                        Text(
                            text = stringResource(R.string.muse_plugins_trust_catalog_remove_action),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                )
            }
        }
    }
}

/** 安装确认预览：能力 / 工具 / 发行者指纹 / 签名状态；市场与本地导入共用同一份内容。 */
@Composable
internal fun PluginInstallPreviewContent(preview: PluginSecurityGate.InstallPreview) {
    val unknownAuthor = stringResource(R.string.muse_plugins_unknown_author)
    val noCapabilities = stringResource(R.string.muse_plugins_none)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_summary,
                preview.version,
                preview.author.ifBlank { unknownAuthor },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (preview.description.isNotBlank()) {
            Text(
                text = preview.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // 运行时必须写在摘要里：JS 与 Python 的隔离强度不同，用户有权知道自己在授权什么。
        Text(
            text = stringResource(R.string.muse_plugins_runtime_label, preview.runtime),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 与权限面板同一份表述：安装页写"这会做什么"，面板写同一个词，
        // 用户在两处看到的必须是同一件事。（context 只能在组合上下文里取，不能写进 joinToString 的 lambda。）
        val capabilityCopyContext = LocalContext.current
        val capabilitySummary = preview.capabilities.ifEmpty { listOf(noCapabilities) }
            .joinToString("；") { PluginCapabilityCopy.text(capabilityCopyContext, it) }
        Text(
            text = stringResource(R.string.muse_plugins_install_confirm_capabilities, capabilitySummary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        // P0-2 起这份声明**就是**执行期的能力边界（未声明即拒），不再是展示品。
        // 之前这里写过"声明不构成约束" —— 那句在闸门接通后变成对用户的误导，必须同步改掉。
        Text(
            text = stringResource(R.string.muse_plugins_capabilities_disclaimer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_tools,
                preview.tools.joinToString(", ") { it.name },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_code,
                preview.entryBytes,
                preview.extraFileCount,
                preview.entrySha256,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_signature_status,
                signatureStatusLabel(preview.signatureStatus),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (preview.publisherId.isNotBlank() && preview.publisherKeyFingerprint.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.muse_plugins_publisher_fingerprint,
                    preview.publisherId,
                    preview.publisherKeyFingerprint,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when (preview.signatureStatus) {
                PluginSecurityGate.SignatureStatus.UNSIGNED ->
                    stringResource(R.string.muse_plugins_install_unsigned_warning)
                PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED ->
                    stringResource(
                        R.string.muse_plugins_install_untrusted_warning,
                        preview.publisherId,
                        preview.publisherKeyFingerprint,
                    )
                PluginSecurityGate.SignatureStatus.VALID_TRUSTED ->
                    stringResource(R.string.muse_plugins_install_trusted_warning)
                PluginSecurityGate.SignatureStatus.INVALID ->
                    stringResource(R.string.muse_plugins_signature_invalid)
                PluginSecurityGate.SignatureStatus.UNSUPPORTED ->
                    stringResource(R.string.muse_plugins_signature_unsupported)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}

