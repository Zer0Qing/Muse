package io.zer0.muse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.view.KeyEvent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.foundation.layout.widthIn
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.state.MuseLoadingState
import io.zer0.muse.ui.common.surface.MuseDivider
import io.zer0.muse.ui.common.surface.MuseListItem
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.sample
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.core.MessageRole
import io.zer0.ai.core.UIMessage
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.artifact.ArtifactEntity
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.ui.chat.ToolApprovalCard
import io.zer0.muse.ui.chat.buildQuotedContent
import io.zer0.muse.ui.chat.SlashCommand
import io.zer0.muse.ui.speech.SpeechInput
import io.zer0.muse.ui.speech.TtsControllerWidget
import io.zer0.muse.ui.speech.VoiceConversationMode
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseDateFormats
import io.zer0.muse.ui.theme.semiLarge
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.taskcard.AgentPlan
import io.zer0.muse.ui.taskcard.AgentPlanStepStatus
import io.zer0.muse.perf.MessagePaginator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

/** v1.25: 委托给助手 Sheet 的两种触发模式。 */
internal sealed class DelegateSheetMode {
    /** 把提示前置到当前输入框,然后自动发送。 */
    data object Input : DelegateSheetMode()

    /** 引用某条消息,新建一条委托消息并自动发送。 */
    data class Message(val msg: UIMessage) : DelegateSheetMode()
}

/** B2-03: ChatScreen 的 sheet/弹窗状态集中管理。 */
internal class ChatSheetState {
    var showModelSheet by androidx.compose.runtime.mutableStateOf(false)
    var showKnowledgeSheet by androidx.compose.runtime.mutableStateOf(false)
    var showPromptTemplateSheet by androidx.compose.runtime.mutableStateOf(false)
    var showSessionSheet by androidx.compose.runtime.mutableStateOf(false)
    var showAssistantSwitchSheet by androidx.compose.runtime.mutableStateOf(false)
    var showDelegateSheet by androidx.compose.runtime.mutableStateOf<DelegateSheetMode?>(null)
    var showToolCallSheet by androidx.compose.runtime.mutableStateOf(false)
    var editingMessage by androidx.compose.runtime.mutableStateOf<io.zer0.ai.core.UIMessage?>(null)
    var editingUserMessage by androidx.compose.runtime.mutableStateOf<io.zer0.ai.core.UIMessage?>(null)
    var showExportSheet by androidx.compose.runtime.mutableStateOf(false)
    var asrTipDialogShown by androidx.compose.runtime.mutableStateOf(false)
    var showVoiceConversation by androidx.compose.runtime.mutableStateOf(false)
}


/**
 * B2-03: ChatScreen 的 sheet/弹窗宿主。状态由 [ChatSheetState] 持有,
 * ChatScreen 只负责触发,展示逻辑全部下沉到这里。
 */
@androidx.compose.runtime.Composable
internal fun ChatSheetHost(
    sheetState: ChatSheetState,
    viewModel: ChatViewModel,
    knowledgeDocs: List<io.zer0.muse.data.knowledge.KnowledgeDocEntity>,
    speechLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    ioScope: kotlinx.coroutines.CoroutineScope,
    onOpenPromptTemplateManager: () -> Unit,
) {
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

        // 阶段 5: 模型切换底部面板
        if (sheetState.showModelSheet) {
            ModelSwitchSheet(
                providers = uiState.providers,
                activeProviderId = uiState.activeProviderId,
                selectedModelId = uiState.selectedModelId,
                onPickProvider = viewModel::setActiveProvider,
                onPickModel = viewModel::setSelectedModel,
                onRefreshModels = viewModel::refreshModels,
                isFetchingModels = uiState.isFetchingModels,
                fetchModelsError = uiState.fetchModelsError,
                onDismiss = { sheetState.showModelSheet = false },
            )
        }
        // v1.58: Prompt 模板库选择
        if (sheetState.showPromptTemplateSheet) {
            MuseBottomSheet(
                onDismissRequest = { sheetState.showPromptTemplateSheet = false },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.contentGap),
                ) {
                    Text(
                        text = stringResource(R.string.chat_prompt_templates_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(MusePaddings.bubbleInner),
                    )
                    val templates = uiState.promptTemplates
                    val categories = templates.map { it.category }.distinct()
                    androidx.compose.foundation.lazy.LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                    ) {
                        categories.forEach { category ->
                            item(key = "cat_$category") {
                                Text(
                                    text = category,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 6.dp),
                                )
                            }
                            items(
                                templates.filter { it.category == category },
                                key = { it.id },
                            ) { template ->
                                Surface(
                                    onClick = {
                                        viewModel.insertPromptTemplate(template)
                                        sheetState.showPromptTemplateSheet = false
                                    },
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 10.dp),
                                    ) {
                                        Text(
                                            text = template.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = template.content.take(60).replace("\n", " "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(
                    onClick = {
                        sheetState.showPromptTemplateSheet = false
                        onOpenPromptTemplateManager()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.prompt_template_manage_entry))
                }
            }
        }
        // v1.94: 工具调用历史 sheet(InputBar 动态胶囊点击展开)
        // v1.97: 合并展示任务待办 + 工具调用历史,可滑动查看
        if (sheetState.showToolCallSheet) {
            val latestPlan = uiState.agentPlans.values.maxByOrNull { it.createdAt }
            MuseBottomSheet(
                onDismissRequest = { sheetState.showToolCallSheet = false },
            ) {
                ToolCallHistorySheet(
                    records = uiState.toolCallHistory,
                    agentPlan = latestPlan,
                )
            }
        }
        // v1.95: 系统语音识别首次使用提示(用户确认后调起系统 Intent)
        if (sheetState.asrTipDialogShown) {
            MuseDialog(
                onDismissRequest = { sheetState.asrTipDialogShown = false },
                title = stringResource(R.string.chat_asr_tip_title),
                content = {
                    Text(
                        text = stringResource(R.string.chat_asr_tip_message),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                confirmText = stringResource(R.string.chat_asr_tip_confirm),
                onConfirm = {
                    sheetState.asrTipDialogShown = false
                    resultOf {
                        speechLauncher.launch(SpeechInput.createIntent(context.getString(R.string.speech_speak_prompt)))
                    }.onError { msg, _ ->
                        // v1.98: 移除弹窗提示,静默处理
                        Logger.w("ChatScreen", "启动语音识别失败: $msg")
                    }
                },
                onDismiss = { sheetState.asrTipDialogShown = false },
            )
        }
        // v0.29 P1-6: 知识库文档选择(MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死)
        if (sheetState.showKnowledgeSheet) {
            MuseDialog(
                onDismissRequest = { sheetState.showKnowledgeSheet = false },
                title = stringResource(R.string.chat_knowledge_dialog_title),
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        // 仅展示用户可见文档(isInternal=true 的内部开发文档供 LLM 通过
                        // knowledge_search 查询,不在「引用知识库」选择器中暴露给用户)
                        val visibleDocs = knowledgeDocs.filterNot { it.isInternal }
                        if (visibleDocs.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_knowledge_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = MusePaddings.largeGap),
                            )
                        } else {
                            visibleDocs.forEach { doc ->
                                MuseListItem(
                                    headlineContent = { Text(doc.title, style = MaterialTheme.typography.bodyLarge) },
                                    supportingContent = {
                                        Text(
                                            stringResource(R.string.chat_knowledge_doc_meta, doc.fileType, doc.content.length),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.outline,
                                        )
                                    },
                                    modifier = Modifier.clickable {
                                        // v0.29 P1-6: 选中后在输入框插入 @文档名 标记
                                        val mention = "@${doc.title} "
                                        val current = viewModel.state.value.input
                                        viewModel.updateInput(if (current.isBlank()) mention else "$current\n$mention")
                                        sheetState.showKnowledgeSheet = false
                                    },
                                )
                            }
                        }
                    }
                },
                onConfirm = null,
                dismissText = stringResource(R.string.action_close),
                onDismiss = { sheetState.showKnowledgeSheet = false },
            )
        }
        // v0.29 P3-17: 会话快速切换(MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死)
        if (sheetState.showSessionSheet) {
            MuseDialog(
                onDismissRequest = { sheetState.showSessionSheet = false },
                title = stringResource(R.string.chat_switch_session_title),
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (uiState.sessions.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_no_sessions),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = MusePaddings.largeGap),
                            )
                        } else {
                            uiState.sessions.take(20).forEach { session ->
                                val isCurrent = session.id == uiState.currentSessionId
                                MuseListItem(
                                    headlineContent = {
                                        Text(
                                            text = session.title.ifBlank { stringResource(R.string.chat_new_session) },
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    },
                                    supportingContent = session.lastMessagePreview.takeIf { it.isNotBlank() }?.let {
                                        {
                                            Text(
                                                text = it,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    modifier = Modifier.clickable {
                                        viewModel.switchSession(session.id)
                                        sheetState.showSessionSheet = false
                                    },
                                )
                            }
                        }
                    }
                },
                onConfirm = null,
                dismissText = stringResource(R.string.action_close),
                onDismiss = { sheetState.showSessionSheet = false },
            )
        }
        // v1.136 T1: 对话内更换助手(标题长按触发)
        if (sheetState.showAssistantSwitchSheet) {
            val currentAssistantId = uiState.currentAssistant?.id
            MuseDialog(
                onDismissRequest = { sheetState.showAssistantSwitchSheet = false },
                title = stringResource(R.string.chat_switch_assistant_title),
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        if (uiState.assistants.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_switch_assistant_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = MusePaddings.largeGap),
                            )
                        } else {
                            uiState.assistants.forEach { assistant ->
                                val isCurrent = assistant.id == currentAssistantId
                                val unnamedAssistant = stringResource(R.string.chat_delegate_unnamed_assistant)
                                MuseListItem(
                                    leadingContent = {
                                        AssistantAvatar(
                                            assistant = assistant,
                                            avatarSize = 36.dp,
                                        )
                                    },
                                    headlineContent = {
                                        Text(
                                            text = assistant.name.takeIf { it.isNotBlank() } ?: unnamedAssistant,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = if (isCurrent) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                                        )
                                    },
                                    supportingContent = {
                                        // v1.136 T5: 优先显示 summary(助手简介),无则回退到 systemPrompt 截断
                                        val desc = assistant.summary.takeIf { it.isNotBlank() }
                                            ?: assistant.systemPrompt.take(60)
                                        if (desc.isNotBlank()) {
                                            Text(
                                                text = desc,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.outline,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    },
                                    trailingContent = if (isCurrent) {
                                        {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = stringResource(R.string.chat_switch_assistant_current),
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.size(18.dp),
                                            )
                                        }
                                    } else null,
                                    modifier = Modifier.clickable {
                                        if (!isCurrent) {
                                            // v1.0.54: 切换助手分派 — Agent Tab 切对话房间(该助手历史恢复/新建),
                                            //   任务 Tab 只换人(消息/历史绝不动)
                                            if (uiState.isAgentMode) {
                                                viewModel.switchAgentAssistant(assistant.id)
                                            } else {
                                                viewModel.setSessionAssistant(assistant.id)
                                            }
                                            val name = assistant.name.takeIf { it.isNotBlank() } ?: unnamedAssistant
                                            MuseToast.show(
                                                context.getString(R.string.chat_switch_assistant_applied, name)
                                            )
                                        }
                                        sheetState.showAssistantSwitchSheet = false
                                    },
                                )
                            }
                        }
                    }
                },
                onConfirm = null,
                dismissText = stringResource(R.string.action_close),
                onDismiss = { sheetState.showAssistantSwitchSheet = false },
            )
        }
        // v1.25: 委托给助手/团队选择(MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死)
        val delegateMode = sheetState.showDelegateSheet
        if (delegateMode != null) {
            val assistants = uiState.assistants
            val teams = uiState.multiAgentConfig.teams
            MuseDialog(
                onDismissRequest = { sheetState.showDelegateSheet = null },
                title = stringResource(R.string.chat_delegate_title),
                content = {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (assistants.isEmpty() && teams.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_delegate_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = MusePaddings.largeGap),
                            )
                        } else {
                            if (assistants.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_delegate_assistants),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(bottom = MusePaddings.contentGap),
                                )
                                assistants.forEach { assistant ->
                                    val unnamedAssistant = stringResource(R.string.chat_delegate_unnamed_assistant)
                                    MuseListItem(
                                        headlineContent = {
                                            Text(
                                                assistant.name.takeIf { it.isNotBlank() } ?: unnamedAssistant,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        },
                                        supportingContent = {
                                            val desc = assistant.systemPrompt.take(60)
                                            if (desc.isNotBlank()) {
                                                Text(
                                                    text = desc,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            val name = assistant.name.takeIf { it.isNotBlank() } ?: unnamedAssistant
                                            when (delegateMode) {
                                                is DelegateSheetMode.Input -> {
                                                    val current = viewModel.state.value.input
                                                    val prompt = context.getString(R.string.chat_delegate_assistant_prompt_input, name, current)
                                                    // H-S2: 先检查内容非空再 updateInput + send,避免读到旧空 input 的竞态
                                                    if (prompt.isNotBlank()) {
                                                        viewModel.updateInput(prompt)
                                                        sheetState.showDelegateSheet = null
                                                        viewModel.send()
                                                    }
                                                }
                                                is DelegateSheetMode.Message -> {
                                                    // M-S9: 委托空内容校验
                                                    if (delegateMode.msg.content.isBlank()) {
                                                        MuseToast.show(context.getString(R.string.chat_delegate_no_text))
                                                        return@clickable
                                                    }
                                                    val summary = delegateMode.msg.content.take(200)
                                                    val prompt = context.getString(R.string.chat_delegate_assistant_prompt_message, name)
                                                    val content = buildQuotedContent(summary, prompt)
                                                    // H-S2: 先检查内容非空再 updateInput + send
                                                    if (content.isNotBlank()) {
                                                        viewModel.updateInput(content)
                                                        sheetState.showDelegateSheet = null
                                                        viewModel.send()
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                            if (teams.isNotEmpty()) {
                                Text(
                                    text = stringResource(R.string.chat_delegate_teams),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.outline,
                                    modifier = Modifier.padding(top = MusePaddings.itemGap, bottom = MusePaddings.contentGap),
                                )
                                teams.forEach { team ->
                                    val unnamedTeam = stringResource(R.string.chat_delegate_unnamed_team)
                                    MuseListItem(
                                        headlineContent = {
                                            Text(
                                                team.name.takeIf { it.isNotBlank() } ?: unnamedTeam,
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        },
                                        supportingContent = {
                                            val desc = team.description.takeIf { it.isNotBlank() }
                                            if (desc != null && desc.isNotBlank()) {
                                                Text(
                                                    text = desc,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.outline,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                            }
                                        },
                                        modifier = Modifier.clickable {
                                            val name = team.name.takeIf { it.isNotBlank() } ?: unnamedTeam
                                            when (delegateMode) {
                                                is DelegateSheetMode.Input -> {
                                                    val current = viewModel.state.value.input
                                                    val prompt = context.getString(R.string.chat_delegate_team_prompt_input, name, current)
                                                    // H-S2: 先检查内容非空再 updateInput + send,避免读到旧空 input 的竞态
                                                    if (prompt.isNotBlank()) {
                                                        viewModel.updateInput(prompt)
                                                        sheetState.showDelegateSheet = null
                                                        viewModel.send()
                                                    }
                                                }
                                                is DelegateSheetMode.Message -> {
                                                    // M-S9: 委托空内容校验
                                                    if (delegateMode.msg.content.isBlank()) {
                                                        MuseToast.show(context.getString(R.string.chat_delegate_no_text))
                                                        return@clickable
                                                    }
                                                    val summary = delegateMode.msg.content.take(200)
                                                    val prompt = context.getString(R.string.chat_delegate_team_prompt_message, name)
                                                    val content = buildQuotedContent(summary, prompt)
                                                    // H-S2: 先检查内容非空再 updateInput + send
                                                    if (content.isNotBlank()) {
                                                        viewModel.updateInput(content)
                                                        sheetState.showDelegateSheet = null
                                                        viewModel.send()
                                                    }
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                onConfirm = null,
                dismissText = stringResource(R.string.action_close),
                onDismiss = { sheetState.showDelegateSheet = null },
            )
        }
        // 功能4: 导出格式选择(Markdown / HTML / PDF 三选一,iOS 风格分段选择器)
        if (sheetState.showExportSheet) {
            io.zer0.muse.ui.chat.ExportFormatPickerDialog(
                onDismiss = { sheetState.showExportSheet = false },
                onFormatSelected = { format ->
                    sheetState.showExportSheet = false
                    ioScope.launch {
                        when (format) {
                            io.zer0.muse.data.export.ExportFormat.MARKDOWN -> {
                                // Markdown:沿用原有逻辑,通过 ACTION_SEND 分享纯文本
                                val (mime, content) = viewModel.exportSession(
                                    io.zer0.muse.ui.chat.ExportFormat.MARKDOWN
                                )
                                shareText(context, mime, content)
                            }
                            io.zer0.muse.data.export.ExportFormat.HTML -> {
                                // HTML:导出为单文件,写入 cacheDir 后通过 FileProvider 分享
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val html = viewModel.exportSessionAsHtml()
                                    val file = writeExportFile(context, "muse-export", "html", html.toByteArray())
                                    shareFile(context, file, "text/html")
                                }
                            }
                            io.zer0.muse.data.export.ExportFormat.PDF -> {
                                // PDF:用 PdfDocument 渲染分页文档,通过 FileProvider 分享
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    val file = viewModel.exportSessionAsPdf(context)
                                    shareFile(context, file, "application/pdf")
                                }
                            }
                        }
                    }
                },
            )
        }
        // 编辑用户消息：独立弹窗编辑，不占用主输入栏
        sheetState.editingUserMessage?.let { msg ->
            // 前端修复 (持久化-8): 编辑草稿改 rememberSaveable,key 保持 msg.id
            var draft by rememberSaveable(msg.id) { mutableStateOf(msg.content) }
            MuseDialog(
                onDismissRequest = { sheetState.editingUserMessage = null },
                title = stringResource(R.string.edit_message_title),
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MuseTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 320.dp),
                            label = { Text(stringResource(R.string.edit_message_content_label)) },
                            maxLines = 10,
                        )
                    }
                },
                confirmText = stringResource(R.string.action_save),
                onConfirm = {
                    viewModel.applyUserEdit(msg.id.toString(), draft.trim())
                    sheetState.editingUserMessage = null
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { sheetState.editingUserMessage = null },
            )
        }
        // 编辑助手消息(MuseDialog 替代原 ModalBottomSheet,避免真机 scrim 卡死)
        sheetState.editingMessage?.let { msg ->
            // 前端修复 (持久化-8): 编辑草稿改 rememberSaveable,key 保持 msg.id
            var draft by rememberSaveable(msg.id) { mutableStateOf(msg.content) }
            MuseDialog(
                onDismissRequest = { sheetState.editingMessage = null },
                title = stringResource(R.string.edit_message_title),
                content = {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        MuseTextField(
                            value = draft,
                            onValueChange = { draft = it },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 120.dp, max = 320.dp),
                            label = { Text(stringResource(R.string.edit_message_content_label)) },
                            maxLines = 10,
                        )
                    }
                },
                confirmText = stringResource(R.string.action_save),
                onConfirm = {
                    viewModel.editAssistantMessage(msg.id, draft.trim())
                    sheetState.editingMessage = null
                },
                dismissText = stringResource(R.string.action_cancel),
                onDismiss = { sheetState.editingMessage = null },
            )
        }

    // v1.201: 委派暂停确认弹窗(绑定到 uiState.activePauseRequest)
    io.zer0.muse.ui.taskcard.DelegationConfirmDialog(
        pauseRequest = uiState.activePauseRequest,
        onSubmit = { response ->
            uiState.activePauseRequest?.let { req ->
                viewModel.submitPauseDecision(req.requestId, response)
            }
        },
    )

    // v1.49: Vosk 模型下载弹窗已移除(离线识别能力随之移除)

    // v1.43: 产物卡片查看弹窗
    uiState.selectedArtifact?.let { artifact ->
        io.zer0.muse.ui.artifact.ArtifactViewerDialog(
            artifact = artifact,
            onDismiss = { viewModel.dismissArtifactViewer() },
            onCopy = { text ->
                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("Muse Artifact", text)
                )
                MuseToast.show(context.getString(R.string.chat_copied_toast))
            },
        )
    }

    // 语音对话模式全屏覆盖层:点击 InputBar 中 RecordVoiceOver 图标触发,
    // 关闭时由 VoiceConversationMode 内部关闭按钮回调,ViewModel 资源在 onClose 中释放
    AnimatedVisibility(
        visible = sheetState.showVoiceConversation,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        VoiceConversationMode(
            onClose = { sheetState.showVoiceConversation = false },
            viewModel = viewModel,
        )
    }
}

/**
 * v1.94: 工具调用历史面板(底部 sheet 内容)。
 *
 * 展示当前会话期间所有工具调用记录(工具名 / 参数 / 结果 / 成功与否),
 * 由 InputBar 动态胶囊点击触发。
 */
@Composable
private fun ToolCallHistorySheet(
    records: List<ToolCallRecord>,
    agentPlan: AgentPlan? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(max = 480.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // v1.97: 任务待办优先展示
        val plan = agentPlan
        if (plan != null && plan.steps.isNotEmpty()) {
            Text(
                text = stringResource(R.string.chat_task_todo_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            plan.steps.forEachIndexed { idx, step ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = MusePaddings.labelVerticalGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusColor = when (step.status) {
                        AgentPlanStepStatus.DONE -> MaterialTheme.colorScheme.primary
                        AgentPlanStepStatus.FAILED -> MaterialTheme.colorScheme.error
                        AgentPlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
                        AgentPlanStepStatus.SKIPPED -> MaterialTheme.colorScheme.outline
                        AgentPlanStepStatus.PENDING -> MaterialTheme.colorScheme.outline
                    }
                    when (step.status) {
                        AgentPlanStepStatus.DONE -> {
                            Icon(Icons.Default.CheckCircle, null, tint = statusColor, modifier = Modifier.size(MusePaddings.screen))
                        }
                        AgentPlanStepStatus.FAILED -> {
                            Icon(TablerIcons.AlertCircle, null, tint = statusColor, modifier = Modifier.size(MusePaddings.screen))
                        }
                        else -> {
                            Box(
                                modifier = Modifier
                                    .size(MusePaddings.contentGap)
                                    .clip(CircleShape)
                                    .background(statusColor),
                            )
                        }
                    }
                    Spacer(Modifier.width(MusePaddings.contentGap))
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (step.status == AgentPlanStepStatus.PENDING || step.status == AgentPlanStepStatus.SKIPPED)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(step.status.labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                }
                if (idx < plan.steps.size - 1) {
                    MuseDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
        // 工具调用历史
        if (records.isNotEmpty()) {
            if (plan != null && plan.steps.isNotEmpty()) {
                Spacer(Modifier.height(MusePaddings.screen))
            }
            Text(
                text = stringResource(R.string.chat_tool_calls_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            records.forEachIndexed { idx, record ->
                ToolCallRecordItem(idx + 1, record)
                if (idx < records.size - 1) {
                    MuseDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                        thickness = 0.5.dp,
                    )
                }
            }
        }
        // 空状态
        if ((plan == null || plan.steps.isEmpty()) && records.isEmpty()) {
            Text(
                text = stringResource(R.string.chat_tool_calls_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

/**
 * v1.94: 单条工具调用记录展示。
 */
@Composable
private fun ToolCallRecordItem(
    index: Int,
    record: ToolCallRecord,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.contentGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#$index ${record.toolName}",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(MusePaddings.contentGap))
            Icon(
                imageVector = if (record.isSuccess) Icons.Default.CheckCircle else TablerIcons.AlertCircle,
                contentDescription = null,
                tint = if (record.isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                modifier = Modifier.size(MusePaddings.screen),
            )
        }
        if (record.arguments.isNotBlank()) {
            Text(
                text = stringResource(R.string.chat_tool_call_arguments, record.arguments.take(200)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (record.result.isNotBlank()) {
            Text(
                text = stringResource(R.string.chat_tool_call_result, record.result.take(300)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 通过 ACTION_SEND 分享纯文本(Markdown / JSON / 纯文本导出复用)。
 *
 * @param context Android Context
 * @param mime MIME 类型(如 "text/markdown")
 * @param content 要分享的文本内容
 */
private fun shareText(context: android.content.Context, mime: String, content: String) {
    val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = mime
        putExtra(android.content.Intent.EXTRA_TEXT, content)
        putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.chat_share_subject))
    }
    context.startActivity(
        android.content.Intent.createChooser(shareIntent, context.getString(R.string.chat_share_chooser_title)).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        },
    )
}

/**
 * 把导出内容写入 cacheDir/export/ 下的文件(用于 HTML 导出)。
 *
 * cacheDir 已在 file_paths.xml 中通过 cache-path 暴露给 FileProvider,可直接分享。
 *
 * @param context Android Context
 * @param prefix 文件名前缀(如 "muse-export")
 * @param extension 文件扩展名(如 "html")
 * @param bytes 文件内容字节数组
 * @return 已写入的文件
 */
private fun writeExportFile(
    context: android.content.Context,
    prefix: String,
    extension: String,
    bytes: ByteArray,
): java.io.File {
    val exportDir = java.io.File(context.cacheDir, "export").apply { mkdirs() }
    val timestamp = java.text.SimpleDateFormat(
        io.zer0.muse.ui.theme.MuseDateFormats.FILE_TIMESTAMP,
        java.util.Locale.US,
    ).format(java.util.Date())
    val file = java.io.File(exportDir, "$prefix-$timestamp.$extension")
    file.outputStream().use { it.write(bytes) }
    return file
}

/**
 * 通过 ACTION_SEND + FileProvider 分享文件(用于 HTML / PDF 导出)。
 *
 * 文件须位于 file_paths.xml 已声明的路径下(cacheDir / filesDir / external-files 等)。
 *
 * @param context Android Context
 * @param file 要分享的文件
 * @param mime MIME 类型(如 "application/pdf")
 */
private fun shareFile(context: android.content.Context, file: java.io.File, mime: String) {
    runCatching {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val shareIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = mime
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, context.getString(R.string.chat_share_subject))
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(shareIntent, context.getString(R.string.chat_share_chooser_title)).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }.onFailure {
        MuseToast.show(context.getString(R.string.chat_share_failed_no_uri))
    }
}
