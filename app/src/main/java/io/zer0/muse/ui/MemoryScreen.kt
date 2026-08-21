@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.zer0.muse.ui

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.memory.MemoryGraphView
import io.zer0.muse.ui.memory.MemoryGraphViewModel
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

/**
 * 记忆观测站。
 *
 * 顶部概览卡 + 三段紧凑分段切换（记忆流 / 事实库 / 记忆星座），
 * 内容区只显示当前分段，避免单页无限长列表，也避免横向滚动的筛选胶囊。
 */
@Composable
fun MemoryScreen(
    onBack: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    viewModel: MemoryViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scopes by viewModel.availableScopes.collectAsStateWithLifecycle()
    val spaces by viewModel.availableSpaces.collectAsStateWithLifecycle()
    val selectedScope by viewModel.selectedScope.collectAsStateWithLifecycle()
    val selectedSpace by viewModel.selectedSpaceId.collectAsStateWithLifecycle()
    val organizing by viewModel.organizeRunning.collectAsStateWithLifecycle()
    val organizeStage by viewModel.organizeStage.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val widthClass = rememberWindowWidthClass()
    var tab by remember { mutableIntStateOf(0) } // 0=记忆流 1=事实库 2=星座
    var query by remember { mutableStateOf("") }
    var editItem by remember { mutableStateOf<MemoryItem?>(null) }
    var showAddFact by remember { mutableStateOf(false) }
    var showFilter by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        delay(300)
        viewModel.search(query)
    }
    LaunchedEffect(viewModel.organizeResult) {
        val result = viewModel.organizeResult.value ?: return@LaunchedEffect
        if (result.startsWith("done:")) {
            val merged = result.removePrefix("done:").toIntOrNull() ?: 0
            MuseToast.show(
                if (merged == 0) {
                    context.getString(R.string.memory_organize_stage_no_duplicates)
                } else {
                    context.getString(R.string.memory_organize_stage_complete, merged)
                },
            )
        } else {
            MuseToast.show(context.getString(R.string.memory_organize_stage_failed))
        }
        viewModel.consumeOrganizeResult()
    }

    Scaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.memory_screen_title),
                onBack = onBack,
                largeTitle = true,
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings_memory_page_title),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (widthClass == WindowWidthClass.Expanded) Modifier.widthIn(max = 760.dp) else Modifier),
            ) {
                MemoryOverviewCard(
                    factCount = state.factCount,
                    organizing = organizing,
                    stage = organizeStage,
                    scopeLabel = scopes.firstOrNull { it.id == selectedScope }?.displayName
                        ?: stringResource(R.string.memory_center_scope_all),
                    spaceLabel = spaces.firstOrNull { it.id == selectedSpace }?.name
                        ?: stringResource(R.string.memory_center_space_default),
                    onOrganize = viewModel::organizeMemory,
                    onOpenFilter = { showFilter = true },
                )
                MemorySegmentedTabs(selected = tab, onSelect = { tab = it })
                when (tab) {
                    0 -> MemoryStreamTab(state = state, onOpenFacts = { tab = 1 })
                    1 -> MemoryFactsTab(
                        state = state,
                        query = query,
                        onQuery = { query = it },
                        onAdd = { showAddFact = true },
                        onEdit = { editItem = it },
                        onDelete = viewModel::deleteFact,
                        onPin = { viewModel.toggleFactPinned(it.id) },
                    )
                    else -> MemoryConstellationTab(
                        scope = selectedScope,
                        spaceId = selectedSpace,
                        factCount = state.factCount,
                    )
                }
            }
        }
    }

    editItem?.let { item ->
        FactEditDialog(
            title = stringResource(R.string.memory_screen_edit_fact),
            initialContent = item.content,
            onDismiss = { editItem = null },
            onConfirm = { content ->
                viewModel.editFact(item.id, content)
                editItem = null
            },
        )
    }
    if (showAddFact) {
        AddFactDialog(
            onDismiss = { showAddFact = false },
            onConfirm = { content ->
                viewModel.addFact(content)
                showAddFact = false
            },
        )
    }
    if (showFilter) {
        MuseBottomSheet(onDismissRequest = { showFilter = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.memory_center_filter_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                )
                Text(
                    text = stringResource(R.string.memory_center_filter_scope),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                scopes.forEach { option ->
                    MemoryFilterRow(
                        label = option.displayName,
                        selected = option.id == selectedScope,
                        onClick = { viewModel.selectScope(option.id); showFilter = false },
                    )
                }
                Text(
                    text = stringResource(R.string.memory_center_filter_space),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                spaces.forEach { space ->
                    MemoryFilterRow(
                        label = space.name,
                        selected = space.id == selectedSpace,
                        onClick = { viewModel.selectSpace(space.id); showFilter = false },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun MemorySegmentedTabs(
    selected: Int,
    onSelect: (Int) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 12.dp),
    ) {
        val options = listOf(
            R.string.memory_center_tab_stream,
            R.string.memory_tab_facts,
            R.string.memory_center_tab_constellation,
        )
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selected == index,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
            ) {
                Text(stringResource(label), maxLines = 1)
            }
        }
    }
}

@Composable
private fun MemoryOverviewCard(
    factCount: Int,
    organizing: Boolean,
    stage: String?,
    scopeLabel: String,
    spaceLabel: String,
    onOrganize: () -> Unit,
    onOpenFilter: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = factCount.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.memory_center_fact_count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    modifier = Modifier.clip(MuseShapes.medium).clickable(onClick = onOpenFilter),
                    shape = MuseShapes.medium,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.memory_center_scope_line, scopeLabel, spaceLabel),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Outlined.FilterList,
                            contentDescription = stringResource(R.string.memory_center_filter_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
            OutlinedButton(
                onClick = onOrganize,
                enabled = !organizing,
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.large,
            ) {
                if (organizing) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    when (stage) {
                        "prepare" -> stringResource(R.string.memory_organize_stage_prepare)
                        "compile" -> stringResource(R.string.memory_organize_stage_compile)
                        "dedup" -> stringResource(R.string.memory_organize_stage_dedup)
                        else -> stringResource(R.string.memory_organize_action)
                    },
                )
            }
        }
    }
}

@Composable
private fun MemoryStreamTab(
    state: MemoryUiState,
    onOpenFacts: () -> Unit,
) {
    val items = state.factItems.sortedByDescending { it.createdAt ?: it.time.orEmpty() }
    if (state.isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            io.zer0.muse.ui.common.state.MuseLoadingState()
        }
        return
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.memory_center_empty_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(onClick = onOpenFacts, shape = MuseShapes.large) {
                    Text(stringResource(R.string.memory_center_open_facts))
                }
            }
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { "stream_${it.id}" }) { item -> MemoryFactRow(item = item) }
    }
}

@Composable
private fun MemoryFactsTab(
    state: MemoryUiState,
    query: String,
    onQuery: (String) -> Unit,
    onAdd: () -> Unit,
    onEdit: (MemoryItem) -> Unit,
    onDelete: (String) -> Unit,
    onPin: (MemoryItem) -> Unit,
) {
    val items = if (query.isBlank()) state.factItems else state.searchResults
    Column(modifier = Modifier.fillMaxSize()) {
        MemorySearchBar(
            query = query,
            onQueryChange = onQuery,
            enabled = !state.isLoading,
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.memory_center_library_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
            IconButton(onClick = onAdd) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_screen_add_fact_cd))
            }
        }
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                io.zer0.muse.ui.common.state.MuseLoadingState()
            }
        } else if (items.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = if (query.isBlank()) stringResource(R.string.memory_center_empty_subtitle)
                        else stringResource(R.string.memory_screen_empty_content),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    OutlinedButton(onClick = onAdd, shape = MuseShapes.large) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.memory_add_fact))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { "lib_${it.id}" }) { item ->
                    MemoryFactRow(
                        item = item,
                        onEdit = { onEdit(item) },
                        onDelete = { onDelete(item.id) },
                        onPin = { onPin(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemoryConstellationTab(
    scope: String?,
    spaceId: String,
    factCount: Int,
) {
    val graphViewModel: MemoryGraphViewModel = koinViewModel()
    val graphState by graphViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(scope, spaceId) { graphViewModel.load(scope, spaceId) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = MusePaddings.screen, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.memory_center_constellation_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MuseShapes.extraLarge),
        ) {
            if (graphState.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    io.zer0.muse.ui.common.state.MuseLoadingState()
                }
            } else if (graphState.nodes.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(
                            if (factCount == 0) R.string.memory_center_constellation_empty
                            else R.string.memory_center_filter_empty,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                MemoryGraphView(state = graphState, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun MemoryFactRow(
    item: MemoryItem,
    onEdit: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onPin: (() -> Unit)? = null,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.title.ifBlank { item.content },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.pinnedAt != null) Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
            }
            if (item.title != item.content) {
                Text(item.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.category?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                item.time?.takeIf { it.isNotBlank() }?.let { Text(it.take(10), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline) }
                Spacer(Modifier.weight(1f))
                onPin?.let { Text(stringResource(R.string.memory_menu_pin), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(MuseShapes.small).clickable { it() }.padding(4.dp)) }
                onEdit?.let { Text(stringResource(R.string.memory_menu_edit), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.clip(MuseShapes.small).clickable { it() }.padding(4.dp)) }
                onDelete?.let { Text(stringResource(R.string.memory_menu_delete), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.clip(MuseShapes.small).clickable { it() }.padding(4.dp)) }
            }
        }
    }
}

@Composable
private fun MemoryFilterRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clip(MuseShapes.medium).clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (selected) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
