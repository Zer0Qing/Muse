@file:Suppress("FunctionNaming", "LongMethod", "LongParameterList", "UnusedParameter")
package io.zer0.muse.ui.translate


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.semiLarge

@Composable
internal fun StylePickerDialog(
    currentStyle: String,
    customStyles: List<TranslateViewModel.CustomStyle>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onManageCustomStyles: () -> Unit,
) {
    val styles = remember(customStyles) {
        TranslateViewModel.TRANSLATION_STYLES + customStyles.map { it.name }
    }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.translate_page_style_label),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                styles.forEach { style ->
                    val selected = style == currentStyle
                    Surface(
                        onClick = { onSelect(style) },
                        shape = MuseShapes.semiLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = style,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onPrimaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
                Surface(
                    onClick = {
                        onDismiss()
                        onManageCustomStyles()
                    },
                    shape = MuseShapes.semiLarge,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                        Text(
                            text = stringResource(R.string.translate_page_custom_style_add),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

// ── 批量翻译对话框 ──

/**
 * 批量翻译对话框 — 输入多段文本(每行一段),一次性翻译并展示结果。
 */
@Composable
internal fun BatchTranslateDialog(
    translating: Boolean,
    results: List<TranslateViewModel.BatchResult>,
    targetLanguage: String,
    onDismiss: () -> Unit,
    onTranslate: (List<String>) -> Unit,
    onCopyResults: (List<TranslateViewModel.BatchResult>) -> Unit,
) {
    var inputText by rememberSaveable { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.translate_page_batch_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                MuseTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    placeholder = { Text(stringResource(R.string.translate_page_batch_input_hint)) },
                    enabled = !translating,
                    minLines = 4,
                    maxLines = 10,
                )
                if (translating) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.translate_page_batch_translating),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                if (results.isNotEmpty()) {
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    Text(
                        text = stringResource(R.string.translate_page_batch_results_title, results.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        results.forEach { r ->
                            Surface(
                                shape = MuseShapes.large,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        text = r.original,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.outline,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = r.translated,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                    TextButton(onClick = { onCopyResults(results) }) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.translate_page_copy))
                    }
                }
            }
        },
        confirmText = stringResource(R.string.translate_page_batch_run),
        onConfirm = {
            val texts = inputText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (texts.isEmpty()) return@MuseDialog
            onTranslate(texts)
        },
        dismissText = stringResource(R.string.translate_page_batch_cancel),
        onDismiss = onDismiss,
    )
}

// ── 自定义风格管理对话框 ──

/**
 * 自定义风格管理对话框 — 添加/删除自定义风格。
 */
@Composable
internal fun CustomStyleDialog(
    customStyles: List<TranslateViewModel.CustomStyle>,
    onDismiss: () -> Unit,
    onAdd: (name: String, prompt: String) -> Unit,
    onRemove: (name: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.translate_page_custom_style_add),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                if (customStyles.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        customStyles.forEach { cs ->
                            Surface(
                                shape = MuseShapes.large,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = cs.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                        )
                                        if (cs.prompt.isNotEmpty()) {
                                            Text(
                                                text = cs.prompt,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    IconButton(
                                        onClick = { onRemove(cs.name) },
                                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = stringResource(
                                                R.string.translate_page_custom_style_remove,
                                            ),
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                MuseTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.translate_page_custom_style_name)) },
                    singleLine = true,
                )
                MuseTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.translate_page_custom_style_prompt)) },
                    minLines = 2,
                    maxLines = 4,
                )
            }
        },
        confirmText = stringResource(R.string.translate_page_custom_style_save),
        onConfirm = {
            if (name.isBlank()) return@MuseDialog
            onAdd(name.trim(), prompt.trim())
            name = ""
            prompt = ""
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}

// ── 术语表管理对话框 ──

/**
 * 术语表管理对话框 — 添加/删除原文→译文映射。
 */
@Composable
internal fun GlossaryDialog(
    glossary: Map<String, String>,
    onDismiss: () -> Unit,
    onAdd: (original: String, translated: String) -> Unit,
    onRemove: (original: String) -> Unit,
) {
    var original by rememberSaveable { mutableStateOf("") }
    var translated by rememberSaveable { mutableStateOf("") }
    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.translate_page_glossary_title),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.translate_page_glossary_count, glossary.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (glossary.isEmpty()) {
                    Text(
                        text = stringResource(R.string.translate_page_glossary_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        glossary.forEach { (src, dst) ->
                            Surface(
                                shape = MuseShapes.large,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = src,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Icon(
                                            imageVector = Icons.Outlined.SwapHoriz,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                                        )
                                        Text(
                                            text = dst,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    IconButton(
                                        onClick = { onRemove(src) },
                                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = stringResource(
                                                R.string.translate_page_glossary_remove,
                                            ),
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MuseTextField(
                        value = original,
                        onValueChange = { original = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.translate_page_glossary_original)) },
                        singleLine = true,
                    )
                    MuseTextField(
                        value = translated,
                        onValueChange = { translated = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.translate_page_glossary_translated)) },
                        singleLine = true,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.translate_page_glossary_add),
        onConfirm = {
            if (original.isBlank() || translated.isBlank()) return@MuseDialog
            onAdd(original.trim(), translated.trim())
            original = ""
            translated = ""
        },
        dismissText = stringResource(R.string.common_cancel),
        onDismiss = onDismiss,
    )
}
