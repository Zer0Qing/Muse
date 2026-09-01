package io.zer0.muse.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import io.zer0.muse.data.cover.CoverItem
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.data.cover.CoverLibraryRepository
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.theme.MuseCornerRadius
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.0.53: 封面库管理页(对标 Beautify 封面工作流)。
 *
 * 功能:
 *  - 网格展示全部封面(3 列)
 *  - 点击选中(高亮边框),底部"使用此封面"回调 [onPick]
 *  - 长按删除(确认对话框)
 *  - FAB 从相册导入(SAF)
 *
 * @param onBack 返回回调
 * @param onPick 选中封面回调(参数为封面文件路径);null 表示仅管理不选择
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun CoverManagerScreen(
    onBack: () -> Unit,
    onPick: ((String) -> Unit)? = null,
) {
    val repo: CoverLibraryRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var covers by remember { mutableStateOf<List<CoverItem>>(emptyList()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<CoverItem?>(null) }
    var refreshTrigger by remember { mutableStateOf(0) }
    var importing by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            repo.importFromUri(uri)
                .onSuccess {
                    MuseToast.show(context.getString(R.string.cover_imported))
                    refreshTrigger++
                }
                .onError { msg, _ ->
                    MuseToast.show(context.getString(R.string.cover_import_failed, msg))
                }
            importing = false
        }
    }

    LaunchedEffect(refreshTrigger) {
        covers = repo.listCovers()
    }

    MusePageScaffold(
        topBarHandlesInsets = false,
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
                    text = stringResource(R.string.cover_library_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.cover_count, covers.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
        },
        floatingActionButton = {
            MuseFloatingButton(
                icon = Icons.Filled.Add,
                onClick = { importLauncher.launch(arrayOf("image/*")) },
                contentDescription = stringResource(R.string.cover_import),
            )
        },
    ) { paddingValues ->
        if (covers.isEmpty() && !importing) {
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
                    imageVector = Icons.Filled.Image,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.cover_empty_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cover_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(120.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = MusePaddings.screen),
                contentPadding = PaddingValues(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(covers, key = { it.id }) { cover ->
                    CoverGridItem(
                        cover = cover,
                        repo = repo,
                        isSelected = cover.id == selectedId,
                        onClick = {
                            if (onPick != null) {
                                selectedId = cover.id
                            }
                        },
                        onLongClick = { pendingDelete = cover },
                    )
                }
                item {
                    Spacer(Modifier.height(72.dp))
                }
            }
        }
    }

    // 底部"使用此封面"栏(仅选择模式显示)
    if (onPick != null && selectedId != null) {
        val selected = covers.firstOrNull { it.id == selectedId }
        if (selected != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                shape = RoundedCornerShape(MuseCornerRadius.BUTTON.dp),
                color = MaterialTheme.colorScheme.primary,
                shadowElevation = 8.dp,
                onClick = {
                    onPick(repo.getCoverFile(selected).absolutePath)
                },
            ) {
                Text(
                    text = stringResource(R.string.cover_pick),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 14.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    // 删除确认对话框
    pendingDelete?.let { item ->
        MuseDialog(
            onDismissRequest = { pendingDelete = null },
            title = stringResource(R.string.cover_delete_confirm),
            content = { Text(item.fileName) },
            confirmText = stringResource(R.string.common_delete),
            onConfirm = {
                scope.launch {
                    repo.deleteCover(item.id)
                    if (item.id == selectedId) selectedId = null
                    pendingDelete = null
                    refreshTrigger++
                }
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { pendingDelete = null },
            destructive = true,
        )
    }
}

/**
 * 封面网格项 — 16:5 横幅裁切 + 选中高亮边框 + 删除角标。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CoverGridItem(
    cover: CoverItem,
    repo: CoverLibraryRepository,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.semiLarge)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 3.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = MuseShapes.semiLarge,
                    )
                } else {
                    Modifier
                }
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        AsyncImage(
            model = repo.getCoverFile(cover),
            contentDescription = cover.fileName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(88.dp),
        )
        // 删除角标(点击删除)
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.cover_delete),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(MuseShapes.pill)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
                    .clickable { onLongClick() }
                    .padding(4.dp)
                    .size(18.dp),
            )
        }
    }
}
