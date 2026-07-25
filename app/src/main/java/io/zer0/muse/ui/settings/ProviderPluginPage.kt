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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import io.zer0.ai.plugin.ProviderPlugin
import io.zer0.ai.plugin.ProviderPluginRegistry
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.ui.common.IosFloatingButton
import io.zer0.muse.ui.common.IosTactileButton
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.WindowWidthClass
import io.zer0.muse.ui.common.rememberWindowWidthClass
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.mega
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * P2-10: Provider 插件管理页 — iOS 风格全屏工具页。
 *
 * 布局:
 *  - 顶部 [IosTopBar]:返回 + 标题「插件管理」
 *  - 中间 [LazyColumn]:已注册插件列表;每项展示 displayName + description,
 *    右侧两个图标按钮:删除 / 转为供应商
 *  - 底部 [FloatingActionButton]:从文件导入(SAF 选择 JSON 文件 → 拷贝到
 *    cacheDir → 调 [ProviderPluginRegistry.loadFromFile])
 *  - 空列表时展示 [EmptyState]
 *
 * 设计令牌:MuseShapes / MusePaddings,不使用 Material3 默认 Button / AlertDialog。
 *
 * @param onBack 返回回调(由 NavHost 注入,点击 TopBar 返回箭头触发)
 */
@Composable
fun ProviderPluginPage(
    onBack: () -> Unit,
) {
    val registry: ProviderPluginRegistry = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widthClass = rememberWindowWidthClass()

    // 插件列表(由 mutableStateMapOf 驱动,registry.list() 返回快照)
    // 用 mutableStateOf 包一层,在导入 / 删除后主动 refresh 触发重组
    var plugins by remember { mutableStateOf(registry.list()) }
    var importing by remember { mutableStateOf(false) }
    // 删除确认弹窗(承载待删插件)
    var pendingDelete by remember { mutableStateOf<ProviderPlugin?>(null) }

    /**
     * 从 SAF Uri 读取 JSON 文本,拷贝到 cacheDir 临时文件后调用 [ProviderPluginRegistry.loadFromFile]。
     * 成功后刷新本地列表快照。
     */
    suspend fun importFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.json")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法打开所选文件")
                val result = registry.loadFromFile(tempFile)
                result.onSuccess {
                    withContext(Dispatchers.Main) {
                        plugins = registry.list()
                        MuseToast.show(context.getString(R.string.provider_plugins_import_success))
                    }
                }.onFailure { e ->
                    val msg = e.message ?: e::class.simpleName ?: "unknown"
                    withContext(Dispatchers.Main) {
                        MuseToast.show(context.getString(R.string.provider_plugins_import_failed, msg), 3500)
                    }
                }
            } finally {
                // 临时文件读完后立即删除,避免 cacheDir 残留
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    /**
     * 把插件转为 ProviderConfig 并写入 SettingsRepository,
     * 用户可在「供应商」列表中看到并编辑 apiKey。
     */
    fun convertToProvider(plugin: ProviderPlugin) {
        scope.launch {
            val config = registry.toProviderConfig(plugin)
            settings.addProvider(config)
            MuseToast.show(context.getString(R.string.provider_plugins_import_success))
        }
    }

    // SAF 选择 JSON 文件 launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            runCatching { importFromUri(uri) }
            importing = false
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.provider_plugins_title),
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            IosFloatingButton(
                icon = Icons.Filled.Add,
                onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
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
                    // 首次导入中展示加载态
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
                    EmptyState(
                        icon = Icons.Outlined.Extension,
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
                        item {
                            Spacer(Modifier.height(MusePaddings.contentGap))
                        }
                        items(
                            items = plugins,
                            key = { it.id },
                        ) { plugin ->
                            PluginCard(
                                plugin = plugin,
                                onDelete = { pendingDelete = plugin },
                                onConvert = { convertToProvider(plugin) },
                            )
                        }
                    }
                }
            }
        }
    }

    // 删除确认弹窗(用 MuseDialog,不用 Material3 AlertDialog)
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
                registry.unregister(target.id)
                plugins = registry.list()
            },
            destructive = true,
        )
    }
}

/**
 * 单个插件卡片:展示 displayName + description,右侧两个图标按钮(删除 / 转为供应商)。
 *
 * 用 [Surface] + [MuseShapes.extraLarge] 圆角,跟随项目卡片视觉令牌。
 * 不使用 Material3 ListItem(避免引入 trailingContent 不灵活的局限)。
 */
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
            // 左侧:扩展图标 + 名称 + 描述
            Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
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
                // 模型数量 + baseUrl 摘要(辅助信息)
                Text(
                    text = "${plugin.models.size} 个模型 · ${plugin.baseUrl}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 右侧:转为供应商 + 删除
            // 转为供应商:用 Surface + clickable(非 Material3 Button)
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
                        imageVector = Icons.Outlined.SwapHoriz,
                        contentDescription = stringResource(R.string.provider_plugins_convert),
                        tint = MaterialTheme.colorScheme.primary,
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
            // v1.134 P0-6: 删除按钮用 IosTactileButton(48dp 触摸目标 + 无 ripple)
            IosTactileButton(
                icon = Icons.Outlined.Delete,
                onClick = onDelete,
                contentDescription = stringResource(R.string.provider_plugins_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}
