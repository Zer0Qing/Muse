package io.zer0.muse.ui.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import io.zer0.muse.ui.common.IosDropdown
import io.zer0.muse.ui.common.IosTopBar
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.pill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.0.30: 独立翻译页 — iOS 风格全屏页面。
 *
 * 布局:
 *  - IosTopBar: 标题 + 返回按钮
 *  - 内容区(垂直滚动):
 *    - 顶部语言切换条(源语言 / 交换 / 目标语言),文字完整显示
 *    - 原文输入大卡片: 占位文案 + 风格选择 + 操作栏 + 主翻译按钮
 *    - 译文结果大卡片: 醒目展示译文 + 操作栏
 *    - 翻译历史区(轻量展示最近几次翻译)
 *
 * 新增回调 [onSendToNewChat] 允许将原文或译文快速发送到一个新的聊天会话,
 * 由调用方负责创建会话、填充输入并跳转。
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
    // v1.0.30 gap4.4: 批量翻译对话框
    var showBatchDialog by remember { mutableStateOf(false) }
    // v1.0.30 gap4.5: 自定义风格对话框
    var showCustomStyleDialog by remember { mutableStateOf(false) }
    // v1.0.30 gap4.6: 术语表对话框
    var showGlossaryDialog by remember { mutableStateOf(false) }
    // v1.0.30 gap4.7: OCR 识别中标志
    var ocrRecognizing by remember { mutableStateOf(false) }

    // 错误消息 → Toast 提示(单次消费,避免重复弹)
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let { msg ->
            MuseToast.show(context.getString(R.string.translate_page_error_failed, msg))
            viewModel.consumeError()
        }
    }

    // v1.0.30 gap4.7: 相册图片选择 → OCR 识别 → 填入输入框
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
                translationStyle = state.translationStyle,
                customStyles = state.customStyles,
                glossaryCount = state.glossary.size,
                onTextChange = { viewModel.updateInput(it) },
                onStyleChange = { viewModel.updateTranslationStyle(it) },
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
                // gap4.4: 批量翻译入口
                onBatchTranslate = { showBatchDialog = true },
                // gap4.5: 自定义风格管理入口
                onManageCustomStyles = { showCustomStyleDialog = true },
                // gap4.6: 术语表管理入口
                onManageGlossary = { showGlossaryDialog = true },
                // gap4.7: OCR 拍照翻译入口
                onOcr = { imagePicker.launch("image/*") },
                ocrRecognizing = ocrRecognizing,
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

            // 底部留白
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

    // gap4.4: 批量翻译对话框
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

    // gap4.5: 自定义风格管理对话框
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

    // gap4.6: 术语表管理对话框
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
 * 翻译页顶栏 — 标题 + 返回。
 */
@Composable
private fun TranslateTopBar(
    onBack: () -> Unit,
) {
    IosTopBar(
        title = stringResource(R.string.translate_page_title),
        onBack = onBack,
    )
}

/**
 * 顶部语言选择条 — 源语言 + 交换 + 目标语言。
 *
 * 使用自定义按钮避免 IosDropdown 在狭小空间内文字截断,确保"自动检测"等语言名完整显示。
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

    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LanguageSelectorButton(
                label = stringResource(R.string.translate_page_source_language),
                value = sourceLanguage,
                onClick = { if (enabled) showSourcePicker = true },
                modifier = Modifier.weight(1f),
            )

            IconButton(
                onClick = onSwap,
                enabled = enabled && sourceLanguage != TranslateViewModel.SOURCE_AUTO,
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.SwapHoriz,
                    contentDescription = stringResource(R.string.translate_page_swap),
                    tint = if (enabled && sourceLanguage != TranslateViewModel.SOURCE_AUTO) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    modifier = Modifier.size(24.dp),
                )
            }

            LanguageSelectorButton(
                label = stringResource(R.string.translate_page_target_language),
                value = targetLanguage,
                onClick = { if (enabled) showTargetPicker = true },
                modifier = Modifier.weight(1f),
            )
        }
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
 * 语言选择按钮 — 两行文本书写(label/value) + 下拉箭头,点击弹出选择器。
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
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                    style = MaterialTheme.typography.bodyLarge,
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
                modifier = Modifier.size(22.dp),
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
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
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
 * 原文输入大卡片 — 输入区域 + 风格选择 + 工具栏 + 主翻译按钮。
 *
 * v1.0.30 gap4.4 ~ gap4.7: 工具栏新增"批量翻译/拍照翻译/术语表"图标按钮,
 * 风格选择区显示默认风格 + 用户自定义风格,并提供"+"入口管理自定义风格。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceInputCard(
    text: String,
    translating: Boolean,
    translationStyle: String,
    customStyles: List<TranslateViewModel.CustomStyle>,
    glossaryCount: Int,
    onTextChange: (String) -> Unit,
    onStyleChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onTranslate: () -> Unit,
    onBatchTranslate: () -> Unit,
    onManageCustomStyles: () -> Unit,
    onManageGlossary: () -> Unit,
    onOcr: () -> Unit,
    ocrRecognizing: Boolean,
) {
    Surface(
        shape = MuseShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
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
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.translate_page_input_placeholder)) },
                minLines = 4,
                maxLines = 10,
                enabled = !translating,
                shape = MuseShapes.large,
            )

            // 翻译风格选择 — 默认风格 + 自定义风格 + 添加按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.translate_page_style_label),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // gap4.5: 自定义风格管理入口
                TextButton(
                    onClick = onManageCustomStyles,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.translate_page_custom_style_add),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.translate_page_custom_style_add),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 默认风格
                TranslateViewModel.TRANSLATION_STYLES.forEach { style ->
                    StyleChip(
                        label = style,
                        selected = style == translationStyle,
                        enabled = !translating,
                        onClick = { onStyleChange(style) },
                    )
                }
                // gap4.5: 自定义风格
                customStyles.forEach { cs ->
                    StyleChip(
                        label = cs.name,
                        selected = cs.name == translationStyle,
                        enabled = !translating,
                        onClick = { onStyleChange(cs.name) },
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 工具栏 + 主翻译按钮
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
                        icon = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.translate_page_clear),
                        onClick = onClear,
                        enabled = !translating && text.isNotEmpty(),
                    )
                    ActionIconButton(
                        icon = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.translate_page_copy_source),
                        onClick = onCopy,
                        enabled = !translating && text.isNotBlank(),
                    )
                    ActionIconButton(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = stringResource(R.string.translate_page_speak_source),
                        onClick = onSpeak,
                        enabled = !translating && text.isNotBlank(),
                    )
                    // gap4.4: 批量翻译入口
                    ActionIconButton(
                        icon = Icons.Outlined.Calculate,
                        contentDescription = stringResource(R.string.translate_page_batch_translate),
                        onClick = onBatchTranslate,
                        enabled = !translating,
                    )
                    // gap4.6: 术语表入口(显示数量徽标)
                    Box {
                        ActionIconButton(
                            icon = Icons.Outlined.MenuBook,
                            contentDescription = stringResource(
                                R.string.translate_page_glossary_count, glossaryCount
                            ),
                            onClick = onManageGlossary,
                            enabled = true,
                        )
                        if (glossaryCount > 0) {
                            // 简单的角标显示数量
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(14.dp)
                                    .padding(0.dp),
                            ) {
                                Text(
                                    text = if (glossaryCount > 9) "9+" else "$glossaryCount",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(2.dp),
                                )
                            }
                        }
                    }
                    // gap4.7: OCR 拍照翻译入口
                    ActionIconButton(
                        icon = Icons.Outlined.PhotoCamera,
                        contentDescription = stringResource(R.string.translate_page_ocr),
                        onClick = onOcr,
                        enabled = !translating && !ocrRecognizing,
                    )
                }

                TranslateButton(
                    onClick = onTranslate,
                    enabled = !translating && text.isNotBlank(),
                )
            }
            // gap4.7: OCR 识别中提示
            if (ocrRecognizing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    text = stringResource(R.string.translate_page_ocr_recognizing),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/**
 * v1.0.30 gap4.5: 风格 chip — 默认风格与自定义风格共用。
 */
@Composable
private fun StyleChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = MuseShapes.pill,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        onClick = { if (enabled) onClick() },
        enabled = enabled,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp),
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
            modifier = Modifier.size(18.dp),
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
 * 译文结果大卡片 — 醒目展示译文 + 操作栏。
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
    AnimatedVisibility(visible = translatedText.isNotBlank() || translating) {
        Surface(
            shape = MuseShapes.extraLarge,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
            shadowElevation = 1.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(MusePaddings.cardInner),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.translate_page_target_language),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

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
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

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
 * "发送到新会话" 按钮 — 胶囊形状,主色高亮。
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = stringResource(R.string.translate_page_send_to_chat),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
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
        modifier = Modifier.size(40.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            },
            modifier = Modifier.size(22.dp),
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
 * 翻译历史区 — 展示最近 N 条翻译记录,点击加载到输入框重新翻译或查看。
 *
 * v1.0.30 gap4.3: 每条记录右上角增加星标按钮,可切换收藏状态。
 */
@Composable
private fun TranslateHistorySection(
    history: List<TranslateViewModel.TranslateHistoryItem>,
    onItemClick: (TranslateViewModel.TranslateHistoryItem) -> Unit,
    onClearClick: () -> Unit,
    onToggleFavorite: (TranslateViewModel.TranslateHistoryItem) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.History,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.translate_page_history_section),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        if (history.isNotEmpty()) {
            TextButton(onClick = onClearClick) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    text = stringResource(R.string.translate_page_history_clear),
                    style = MaterialTheme.typography.labelLarge,
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

    Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
        history.forEach { item ->
            TranslateHistoryItemCard(
                item = item,
                onClick = { onItemClick(item) },
                onToggleFavorite = { onToggleFavorite(item) },
            )
        }
    }
}

/**
 * 单条翻译历史卡片 — 双行(原文/译文)布局,顶部展示源→目标语言流向 chip 和相对时间。
 *
 * v1.0.30 gap4.3: 右上角增加星标按钮,点击切换收藏状态(不触发卡片点击)。
 */
@Composable
private fun TranslateHistoryItemCard(
    item: TranslateViewModel.TranslateHistoryItem,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    val timeText = remember(item.timestamp) { formatHistoryTime(item.timestamp) }
    val sourceLabel = stringResource(R.string.translate_page_history_source_label)
    val translatedLabel = stringResource(R.string.translate_page_history_translated_label)

    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // 源 → 目标语言 chip(低饱和)
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
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
                            modifier = Modifier.size(10.dp),
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
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // 相对时间
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                    // gap4.3: 收藏星标按钮
                    IconButton(
                        onClick = onToggleFavorite,
                        modifier = Modifier.size(28.dp),
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
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
            // 原文(单行省略号)
            HistoryTextLine(label = sourceLabel, text = item.sourceText)
            // 译文(单行省略号)
            HistoryTextLine(label = translatedLabel, text = item.translatedText)
        }
    }
}

/** 历史卡片内的"标签 + 内容"单行 — 标签灰色小号,内容主体色,超出省略号。 */
@Composable
private fun HistoryTextLine(label: String, text: String) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
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

/** 格式化历史时间戳为相对时间(刚刚 / N 分钟前 / 今天 HH:mm / 日期)。 */
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

// ── v1.0.30 gap4.4: 批量翻译对话框 ──

/**
 * 批量翻译对话框 — 输入多段文本(每行一段),一次性翻译并展示结果。
 *
 * 用户可关闭对话框(取消正在进行的翻译),也可在结果出来后一键复制全部译文。
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
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 240.dp),
                    placeholder = { Text(stringResource(R.string.translate_page_batch_input_hint)) },
                    enabled = !translating,
                    minLines = 4,
                    maxLines = 10,
                    shape = MuseShapes.large,
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
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(4.dp))
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

// ── v1.0.30 gap4.5: 自定义风格管理对话框 ──

/**
 * 自定义风格管理对话框 — 添加/删除自定义风格。
 *
 * 已添加的风格以列表项形式展示(名称 + 删除按钮),底部有"名称 + 指令 + 保存"添加区。
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
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = stringResource(R.string.translate_page_custom_style_remove),
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.translate_page_custom_style_name)) },
                    singleLine = true,
                    shape = MuseShapes.large,
                )
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.translate_page_custom_style_prompt)) },
                    minLines = 2,
                    maxLines = 4,
                    shape = MuseShapes.large,
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

// ── v1.0.30 gap4.6: 术语表管理对话框 ──

/**
 * 术语表管理对话框 — 添加/删除原文→译文映射。
 *
 * 已存在的术语以列表项形式展示(原文 → 译文 + 删除按钮),底部有添加输入区。
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
                                            modifier = Modifier.size(12.dp),
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
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.DeleteOutline,
                                            contentDescription = stringResource(R.string.translate_page_glossary_remove),
                                            tint = MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = original,
                        onValueChange = { original = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.translate_page_glossary_original)) },
                        singleLine = true,
                        shape = MuseShapes.large,
                    )
                    OutlinedTextField(
                        value = translated,
                        onValueChange = { translated = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.translate_page_glossary_translated)) },
                        singleLine = true,
                        shape = MuseShapes.large,
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
