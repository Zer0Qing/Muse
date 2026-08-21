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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.memory.MemoryGraphView
import io.zer0.muse.ui.memory.MemoryGraphViewModel
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel

private enum class MemoryCenterTab { STREAM, FACTS, CONSTELLATION }

/**
 * 记忆观测站。
 *
 * 页面只保留三件用户真正关心的事：最近记住了什么、事实库里有什么、它们如何关联。
 * 编译、去重、反思仍由统一“整理记忆”入口触发，底层数据库和旧 API 保持不变。
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
    var tab by remember { mutableStateOf(MemoryCenterTab.STREAM) }
    var query by rememberSaveable { mutableStateOf("") }
    var editItem by remember { mutableStateOf<MemoryItem?>(null) }
    var showAddFact by rememberSaveable { mutableStateOf(false) }

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
                MemoryStationHeader(
                    factCount = state.factCount,
                    summaryCount = state.summaryCount,
                    lastUpdated = state.lastUpdatedAt,
                    organizing = organizing,
                    stage = organizeStage,
                    onOrganize = viewModel::organizeMemory,
                )
                MemoryScopeBar(
                    scopes = scopes,
                    selectedScope = selectedScope,
                    spaces = spaces,
                    selectedSpace = selectedSpace,
                    onScope = viewModel::selectScope,
                    onSpace = viewModel::selectSpace,
                )
                MemoryCenterTabs(
                    selected = tab,
                    onSelect = { tab = it },
                )
                when (tab) {
                    MemoryCenterTab.STREAM -> MemoryStreamTab(
                        state = state,
                        onOpenFacts = { tab = MemoryCenterTab.FACTS },
                    )
                    MemoryCenterTab.FACTS -> MemoryFactsTab(
                        state = state,
                        query = query,
                        onQuery = { query = it },
                        onAdd = { showAddFact = true },
                        onEdit = { editItem = it },
                        onDelete = viewModel::deleteFact,
                        onPin = { viewModel.toggleFactPinned(it.id) },
                    )
                    MemoryCenterTab.CONSTELLATION -> MemoryConstellationTab(
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
}

@Composable
private fun MemoryStationHeader(
    factCount: Int,
    summaryCount: Int,
    lastUpdated: String?,
    organizing: Boolean,
    stage: String?,
    onOrganize: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = factCount.toString(),
                    style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.memory_center_fact_count),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            Text(
                text = if (factCount == 0) stringResource(R.string.memory_center_empty_subtitle)
                else stringResource(R.string.memory_center_ready_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = stringResource(R.string.memory_center_summary_count, summaryCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                )
                lastUpdated?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = stringResource(R.string.memory_center_updated, it.take(10)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f),
                    )
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
private fun MemoryScopeBar(
    scopes: List<ScopeOption>,
    selectedScope: String?,
    spaces: List<io.zer0.memory.space.MemorySpaceEntity>,
    selectedSpace: String,
    onScope: (String?) -> Unit,
    onSpace: (String) -> Unit,
) {
    if (scopes.isEmpty() && spaces.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MusePaddings.screen),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        scopes.forEach { option ->
            FilterChip(
                selected = option.id == selectedScope,
                onClick = { onScope(option.id) },
                label = { Text(option.displayName) },
            )
        }
        spaces.forEach { space ->
            FilterChip(
                selected = space.id == selectedSpace,
                onClick = { onSpace(space.id) },
                label = { Text(space.name) },
            )
        }
    }
}

@Composable
private fun MemoryCenterTabs(
    selected: MemoryCenterTab,
    onSelect: (MemoryCenterTab) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = MusePaddings.screen, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        listOf(
            MemoryCenterTab.STREAM to R.string.memory_center_tab_stream,
            MemoryCenterTab.FACTS to R.string.memory_tab_facts,
            MemoryCenterTab.CONSTELLATION to R.string.memory_center_tab_constellation,
        ).forEach { (tab, label) ->
            FilterChip(selected = selected == tab, onClick = { onSelect(tab) }, label = { Text(stringResource(label)) })
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MuseLoadingState() }
        return
    }
    if (items.isEmpty()) {
        MemoryEmptyExplanation(
            title = stringResource(R.string.memory_center_empty_title),
            subtitle = stringResource(R.string.memory_center_empty_subtitle),
            actionText = stringResource(R.string.memory_center_open_facts),
            onAction = onOpenFacts,
        )
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.memory_center_stream_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
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
        MemorySearchBar(query = query, onQueryChange = onQuery, enabled = !state.isLoading, modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = MusePaddings.screen, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.memory_center_library_title), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            IconButton(onClick = onAdd) { Icon(Icons.Default.Add, contentDescription = stringResource(R.string.memory_screen_add_fact_cd)) }
        }
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MuseLoadingState() }
        } else if (items.isEmpty()) {
            MemoryEmptyExplanation(
                title = if (query.isBlank()) stringResource(R.string.memory_center_empty_title) else stringResource(R.string.memory_screen_empty_content),
                subtitle = stringResource(R.string.memory_center_empty_subtitle),
                actionText = stringResource(R.string.memory_add_fact),
                onAction = onAdd,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = MusePaddings.screen, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { "fact_${it.id}" }) { item ->
                    MemoryFactRow(item = item, onEdit = { onEdit(item) }, onDelete = { onDelete(item.id) }, onPin = { onPin(item) })
                }
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
private fun MemoryConstellationTab(
    scope: String?,
    spaceId: String,
    factCount: Int,
) {
    val graphViewModel: MemoryGraphViewModel = koinViewModel()
    val graphState by graphViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(scope, spaceId) { graphViewModel.load(scope, spaceId) }
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = MusePaddings.screen)) {
        Text(
            text = stringResource(R.string.memory_center_constellation_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        if (!graphState.isLoading && graphState.error == null && graphState.nodes.isEmpty()) {
            MemoryEmptyExplanation(
                title = stringResource(R.string.memory_center_empty_title),
                subtitle = stringResource(
                    if (factCount == 0) R.string.memory_center_constellation_empty
                    else R.string.memory_center_filter_empty,
                ),
                actionText = null,
                onAction = {},
            )
        } else {
            MemoryGraphView(state = graphState, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MemoryEmptyExplanation(
    title: String,
    subtitle: String,
    actionText: String?,
    onAction: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MuseEmptyState(title = title, subtitle = subtitle)
            actionText?.let { OutlinedButton(onClick = onAction, shape = MuseShapes.large) { Text(it) } }
        }
    }
}
