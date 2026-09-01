package io.zer0.muse.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.Puzzle
import compose.icons.tablericons.SwitchHorizontal
import compose.icons.tablericons.Trash
import compose.icons.tablericons.Plus
import io.zer0.ai.plugin.ProviderPlugin
import io.zer0.ai.plugin.ProviderPluginRegistry
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * 统一「插件管理」页（v1.0.62）。
 *
 * 合并两类插件：
 *  - 外部插件（.muse-plugin / ZIP 包）：PluginManager 管理，工具注册进 skills 表由 SkillExecutor 执行
 *  - Provider 插件（JSON 配置）：ProviderPluginRegistry 管理，可转为供应商配置
 *
 * 导入入口统一：自动识别文件类型（ZIP 头 PK → 外部插件；JSON → Provider 插件）。
 */
@Composable
fun PluginManagePage(
    onBack: () -> Unit,
) {
    val pluginManager: PluginManager = koinInject()
    val registry: ProviderPluginRegistry = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widthClass = rememberWindowWidthClass()

    var externalPlugins by remember { mutableStateOf(pluginManager.list()) }
    var providerPlugins by remember { mutableStateOf(registry.list()) }
    var importing by remember { mutableStateOf(false) }
    var pendingDeleteExternal by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }
    var pendingDeleteProvider by remember { mutableStateOf<ProviderPlugin?>(null) }

    /** 统一导入：PK 头 → 外部插件包；否则按 JSON Provider 插件处理。 */
    suspend fun importFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull() ?: error("无法读取所选文件")
            val isZip = bytes.size >= 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
            if (isZip) {
                // C-30 「安装二次确认」提示位: 外部插件包**无签名校验**(见 PluginManager.installFromFile 注记),
                // 直接安装即执行第三方 JS。建议在此处弹出确认对话框,向用户明示:
                //   "该第三方插件未经签名校验,将允许其访问本地 JS 沙盒(禁网络/导航)。确认安装?"
                //   用户确认后才继续下方 pluginManager.installFromFile;取消则 return@withContext 中止导入。
                // (UI 接线留待后续;当前为最小改动,保持与 Provider 插件一致的即时导入行为。)
                val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.muse-plugin")
                try {
                    tempFile.writeBytes(bytes)
                    pluginManager.installFromFile(tempFile)
                        .onSuccess {
                            withContext(Dispatchers.Main) {
                                externalPlugins = pluginManager.list()
                                MuseToast.show(context.getString(R.string.muse_plugins_imported))
                            }
                        }
                        .onFailure { e ->
                            val msg = e.message ?: e::class.simpleName ?: "unknown"
                            withContext(Dispatchers.Main) {
                                MuseToast.show(context.getString(R.string.provider_plugins_import_failed, msg), 3500)
                            }
                        }
                } finally {
                    runCatching { if (tempFile.exists()) tempFile.delete() }
                }
                return@withContext
            }
            val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.json")
            try {
                tempFile.writeBytes(bytes)
                registry.loadFromFile(tempFile)
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            providerPlugins = registry.list()
                            MuseToast.show(context.getString(R.string.provider_plugins_import_success))
                        }
                    }
                    .onFailure { e ->
                        val msg = e.message ?: e::class.simpleName ?: "unknown"
                        withContext(Dispatchers.Main) {
                            MuseToast.show(context.getString(R.string.provider_plugins_import_failed, msg), 3500)
                        }
                    }
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    fun convertToProvider(plugin: ProviderPlugin) {
        scope.launch {
            val config = registry.toProviderConfig(plugin)
            settings.addProvider(config)
            MuseToast.show(context.getString(R.string.provider_plugins_import_success))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            resultOf { importFromUri(uri) }
                .onError { msg, t -> Logger.w("PluginManagePage", "importFromUri failed: $msg", t) }
            importing = false
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.muse_plugins_manage),
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            MuseFloatingButton(
                icon = TablerIcons.Plus,
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                contentDescription = stringResource(R.string.provider_plugins_import),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .then(
                    if (widthClass == WindowWidthClass.Expanded) Modifier.widthIn(max = 720.dp) else Modifier,
                ),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
            ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            // ── 外部插件区 ──
            item { SectionHeader(stringResource(R.string.muse_plugins_external)) }
            if (importing && externalPlugins.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else if (externalPlugins.isEmpty()) {
                item {
                    MuseEmptyState(
                        icon = TablerIcons.Puzzle,
                        title = stringResource(R.string.muse_plugins_empty_hint),
                    )
                }
            } else {
                items(externalPlugins, key = { "ext_${it.id}" }) { plugin ->
                    InstalledPluginRow(
                        plugin = plugin,
                        onToggle = {
                            scope.launch {
                                pluginManager.setEnabled(plugin.id, !plugin.enabled)
                                externalPlugins = pluginManager.list()
                            }
                        },
                        onDelete = { pendingDeleteExternal = plugin },
                    )
                }
            }

            // ── Provider 插件区 ──
            item {
                Spacer(Modifier.height(MusePaddings.contentGap))
                SectionHeader(stringResource(R.string.provider_plugins_title))
            }
            if (providerPlugins.isEmpty()) {
                item {
                    MuseEmptyState(
                        icon = TablerIcons.Puzzle,
                        title = stringResource(R.string.provider_plugins_empty),
                    )
                }
            } else {
                items(providerPlugins, key = { "prov_${it.id}" }) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        onDelete = { pendingDeleteProvider = plugin },
                        onConvert = { convertToProvider(plugin) },
                    )
                }
            }
        }
    }

    // 外部插件删除确认
    pendingDeleteExternal?.let { target ->
        MuseDialog(
            onDismissRequest = { pendingDeleteExternal = null },
            title = stringResource(R.string.provider_plugins_delete),
            content = {
                Text(
                    text = stringResource(R.string.provider_plugins_delete_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.provider_plugins_delete),
            onConfirm = {
                scope.launch {
                    pluginManager.uninstall(target.id)
                    externalPlugins = pluginManager.list()
                }
                pendingDeleteExternal = null
            },
            destructive = true,
        )
    }
    // Provider 插件删除确认
    pendingDeleteProvider?.let { target ->
        MuseDialog(
            onDismissRequest = { pendingDeleteProvider = null },
            title = stringResource(R.string.provider_plugins_delete),
            content = {
                Text(
                    text = stringResource(R.string.provider_plugins_delete_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.provider_plugins_delete),
            onConfirm = {
                scope.launch {
                    registry.unregister(target.id)
                    providerPlugins = registry.list()
                }
                pendingDeleteProvider = null
            },
            destructive = true,
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
    )
}

@Composable
private fun InstalledPluginRow(
    plugin: PluginManager.InstalledPlugin,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.itemGap),
    ) {
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
                Text(
                    text = stringResource(R.string.plugin_summary, plugin.id, plugin.version, plugin.tools.size),
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
            }
            Text(
                text = stringResource(if (plugin.enabled) R.string.skill_enabled else R.string.skill_disabled),
                style = MaterialTheme.typography.labelMedium,
                color = if (plugin.enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(MusePaddings.itemGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            MuseCapsuleButton(
                text = stringResource(if (plugin.enabled) R.string.skill_disabled else R.string.skill_enabled),
                onClick = onToggle,
                modifier = Modifier.weight(1f),
                variant = IosCapsuleButtonVariant.Secondary,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.provider_plugins_delete),
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                variant = IosCapsuleButtonVariant.Secondary,
            )
        }
    }
}

@Composable
private fun PluginCard(
    plugin: ProviderPlugin,
    onDelete: () -> Unit,
    onConvert: () -> Unit,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TablerIcons.Puzzle,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { onConvert() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = TablerIcons.SwitchHorizontal,
                        contentDescription = stringResource(R.string.provider_plugins_convert),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Spacer(Modifier.size(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.provider_plugins_convert),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.size(MusePaddings.contentGap))
            MuseTactileButton(
                icon = TablerIcons.Trash,
                onClick = onDelete,
                contentDescription = stringResource(R.string.provider_plugins_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

