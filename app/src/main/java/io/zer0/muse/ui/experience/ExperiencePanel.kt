package io.zer0.muse.ui.experience

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import io.zer0.muse.ui.common.IosFloatingButton
import io.zer0.muse.ui.common.IosSwitch
import io.zer0.muse.ui.common.IosTopBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.experience.ExperienceEntity

/**
 * 经验面板(openhanako experience UI 移植版)。
 *
 * 浏览、搜索、新增和删除经验。
 * 通过经验开关与设置页集成。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExperiencePanel(
    experiences: List<ExperienceEntity>,
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onAdd: (category: String, content: String) -> Unit,
    onDelete: (id: String) -> Unit,
    onBack: () -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }

    val filtered = remember(experiences, searchQuery, selectedCategory) {
        var list = experiences
        if (searchQuery.isNotBlank()) {
            list = list.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.content.contains(searchQuery, ignoreCase = true)
            }
        }
        if (selectedCategory != null) {
            list = list.filter { it.category == selectedCategory }
        }
        list
    }

    val categories = remember(experiences) {
        experiences.map { it.category }.distinct().sorted()
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.experience_title),
                onBack = onBack,
                actions = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.experience_enabled), style = MaterialTheme.typography.labelSmall)
                        IosSwitch(checked = enabled, onCheckedChange = onToggleEnabled)
                    }
                },
            )
        },
        floatingActionButton = {
            if (enabled) {
                IosFloatingButton(
                    icon = Icons.Default.Add,
                    onClick = { showAddDialog = true },
                    contentDescription = stringResource(R.string.experience_add_cd),
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text(stringResource(R.string.experience_search_hint)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                singleLine = true,
            )

            // Category chips
            if (categories.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    CategoryChip(
                        label = "All",
                        selected = selectedCategory == null,
                        onClick = { selectedCategory = null },
                    )
                    for (cat in categories) {
                        CategoryChip(
                            label = cat,
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = if (selectedCategory == cat) null else cat },
                        )
                    }
                }
            }

            // 列表
            if (filtered.isEmpty()) {
                Text(
                    text = if (experiences.isEmpty()) "No experiences yet. The AI will record lessons learned here."
                    else "No matching experiences found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 32.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { entity ->
                        ExperienceCard(entity = entity, onDelete = { onDelete(entity.id) })
                    }
                }
            }
        }
    }

    // 新增对话框
    if (showAddDialog) {
        AddExperienceDialog(
            categories = categories,
            onDismiss = { showAddDialog = false },
            onConfirm = { cat, content ->
                onAdd(cat, content)
                showAddDialog = false
            },
        )
    }
}

@Composable
private fun ExperienceCard(entity: ExperienceEntity, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entity.title,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = entity.category,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error)
                }
            }
            Text(
                text = entity.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AddExperienceDialog(
    categories: List<String>,
    onDismiss: () -> Unit,
    onConfirm: (category: String, content: String) -> Unit,
) {
    var category by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.experience_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text(stringResource(R.string.experience_add_category_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text(stringResource(R.string.experience_add_content_hint)) },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (category.isNotBlank() && content.isNotBlank()) onConfirm(category, content) },
            ) { Text(stringResource(R.string.experience_add_confirm)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.experience_add_cancel)) }
        },
    )
}
