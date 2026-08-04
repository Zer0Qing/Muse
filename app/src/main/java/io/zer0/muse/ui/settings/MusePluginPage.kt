package io.zer0.muse.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Scaffold
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
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Puzzle
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * B6-01: 外部插件管理页。
 *
 * 从文件导入 `.muse-plugin` / ZIP 插件包，列表支持启用/禁用/卸载。
 * 插件工具注册进 skills 表后由 SkillExecutor 路由到 JS 沙盒执行。
 */
@Composable
fun MusePluginPage(
    onBack: () -> Unit,
) {
    val pluginManager: PluginManager = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widthClass = rememberWindowWidthClass()

    var plugins by remember { mutableStateOf(pluginManager.list()) }
    var importing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }

    suspend fun importFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.muse-plugin")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法打开所选文件")
                pluginManager.installFromFile(tempFile)
                withContext(Dispatchers.Main) {
                    plugins = pluginManager.list()
                    MuseToast.show(context.getString(R.string.provider_plugins_import_success))
                }
            } catch (e: Exception) {
                val msg = e.message ?: e::class.simpleName ?: "unknown"
                withContext(Dispatchers.Main) {
                    MuseToast.show(context.getString(R.string.provider_plugins_import_failed, msg), 3500)
                }
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            resultOf { importFromUri(uri) }
                .onError { msg, t -> Logger.w("MusePluginPage", "import failed: $msg", t) }
            importing = false
        }
    }

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.provider_plugins_title),
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            MuseFloatingButton(
                icon = TablerIcons.Plus,
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                contentDescription = stringResource(R.string.provider_plugins_import),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                importing && plugins.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                            strokeWidth = 2.dp,
                        )
                    }
                }
                plugins.isEmpty() -> {
                    MuseEmptyState(
                        icon = TablerIcons.Puzzle,
                        title = stringResource(R.string.provider_plugins_empty),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = innerPadding.calculateTopPadding()),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (widthClass == WindowWidthClass.Expanded) {
                                    Modifier.widthIn(max = 720.dp)
                                } else {
                                    Modifier
                                }
                            ),
                        contentPadding = PaddingValues(
                            top = innerPadding.calculateTopPadding(),
                            bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                    ) {
                        item { Spacer(Modifier.height(MusePaddings.contentGap)) }
                        items(
                            items = plugins,
                            key = { it.id },
                        ) { plugin ->
                            InstalledPluginRow(
                                plugin = plugin,
                                onToggle = {
                                    scope.launch {
                                        pluginManager.setEnabled(plugin.id, !plugin.enabled)
                                        plugins = pluginManager.list()
                                    }
                                },
                                onDelete = { pendingDelete = plugin },
                            )
                        }
                    }
                }
            }
        }
    }

    pendingDelete?.let { target ->
        MuseDialog(
            onDismissRequest = { pendingDelete = null },
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
                    plugins = pluginManager.list()
                }
                pendingDelete = null
            },
            destructive = true,
        )
    }
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
                    text = "${plugin.id} · v${plugin.version} · ${plugin.tools.size} 工具",
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
