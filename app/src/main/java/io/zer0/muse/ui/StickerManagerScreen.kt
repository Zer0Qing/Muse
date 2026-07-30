package io.zer0.muse.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.zer0.muse.R
import io.zer0.muse.data.sticker.StickerItem
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * 表情包管理页 — 展示贴纸库分类与贴纸列表。
 *
 * 数据来源: [StickerLibraryRepository]。
 * 支持: 分类列表 / 导入 ZIP / 删除贴纸。
 */
@Composable
fun StickerManagerScreen(
    onBack: () -> Unit,
) {
    val repo: StickerLibraryRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var categories by remember { mutableStateOf<List<String>>(emptyList()) }
    var stickers by remember { mutableStateOf<List<StickerItem>>(emptyList()) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    // 导入后自增,触发 LaunchedEffect 重新加载
    var refreshTrigger by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }

    // ZIP 导入 launcher
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            repo.importZip(uri)
                .onSuccess { count ->
                    MuseToast.show(context.getString(R.string.settings_sticker_imported, count))
                    refreshTrigger++
                }
                .onError { msg, _ ->
                    MuseToast.show(context.getString(R.string.settings_sticker_import_failed, msg))
                }
            importing = false
        }
    }

    LaunchedEffect(refreshTrigger) {
        categories = repo.listCategories()
        stickers = repo.listStickers(selectedCategory)
    }

    Scaffold(
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseTactileButton(
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    onClick = onBack,
                    contentDescription = stringResource(R.string.action_back),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = "表情包",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${stickers.size} 个贴纸",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        },
        floatingActionButton = {
            MuseFloatingButton(
                icon = Icons.Filled.Add,
                onClick = {
                    importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                },
                contentDescription = "导入",
            )
        },
    ) { paddingValues ->
        if (stickers.isEmpty() && !importing) {
            // 空态
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(MusePaddings.emptyStateGap),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.EmojiEmotions,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "还没有贴纸",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "点击右下角按钮导入 ZIP 表情包",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = MusePaddings.screen),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { Spacer(Modifier.height(8.dp)) }

                // 分类过滤器
                if (categories.isNotEmpty()) {
                    item {
                        SectionLabel("分类")
                        CategoryChips(
                            categories = categories,
                            selected = selectedCategory,
                            onSelect = { cat ->
                                selectedCategory = cat
                                scope.launch {
                                    stickers = repo.listStickers(cat)
                                }
                            },
                        )
                    }
                }

                // 按分类分组展示
                val grouped = stickers.groupBy { it.category }
                grouped.forEach { (category, items) ->
                    item { SectionLabel("$category (${items.size})") }
                    items(items, key = { it.id }) { sticker ->
                        StickerRow(
                            sticker = sticker,
                            repo = repo,
                            onDelete = {
                                scope.launch {
                                    repo.deleteSticker(sticker.id)
                                    stickers = repo.listStickers(selectedCategory)
                                    categories = repo.listCategories()
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChips(
    categories: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    FlowRow(
        modifier = Modifier.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // "全部" chip
        val allSelected = selected == null
        Surface(
            modifier = Modifier.padding(0.dp),
            shape = MuseShapes.semiLarge,
            color = if (allSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
            onClick = { onSelect(null) },
        ) {
            Text(
                text = "全部",
                style = MaterialTheme.typography.labelMedium,
                color = if (allSelected) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        categories.forEach { category ->
            val isSelected = selected == category
            Surface(
                shape = MuseShapes.semiLarge,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                },
                onClick = { onSelect(category) },
            ) {
                Text(
                    text = category,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StickerRow(
    sticker: StickerItem,
    repo: StickerLibraryRepository,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(MuseCornerRadius.CARD.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 贴纸缩略图
            Surface(
                modifier = Modifier.size(48.dp),
                shape = MuseShapes.small,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = repo.getStickerFileByPath(sticker.relativePath),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = sticker.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = sticker.category,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
