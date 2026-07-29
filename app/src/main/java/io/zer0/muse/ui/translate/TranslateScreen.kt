package io.zer0.muse.ui.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.R
import io.zer0.muse.ui.common.IosTextField
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.0.31: 翻译页按新设计重写。
 *
 * 主要变化:
 *  - 顶部栏大标题居中
 *  - 语言选择器改为左右两个大胶囊,小标签 + 大语言名,中间交换按钮
 *  - 原文输入卡片:绿色"源语言"标签 + 字数统计 + 圆角输入区 + 底部操作栏
 *  - 译文结果卡片:绿色"目标语言"标签 + 译文文本 + 复制/朗读/交换/发送到会话
 *  - 翻译历史:标题行带时钟图标与清空按钮,记录项按设计使用"原/译"双行 + 语言流向 chip + 时间 + 收藏星标
 *  - 保留批量翻译、自定义风格、术语表、OCR 拍照翻译等能力,收纳在"更多"菜单中
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen(
    onBack: () -> Unit,
    onSendToNewChat: (String) -> Unit = {},
    viewModel: TranslateViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ocrManager: io.zer0.muse.doc.OcrManager = koinInject()
    var showClearHistoryDialog by remember { mutableStateOf(false) }
    var showBatchDialog by remember { mutableStateOf(false) }
    var showStyleDialog by remember { mutableStateOf(false) }
    var showCustomStyleDialog by remember { mutableStateOf(false) }
    var showGlossaryDialog by remember { mutableStateOf(false) }
    var ocrRecognizing by remember { mutableStateOf(false) }

    // 错误消息 → Toast 提示(单次消费,避免重复弹)
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            MuseToast.show(context.getString(R.string.translate_page_error_failed, msg))
            viewModel.consumeError()
        }
    }

    // OCR 相册选择
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        ocrRecognizing = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching { ocrManager.recognize(uri, context) }
                    .getOrElse {
                        MuseToast.show(context.getString(R.string.translate_page_ocr_failed))
                        ""
                    }
            }
            ocrRecognizing = false
            when {
                text.isBlank() -> MuseToast.show(context.getString(R.string.translate_page_ocr_empty))
                else -> {
                    viewModel.applyOcrText(text)
                    MuseToast.show(context.getString(R.string.translate_page_ocr_applied))
                }
            }
        }
    }

    Scaffold(
        topBar = { TranslateTopBar(onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.contentGap),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
        ) {
            // ── 源/目标语言选择条 ──
            LanguageSelectorBar(
                sourceLanguage = state.sourceLanguage,
                targetLanguage = state.targetLanguage,
                onSourceChange = { viewModel.updateSourceLanguage(it) },
                onTargetChange = { viewModel.updateTargetLanguage(it) },
                onSwap = { viewModel.swapLanguages() },
                enabled = !state.translating,
            )

            // ── 原文输入区 ──
            SourceInputCard(
                text = state.inputText,
                translating = state.translating,
                onTextChange = { viewModel.updateInput(it) },
                onPaste = {
                    val clipText = readClipboardText(context)
                    if (viewModel.paste(clipText)) {
                        MuseToast.show(context.getString(R.string.translate_page_pasted))
                    } else {
                        MuseToast.show(context.getString(R.string.translate_page_clipboard_empty))
                    }
                },
                onClear = { viewModel.clear() },
                onCopy = {
                    if (state.inputText.isNotBlank()) {
                        copyToClipboard(context, state.inputText)
                        MuseToast.show(context.getString(R.string.translate_page_copied))
                    }
                },
                onSpeak = {
                    if (!viewModel.speakSource()) {
                        MuseToast.show(context.getString(R.string.translate_page_tts_not_ready))
                    }
                },
                onTranslate = { viewModel.translate() },
                onOcr = { imagePicker.launch("image/*") },
                ocrRecognizing = ocrRecognizing,
                onBatch = { showBatchDialog = true },
                onGlossary = { showGlossaryDialog = true },
                onStyle = { showStyleDialog = true },
            )

            // ── 译文结果区 ──
            TranslationResultCard(
                translatedText = state.translatedText,
                translating = state.translating,
                onCopy = {
                    if (state.translatedText.isNotBlank()) {
                        copyToClipboard(context, state.translatedText)
                        MuseToast.show(context.getString(R.string.translate_page_copied))
                    }
                },
                onSpeak = {
                    if (!viewModel.speakTranslated()) {
                        MuseToast.show(context.getString(R.string.translate_page_tts_not_ready))
                    }
                },
                onUseAsInput = { viewModel.swapResultToInput() },
                onSendToNewChat = {
                    if (state.translatedText.isNotBlank()) {
                        onSendToNewChat(state.translatedText)
                    }
                },
            )

            // ── 翻译历史区 ──
            TranslateHistorySection(
                history = state.history,
                onItemClick = { item ->
                    viewModel.loadHistoryItem(item)
                    MuseToast.show(context.getString(R.string.translate_page_history_loaded))
                },
                onClearClick = { showClearHistoryDialog = true },
                onToggleFavorite = { item ->
                    viewModel.toggleFavorite(item)
                    MuseToast.show(
                        context.getString(
                            if (item.favorite) R.string.translate_page_favorite_removed
                            else R.string.translate_page_favorite_added
                        )
                    )
                },
            )

            Spacer(Modifier.height(24.dp))
        }
    }

    // 清空历史二次确认弹窗
    if (showClearHistoryDialog) {
        MuseDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            title = stringResource(R.string.translate_page_history_clear_confirm),
            content = {
                Text(
                    text = stringResource(R.string.translate_page_history_clear_confirm_msg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.translate_page_history_clear),
            onConfirm = {
                viewModel.clearHistory()
                showClearHistoryDialog = false
                MuseToast.show(context.getString(R.string.translate_page_history_cleared))
            },
            dismissText = stringResource(R.string.common_cancel),
            onDismiss = { showClearHistoryDialog = false },
            destructive = true,
        )
    }

    // 翻译风格选择弹窗
    if (showStyleDialog) {
        StylePickerDialog(
            currentStyle = state.translationStyle,
            customStyles = state.customStyles,
            onDismiss = { showStyleDialog = false },
            onSelect = { style ->
                viewModel.updateTranslationStyle(style)
                showStyleDialog = false
            },
            onManageCustomStyles = {
                showStyleDialog = false
                showCustomStyleDialog = true
            },
        )
    }

    // 批量翻译对话框
    if (showBatchDialog) {
        BatchTranslateDialog(
            translating = state.batchTranslating,
            results = state.batchResults,
            targetLanguage = state.targetLanguage,
            onDismiss = {
                viewModel.cancelBatchTranslation()
                viewModel.clearBatchResults()
                showBatchDialog = false
            },
            onTranslate = { texts ->
                viewModel.translateBatch(texts, state.targetLanguage)
            },
            onCopyResults = { results ->
                val merged = results.joinToString("\n\n") { it.translated }
                copyToClipboard(context, merged)
                MuseToast.show(context.getString(R.string.translate_page_copied))
            },
        )
    }

    // 自定义风格管理对话框
    if (showCustomStyleDialog) {
        CustomStyleDialog(
            customStyles = state.customStyles,
            onDismiss = { showCustomStyleDialog = false },
            onAdd = { name, prompt ->
                if (name.isBlank()) {
                    MuseToast.show(context.getString(R.string.translate_page_custom_style_empty_name))
                } else {
                    viewModel.addCustomStyle(name, prompt)
                    MuseToast.show(context.getString(R.string.translate_page_custom_style_added))
                }
            },
            onRemove = { name ->
                if (viewModel.removeCustomStyle(name)) {
                    MuseToast.show(context.getString(R.string.translate_page_custom_style_removed))
                }
            },
        )
    }

    // 术语表管理对话框
    if (showGlossaryDialog) {
        GlossaryDialog(
            glossary = state.glossary,
            onDismiss = { showGlossaryDialog = false },
            onAdd = { original, translated ->
                viewModel.addGlossaryEntry(original, translated)
                MuseToast.show(context.getString(R.string.translate_page_glossary_added))
            },
            onRemove = { original ->
                if (viewModel.removeGlossaryEntry(original)) {
                    MuseToast.show(context.getString(R.string.translate_page_glossary_removed))
                }
            },
        )
    }
}

/**
 * 翻译页顶栏 — 大标题居中 + 返回。
 */
@Composable
private fun TranslateTopBar(
    onBack: () -> Unit,
) {
    IosTopBar(
        title = stringResource(R.string.translate_page_title),
        onBack = onBack,
        largeTitle = true,
    )
}

/**
 * 顶部语言选择条 — 左右两个大胶囊,中间交换按钮。
 */
@Composable
private fun LanguageSelectorBar(
    sourceLanguage: String,
    targetLanguage: String,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onSwap: () -> Unit,
    enabled: Boolean,
) {
    var showSourcePicker by rememberSaveable { mutableStateOf(false) }
    var showTargetPicker by rememberSaveable { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        LanguageSelectorButton(
            label = stringResource(R.string.translate_page_source_language),
            value = sourceLanguage,
            onClick = { if (enabled) showSourcePicker = true },
            modifier = Modifier.weight(1f),
        )

        Surface(
            onClick = onSwap,
            enabled = enabled && sourceLanguage != TranslateViewModel.SOURCE_AUTO,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.size(40.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = stringResource(R.string.translate_page_swap),
                    tint = if (enabled && sourceLanguage != TranslateViewModel.SOURCE_AUTO) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(MuseIconSizes.iconMedium),
                )
            }
        }

        LanguageSelectorButton(
            label = stringResource(R.string.translate_page_target_language),
            value = targetLanguage,
            onClick = { if (enabled) showTargetPicker = true },
            modifier = Modifier.weight(1f),
        )
    }

    if (showSourcePicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.translate_page_source_language),
            selected = sourceLanguage,
            options = TranslateViewModel.SOURCE_LANGUAGES,
            onSelected = {
                onSourceChange(it)
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false },
        )
    }

    if (showTargetPicker) {
        LanguagePickerDialog(
            title = stringResource(R.string.translate_page_target_language),
            selected = targetLanguage,
            options = TranslateViewModel.TARGET_LANGUAGES,
            onSelected = {
                onTargetChange(it)
                showTargetPicker = false
            },
            onDismiss = { showTargetPicker = false },
        )
    }
}

/**
 * 语言选择胶囊按钮 — 小标签 + 大语言名 + 下拉箭头。
 */
@Composable
private fun LanguageSelectorButton(
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
        }
    }
}

/**
 * 语言选择弹窗 — 复用 MuseDialog 保持 iOS 风格。
 */
@Composable
private fun LanguagePickerDialog(
    title: String,
    selected: String,
    options: List<String>,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    MuseDialog(
        onDismissRequest = onDismiss,
        title = title,
        content = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                options.forEach { option ->
                    val isSelected = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelected(option) }
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = option,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        if (isSelected) {
                            Spacer(Modifier.width(8.dp))
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
        },
        confirmText = stringResource(R.string.common_cancel),
        onConfirm = onDismiss,
        onDismiss = onDismiss,
    )
}

/**
 * 原文输入大卡片 — 按设计图展示源语言标签、字数、输入区与底部操作栏。
 *
 * 批量翻译、术语表、翻译风格、OCR 等高级能力收纳在"更多"菜单中。
 */
@Composable
private fun SourceInputCard(
    text: String,
    translating: Boolean,
    onTextChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onTranslate: () -> Unit,
    onOcr: () -> Unit,
    ocrRecognizing: Boolean,
    onBatch: () -> Unit,
    onGlossary: () -> Unit,
    onStyle: () -> Unit,
) {
    var expandedMore by remember { mutableStateOf(false) }

    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            // 标题行：源语言标签 + 字数
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.translate_page_source_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${text.length}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // 输入框
            IosTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.translate_page_input_placeholder)) },
                minLines = 4,
                maxLines = 10,
                enabled = !translating,
            )

            // OCR 识别中提示
            AnimatedVisibility(
                visible = ocrRecognizing,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                    Spacer(Modifier.height(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.translate_page_ocr_recognizing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )

            // 底部工具栏 + 主翻译按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                    ActionIconButton(
                        icon = Icons.Filled.ContentPaste,
                        contentDescription = stringResource(R.string.translate_page_paste),
                        onClick = onPaste,
                        enabled = !translating,
                    )
                    ActionIconButton(
                        icon = Icons.Filled.Clear,
                        contentDescription = stringResource(R.string.translate_page_clear),
                        onClick = onClear,
                        enabled = !translating && text.isNotEmpty(),
                    )
                    ActionIconButton(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.translate_page_speak_source),
                        onClick = onSpeak,
                        enabled = !translating && text.isNotBlank(),
                    )
                    ActionIconButton(
                        icon = Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.translate_page_ocr),
                        onClick = onOcr,
                        enabled = !translating && !ocrRecognizing,
                    )
                    Box {
                        ActionIconButton(
                            icon = Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.translate_page_more),
                            onClick = { expandedMore = true },
                            enabled = !translating,
                        )
                        TranslateMoreMenu(
                            expanded = expandedMore,
                            onDismiss = { expandedMore = false },
                            onBatch = {
                                expandedMore = false
                                onBatch()
                            },
                            onGlossary = {
                                expandedMore = false
                                onGlossary()
                            },
                            onStyle = {
                                expandedMore = false
                                onStyle()
                            },
                        )
                    }
                }

                TranslateButton(
                    onClick = onTranslate,
                    enabled = !translating && text.isNotBlank(),
                )
            }
        }
    }
}

/**
 * "更多"下拉菜单 — 批量翻译、术语表、翻译风格。
 */
@Composable
private fun TranslateMoreMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onBatch: () -> Unit,
    onGlossary: () -> Unit,
    onStyle: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MuseShapes.semiLarge,
    ) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.translate_page_style_label)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Translate,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            },
            onClick = onStyle,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.translate_page_batch_translate)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Calculate,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            },
            onClick = onBatch,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.translate_page_glossary)) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.MenuBook,
                    contentDescription = null,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
            },
            onClick = onGlossary,
        )
    }
}

/**
 * 主翻译按钮 — 胶囊主色按钮。
 */
@Composable
private fun TranslateButton(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = MuseShapes.pill,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            disabledContentColor = MaterialTheme.colorScheme.outline,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.Translate,
            contentDescription = null,
            modifier = Modifier.size(MuseIconSizes.iconSmall),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.translate_page_translate),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * 译文结果大卡片 — 按设计图展示目标语言标签、译文与操作栏。
 */
@Composable
private fun TranslationResultCard(
    translatedText: String,
    translating: Boolean,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onUseAsInput: () -> Unit,
    onSendToNewChat: () -> Unit,
) {
    AnimatedVisibility(
        visible = translatedText.isNotBlank() || translating,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Surface(
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Text(
                    text = stringResource(R.string.translate_page_target_language),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )

                if (translating) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }

                SelectionContainer {
                    Text(
                        text = translatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                        ActionIconButton(
                            icon = Icons.Filled.ContentCopy,
                            contentDescription = stringResource(R.string.translate_page_copy),
                            onClick = onCopy,
                            enabled = !translating && translatedText.isNotBlank(),
                        )
                        ActionIconButton(
                            icon = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(R.string.translate_page_speak_result),
                            onClick = onSpeak,
                            enabled = !translating && translatedText.isNotBlank(),
                        )
                        ActionIconButton(
                            icon = Icons.Outlined.SwapHoriz,
                            contentDescription = stringResource(R.string.translate_page_use_as_input),
                            onClick = onUseAsInput,
                            enabled = !translating && translatedText.isNotBlank(),
                        )
                    }

                    SendToChatButton(
                        onClick = onSendToNewChat,
                        enabled = !translating && translatedText.isNotBlank(),
                    )
                }
            }
        }
    }
}

/**
 * "发送到会话" 按钮 — 胶囊形状,主色高亮。
 */
@Composable
private fun SendToChatButton(
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (enabled) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        contentColor = if (enabled) {
            MaterialTheme.colorScheme.onPrimary
        } else {
            MaterialTheme.colorScheme.outline
        },
        onClick = onClick,
        enabled = enabled,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
            Text(
                text = stringResource(R.string.translate_page_send_to_chat_session),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * 统一操作图标按钮 — 小号、无边框、禁用状态变灰。
 */
@Composable
private fun ActionIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(MuseIconSizes.touchTarget),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                // v1.0.28 修复: outline.copy(alpha=0.5f) 与背景过于接近,
                // 在浅色主题下看起来像空方框。改用 onSurfaceVariant 并保留 0.5f alpha,
                // 既表达 disabled 状态,又不会让用户误以为是无用占位。
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            },
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
    }
}

// ── 剪贴板辅助函数 ──

/** 从系统剪贴板读取纯文本(可能为空)。 */
private fun readClipboardText(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val clip = clipboard?.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/** 将文本写入系统剪贴板。 */
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
}

// ── 翻译历史区 ──

/**
 * 翻译历史区 — 按设计图展示标题行与最近 N 条记录。
 */
@Composable
private fun TranslateHistorySection(
    history: List<TranslateViewModel.TranslateHistoryItem>,
    onItemClick: (TranslateViewModel.TranslateHistoryItem) -> Unit,
    onClearClick: () -> Unit,
    onToggleFavorite: (TranslateViewModel.TranslateHistoryItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Icon(
                    imageVector = Icons.Outlined.History,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(MuseIconSizes.iconSmall),
                )
                Text(
                    text = stringResource(R.string.translate_page_history_section),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (history.isNotEmpty()) {
                TextButton(
                    onClick = onClearClick,
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        modifier = Modifier.size(MuseIconSizes.iconTiny),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(2.dp))
                    Text(
                        text = stringResource(R.string.translate_page_history_clear_short),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (history.isEmpty()) {
            Surface(
                shape = MuseShapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.translate_page_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp),
                )
            }
            return
        }

        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.itemGap)) {
            history.forEach { item ->
                TranslateHistoryItemCard(
                    item = item,
                    onClick = { onItemClick(item) },
                    onToggleFavorite = { onToggleFavorite(item) },
                )
            }
        }
    }
}

/**
 * 单条翻译历史卡片 — 设计图风格。
 *
 * 顶部:语言流向 chip + 相对时间 + 收藏星标
 * 中部:原 + 原文(单行省略)
 * 底部:译 + 译文(单行省略)
 */
@Composable
private fun TranslateHistoryItemCard(
    item: TranslateViewModel.TranslateHistoryItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val timeText = remember(item.timestamp) { formatHistoryTime(item.timestamp) }

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(
            modifier = Modifier.padding(MusePaddings.cardInnerMedium),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 源 → 目标语言 chip
                Surface(
                    shape = MuseShapes.pill,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = item.sourceLanguage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Icon(
                            imageVector = Icons.Outlined.SwapHoriz,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                        Text(
                            text = item.targetLanguage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(MuseIconSizes.touchTarget),
                    ) {
                        Icon(
                            imageVector = if (item.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = stringResource(
                                if (item.favorite) R.string.translate_page_favorite_remove
                                else R.string.translate_page_favorite_add
                            ),
                            tint = if (item.favorite) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                        )
                    }
                }
            }
            // 原 + 原文
            HistoryTextLine(label = stringResource(R.string.translate_page_history_source_short), text = item.sourceText)
            // 译 + 译文
            HistoryTextLine(label = stringResource(R.string.translate_page_history_translated_short), text = item.translatedText)
        }
    }
}

/** 历史卡片内的"标签 + 内容"单行。 */
@Composable
private fun HistoryTextLine(label: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 格式化历史时间戳为相对时间。 */
private fun formatHistoryTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    return when {
        diff < 60_000 -> "刚刚"
        diff < 60 * 60_000 -> "${diff / 60_000} 分钟前"
        diff < 24 * 60 * 60_000 -> "${diff / (60 * 60_000)} 小时前"
        else -> SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

// ── 翻译风格选择弹窗 ──

/**
 * 翻译风格选择弹窗 — 列出默认风格 + 自定义风格,并提供管理自定义风格入口。
 */
@Composable
private fun StylePickerDialog(
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
private fun BatchTranslateDialog(
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
                IosTextField(
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
private fun CustomStyleDialog(
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
                                            contentDescription = stringResource(R.string.translate_page_custom_style_remove),
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
                IosTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.translate_page_custom_style_name)) },
                    singleLine = true,
                )
                IosTextField(
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
private fun GlossaryDialog(
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
                                            contentDescription = stringResource(R.string.translate_page_glossary_remove),
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
                    IosTextField(
                        value = original,
                        onValueChange = { original = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.translate_page_glossary_original)) },
                        singleLine = true,
                    )
                    IosTextField(
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
