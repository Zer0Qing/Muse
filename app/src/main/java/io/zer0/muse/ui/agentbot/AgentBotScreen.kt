@file:Suppress("LongMethod", "CyclomaticComplexMethod")

package io.zer0.muse.ui.agentbot

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.agentbot.AgentBotError
import io.zer0.muse.data.agentbot.AgentBotScopeState
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.AgentStreamCoordinator
import io.zer0.muse.data.agentbot.BotCardKind
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.ConfirmDeleteDialog
import io.zer0.muse.ui.markdown.MarkdownText
import io.zer0.muse.ui.speech.SpeechInput
import io.zer0.muse.ui.theme.MuseBorderWidth
import io.zer0.muse.ui.theme.MuseButtonSize
import io.zer0.muse.ui.theme.MuseElevation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseSpacing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.util.launchSafely
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * 沉浸式个人智能体主画布（Messaging-First + Generative UI）。
 *
 * 彻底废弃旧版上下分屏工单看板，回归单一全屏对话流：
 * - 顶部轻浮头像与胶囊名牌 + 右侧工作台按钮；
 * - 有后台任务、待拍板审批或活跃虚拟屏时自动滑出单行灵动状态条；
 * - 消息气泡支持底角压边 Emoji 反应贴纸、内联来源链接与长按快捷菜单；
 * - 任务进度卡、拍板审批卡、后续建议卡、主动简报卡与产物卡均作为对话流一等公民自然穿插；
 * - 底部悬浮药丸输入栏永不因后台任务而阻塞。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AgentBotScreen(
    viewModel: AgentBotViewModel,
    modifier: Modifier = Modifier,
    assistantName: String = "",
    avatarUri: String? = null,
    modelName: String = "",
    providers: List<ProviderConfig> = emptyList(),
    onOpenModel: (() -> Unit)? = null,
    onOpenProviderSettings: (() -> Unit)? = null,
    onOpenRuntimeInstall: (() -> Unit)? = null,
    onOpenTask: ((String) -> Unit)? = null,
    onOpenArtifact: ((String) -> Unit)? = null,
    onSwitchAssistant: ((String) -> Unit)? = null,
    onOpenGroupChats: (() -> Unit)? = null,
    topOverlayInset: Dp = MuseSpacing.xxs,
    externalWorkbenchTab: AgentWorkbenchTab? = null,
    onExternalWorkbenchTabConsumed: () -> Unit = {},
    externalBriefingTick: Int = 0,
) {
    val composer by viewModel.composer.collectAsStateWithLifecycle()
    val transcript by viewModel.transcript.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()
    val scopeState by viewModel.scopeState.collectAsStateWithLifecycle()
    val allowedTools by viewModel.allowedTools.collectAsStateWithLifecycle()
    val hasOlderTranscript by viewModel.hasOlderTranscript.collectAsStateWithLifecycle()
    val loadingOlderTranscript by viewModel.loadingOlderTranscript.collectAsStateWithLifecycle()
    val taskDetails by viewModel.taskDetails.collectAsStateWithLifecycle()
    val candidatePreview by viewModel.candidatePreview.collectAsStateWithLifecycle()
    val sandboxLogs by viewModel.sandboxLogs.collectAsStateWithLifecycle()
    val activeVirtualDisplay by viewModel.activeVirtualDisplay.collectAsStateWithLifecycle()
    val modelRoutes by viewModel.modelRoutes.collectAsStateWithLifecycle()
    val routines by viewModel.routinesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val teachState by viewModel.teachState.collectAsStateWithLifecycle()
    val assistants by viewModel.assistantsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val channels by viewModel.channelsFlow.collectAsStateWithLifecycle()
    val squadIdentities by viewModel.squadIdentityBindings.collectAsStateWithLifecycle()
    val artifacts by viewModel.artifactsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val chatPreferences by viewModel.chatPreferencesFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.data.ChatPreferences(),
    )
    val webSearchConfig by viewModel.webSearchConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.web.WebSearchConfig(),
    )
    val knowledgeDocs by viewModel.knowledgeDocsFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val deepThinkingEnabled by viewModel.deepThinkingEnabled.collectAsStateWithLifecycle()
    val deepThinkingLevel by viewModel.deepThinkingLevel.collectAsStateWithLifecycle()
    val asrConfig by viewModel.asrConfig.collectAsStateWithLifecycle()
    val asrState by viewModel.asrState.collectAsStateWithLifecycle()
    val localModelReady by io.zer0.muse.asr.local.LocalModelStoreBridge.readyFlow.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val systemRecognizerAvailable = remember(context) { SpeechInput.isAvailable(context) }
    val showMic = io.zer0.muse.asr.isAsrInputAvailable(
        provider = asrConfig.provider,
        apiKey = asrConfig.apiKey,
        localModelReady = localModelReady,
        systemRecognizerAvailable = systemRecognizerAvailable,
    )
    var systemRecording by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    val voiceUnavailableText = stringResource(R.string.agent_hub_voice_unavailable)
    val voiceRecordingPrompt = stringResource(R.string.agent_hub_voice_recording)

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            MuseToast.show(context.getString(R.string.chat_err_mic_permission))
        }
    }

    val lifecycleOwnerForAsr = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwnerForAsr, viewModel) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                systemRecording = false
                viewModel.disposeAsr()
            }
        }
        lifecycleOwnerForAsr.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwnerForAsr.lifecycle.removeObserver(observer)
            systemRecording = false
            viewModel.disposeAsr()
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { uri ->
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }.onFailure { Logger.w("AgentBotScreen", "图片读取授权无法持久化: ${it.message}", it) }
            viewModel.addPendingImage(uri, context)
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val spoken = SpeechInput.parseResult(result.resultCode, result.data?.extras)
        if (!spoken.isNullOrBlank()) {
            val merged = if (composer.text.isBlank()) spoken else "${composer.text.trimEnd()} $spoken"
            viewModel.onComposerTextChange(merged)
        }
    }

    val projection = when (val state = scopeState) {
        is AgentBotScopeState.Content -> state.projection
        is AgentBotScopeState.Error -> state.lastContent
        AgentBotScopeState.Empty,
        AgentBotScopeState.Loading,
        -> null
    }
    val scopeError = scopeState is AgentBotScopeState.Error
    val isLoading = scopeState is AgentBotScopeState.Loading

    var deleteTarget by remember { mutableStateOf<AgentBotUiState.PublishedMessage?>(null) }
    var workbenchTab by rememberSaveable { mutableStateOf<AgentWorkbenchTab?>(null) }
    var showKnowledgePicker by rememberSaveable { mutableStateOf(false) }
    var viewerMedia by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }

    LaunchedEffect(externalWorkbenchTab) {
        if (externalWorkbenchTab != null) {
            workbenchTab = externalWorkbenchTab
            onExternalWorkbenchTabConsumed()
        }
    }

    val transcriptListState = rememberLazyListState()
    var shouldFollowTranscript by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(transcriptListState) {
        snapshotFlow {
            val layout = transcriptListState.layoutInfo
            layout.totalItemsCount == 0 ||
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 2
        }.collect { shouldFollowTranscript = it }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.tickScheduler()
                viewModel.runTurnOnce()
                delay(3_000)
            }
        }
    }

    LaunchedEffect(transcript.lastOrNull()?.messageId, streaming.active) {
        if (!shouldFollowTranscript || !chatPreferences.autoScrollToBottom) return@LaunchedEffect
        snapshotFlow { transcriptListState.layoutInfo.totalItemsCount }.first { it > 0 }
        val lastIndex = transcriptListState.layoutInfo.totalItemsCount - 1
        if (lastIndex >= 0) {
            transcriptListState.animateScrollToItem(lastIndex)
        }
    }
    LaunchedEffect(streaming.active) {
        if (!streaming.active) return@LaunchedEffect
        var lastScrollUptime = 0L
        snapshotFlow { streaming.text.length }
            .collect { length ->
                if (length <= 0 || !shouldFollowTranscript || !chatPreferences.autoScrollToBottom) return@collect
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastScrollUptime >= 280L) {
                    val lastIndex = transcriptListState.layoutInfo.totalItemsCount - 1
                    if (lastIndex >= 0) {
                        lastScrollUptime = now
                        transcriptListState.animateScrollToItem(lastIndex)
                    }
                }
            }
    }

    val activeAssistant = assistants.firstOrNull { it.id == composer.botId }
    val resolvedName = assistantName.ifBlank {
        activeAssistant?.name.orEmpty().ifBlank { stringResource(R.string.agent_screen_title) }
    }
    val resolvedAvatar = avatarUri?.takeIf { it.isNotBlank() }
        ?: activeAssistant?.avatarImageUrl?.takeIf { it.isNotBlank() }
    val visibleModel = modelName.ifBlank { stringResource(R.string.agent_screen_model_missing) }

    val inProgressTasks = projection?.inProgress.orEmpty()
    val needsActionTasks = projection?.needsAction.orEmpty()
    val recentTasks = projection?.recent.orEmpty()
    val activeStreamTasks = remember(inProgressTasks, needsActionTasks) {
        inProgressTasks + needsActionTasks
    }
    val allTasks = inProgressTasks + needsActionTasks + recentTasks
    val pendingApprovals = projection?.pendingApprovals.orEmpty()
    val candidates = projection?.candidates.orEmpty()
    val annotationsByMsg = projection?.annotationsByMessage.orEmpty()
    val cards = projection?.cards.orEmpty()

    // 仅已完成的历史任务按助手回复消息锚定在历史流中；进行中与待拍板任务统一置于对话流底部（输入栏正上方）。
    val missionAnchorByTaskId = remember(cards) {
        cards.filter { it.kind == BotCardKind.MISSION && it.taskId.isNotBlank() && it.anchorMessageId.isNotBlank() }
            .associate { it.taskId to it.anchorMessageId }
    }
    val tasksByAnchor = remember(recentTasks, transcript, missionAnchorByTaskId) {
        val messageIds = transcript.mapTo(mutableSetOf()) { it.messageId }
        recentTasks
            .mapNotNull { task ->
                val anchor = missionAnchorByTaskId[task.taskId]?.takeIf { it in messageIds }
                if (anchor != null) anchor to task else null
            }
            .groupBy({ it.first }, { it.second })
    }
    val unanchoredRecentTasks = remember(recentTasks, tasksByAnchor) {
        val anchoredIds = tasksByAnchor.values.flatten().mapTo(mutableSetOf()) { it.taskId }
        recentTasks.filterNot { it.taskId in anchoredIds }
    }
    val choiceCardsByAnchor = remember(cards, transcript) {
        val messageIds = transcript.mapTo(mutableSetOf()) { it.messageId }
        cards
            .filter { it.kind == BotCardKind.CHOICE && it.anchorMessageId in messageIds }
            .groupBy { it.anchorMessageId }
    }
    val cueCards = remember(cards) {
        cards.filter { it.kind == BotCardKind.CUE_BRIEFING }
    }
    val unanchoredChoiceCards = remember(cards, choiceCardsByAnchor) {
        val anchoredIds = choiceCardsByAnchor.values.flatten().mapTo(mutableSetOf()) { it.cardId }
        cards.filter { it.kind == BotCardKind.CHOICE && it.cardId !in anchoredIds }
    }

    val activeBadgeCount = inProgressTasks.size + needsActionTasks.size + pendingApprovals.size
    val latestRunningNote = inProgressTasks.firstOrNull()?.note.orEmpty()
        .ifBlank { inProgressTasks.firstOrNull()?.goal.orEmpty() }
    val briefingTitle = stringResource(R.string.agent_hub_cue_briefing_title)
    val briefingEmpty = stringResource(R.string.agent_hub_cue_briefing_empty)
    val triggerBriefing: () -> Unit = {
        val summary = buildString {
            if (inProgressTasks.isNotEmpty()) append("进行中任务 ${inProgressTasks.size} 项；")
            if (pendingApprovals.isNotEmpty()) append("待拍板审批 ${pendingApprovals.size} 项；")
            if (routines.isNotEmpty()) append("已配置自动化 ${routines.size} 条。")
        }.ifBlank { briefingEmpty }
        viewModel.publishCueBriefing(
            title = briefingTitle,
            summary = summary,
            actions = listOf(
                AgentStreamCoordinator.ChoiceOption(
                    id = "brief_review",
                    label = "帮我梳理今天的优先级",
                    prompt = "结合我目前的任务和自动化安排，帮我梳理一下今天最值得先做的三件事。",
                ),
                AgentStreamCoordinator.ChoiceOption(
                    id = "brief_check_env",
                    label = "自检电脑运行环境",
                    prompt = "检查一下你当前这台电脑的运行环境和工具状态，简短告诉我结果。",
                ),
            ),
        )
    }
    LaunchedEffect(externalBriefingTick) {
        if (externalBriefingTick > 0) {
            triggerBriefing()
        }
    }

    val isWelcomeEmpty = transcript.isEmpty() && allTasks.isEmpty() && cueCards.isEmpty() && !streaming.active
    val density = LocalDensity.current
    var topOverlayHeightPx by remember { mutableIntStateOf(0) }
    var bottomOverlayHeightPx by remember { mutableIntStateOf(0) }
    val topOverlayPadding = with(density) { topOverlayHeightPx.toDp() }
    val bottomOverlayPadding = with(density) { bottomOverlayHeightPx.toDp() }
    val copiedToastText = stringResource(R.string.agent_screen_copy)
    val savedRoutineToastText = stringResource(R.string.agent_hub_mission_save_routine)

    // 接入外观设置中的三层聊天背景与液态玻璃效果（背景层与内容层作为兄弟节点，防止采样递归）
    val settingsRepo = remember {
        runCatching {
            org.koin.mp.KoinPlatformTools.defaultContext().getOrNull()
                ?.getOrNull<io.zer0.muse.data.SettingsRepository>()
        }.getOrNull()
    }
    val globalChatBackground by (settingsRepo?.chatBackgroundFlow ?: flowOf(null))
        .collectAsStateWithLifecycle(initialValue = null)
    val darkChatBackground by (settingsRepo?.chatBackgroundDarkFlow ?: flowOf(null))
        .collectAsStateWithLifecycle(initialValue = null)
    val assistantBackgrounds by (settingsRepo?.assistantChatBackgroundFlow ?: flowOf(emptyMap()))
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val sessionBackgrounds by (settingsRepo?.sessionChatBackgroundFlow ?: flowOf(emptyMap()))
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    val chatGradient by (settingsRepo?.chatGradientFlow ?: flowOf(null))
        .collectAsStateWithLifecycle(initialValue = null)
    val boundSessionId by viewModel.boundSessionId.collectAsStateWithLifecycle()
    val isDarkTheme = io.zer0.muse.ui.theme.LocalIsDarkTheme.current
    val effectiveGlobalBackground = if (isDarkTheme && !darkChatBackground.isNullOrBlank()) {
        darkChatBackground
    } else {
        globalChatBackground
    }
    val chatBackground: String? = io.zer0.muse.data.resolveChatBackground(
        sessionId = boundSessionId.takeIf { it.isNotBlank() },
        assistantId = composer.botId.ifBlank { "default" },
        sessionBackgrounds = sessionBackgrounds,
        assistantBackgrounds = assistantBackgrounds,
        globalBackground = effectiveGlobalBackground,
    )
    val glassConfig = io.zer0.muse.ui.theme.LocalLiquidGlass.current
    val glassActive = glassConfig.enabled &&
        io.zer0.muse.ui.theme.isRealGlassSupported() &&
        !io.zer0.muse.ui.theme.LocalInsideBackdropRecorder.current
    val pageLayerBackdrop = if (glassActive && glassConfig.style == io.zer0.muse.ui.theme.GlassStyle.FROST) {
        rememberLayerBackdrop()
    } else {
        null
    }
    val pageWaterState = if (glassActive && glassConfig.style == io.zer0.muse.ui.theme.GlassStyle.WATER) {
        rememberLiquidState()
    } else {
        null
    }
    val backgroundIsDark = when {
        !chatBackground.isNullOrBlank() -> true
        else -> chatGradient?.let { g ->
            val start = Color(g.startColorArgb.toInt())
            val end = Color(g.endColorArgb.toInt())
            (start.luminance() + end.luminance()) / 2f < 0.5f
        } ?: (MaterialTheme.colorScheme.background.luminance() < 0.5f)
    }

    // 全屏叠层画布：背景与对话流共同进入 backdrop 采样层，顶栏与底部输入岛作为兄弟层悬浮其上。
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (pageLayerBackdrop != null) Modifier.layerBackdrop(pageLayerBackdrop) else Modifier)
                .then(if (pageWaterState != null) Modifier.liquefiable(pageWaterState) else Modifier),
        ) {
            // 内容层内部隔离 backdrop 采样源，避免滚动流内的流内玻璃胶囊（如 AgentInlineIdentityBar）
            // 反向采样自身所属的 backdrop 导致 HWUI RenderThread 递归崩溃。
            androidx.compose.runtime.CompositionLocalProvider(
                io.zer0.muse.ui.theme.LocalLayerBackdrop provides null,
                io.zer0.muse.ui.theme.LocalWaterGlassState provides null,
                io.zer0.muse.ui.theme.LocalInsideBackdropRecorder provides
                    (pageLayerBackdrop != null || pageWaterState != null ||
                        io.zer0.muse.ui.theme.LocalInsideBackdropRecorder.current),
            ) {
            if (!chatBackground.isNullOrBlank()) {
                AsyncImage(
                    model = chatBackground,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                chatGradient?.let { gradient ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    colors = listOf(
                                        Color(gradient.startColorArgb.toInt()),
                                        Color(gradient.endColorArgb.toInt()),
                                    ),
                                ),
                            ),
                    )
                }
            }

            if (isWelcomeEmpty) {
                AgentWelcomeCanvas(
                    assistantName = resolvedName,
                    assistant = activeAssistant,
                    avatarUri = resolvedAvatar,
                    modelLabel = visibleModel,
                    hasConfiguredModel = modelName.isNotBlank() && providers.any { it.enabled },
                    onOpenIdentitySwitch = { workbenchTab = AgentWorkbenchTab.SQUAD },
                    onOpenModel = onOpenModel,
                    onTriggerBriefing = triggerBriefing,
                    onOpenTeach = {
                        viewModel.startTeachRecording()
                        workbenchTab = AgentWorkbenchTab.ROUTINES
                    },
                    onOpenWorkbench = { workbenchTab = AgentWorkbenchTab.LIVE },
                    onOpenProviderSettings = onOpenProviderSettings,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(
                            top = topOverlayInset + topOverlayPadding,
                            bottom = bottomOverlayPadding,
                        ),
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = transcriptListState,
                    contentPadding = PaddingValues(
                        start = if (chatPreferences.bubbleFullWidth) MuseSpacing.sm else MusePaddings.screen,
                        end = if (chatPreferences.bubbleFullWidth) MuseSpacing.sm else MusePaddings.screen,
                        top = topOverlayInset + topOverlayPadding + MusePaddings.contentGap,
                        bottom = bottomOverlayPadding + MusePaddings.contentGap,
                    ),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.auxGap),
                ) {
                    if (topOverlayInset > 0.dp) {
                        item(key = "agent_inline_identity") {
                            AgentInlineIdentityBar(
                                assistantName = resolvedName,
                                assistant = activeAssistant,
                                avatarUri = resolvedAvatar,
                                modelLabel = visibleModel,
                                onOpenIdentitySwitch = { workbenchTab = AgentWorkbenchTab.SQUAD },
                                onOpenModel = onOpenModel,
                            )
                        }
                    }
                    if (hasOlderTranscript) {
                        item(key = "load-older") {
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                MuseCapsuleButton(
                                    text = stringResource(R.string.agent_screen_load_older),
                                    onClick = viewModel::loadOlderTranscript,
                                    loading = loadingOlderTranscript,
                                    variant = IosCapsuleButtonVariant.Text,
                                    fillWidth = false,
                                    size = MuseButtonSize.Compact,
                                )
                            }
                        }
                    }

                    // 主动简报 / 建议行动卡
                    items(cueCards, key = { "cue:${it.cardId}" }) { cue ->
                        AgentCueBriefingCard(
                            card = cue,
                            onSelectAction = viewModel::selectChoiceOption,
                            onDismiss = viewModel::dismissCard,
                        )
                    }

                    // 对话气泡 + 锚定动态任务卡 + 锚定后续建议卡
                    items(transcript, key = { it.messageId }) { message ->
                        Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap)) {
                            AgentMessageBubble(
                                message = message,
                                reactions = annotationsByMsg[message.messageId].orEmpty(),
                                chatPreferences = chatPreferences,
                                modelLabel = visibleModel,
                                onCopy = {
                                    val cleanCopy = io.zer0.muse.transformer.InternalMarkupSanitizer.displayContent(message.text)
                                    clipboard?.setPrimaryClip(ClipData.newPlainText(null, cleanCopy))
                                    MuseToast.show(copiedToastText)
                                },
                                onRetry = { viewModel.retryMessage(message.messageId) },
                                onDelete = { deleteTarget = message },
                                onReact = { emoji -> viewModel.reactToMessage(message.messageId, emoji) },
                                onRemoveReaction = viewModel::removeAnnotation,
                                onQuote = {
                                    viewModel.setQuotedMessage(message)
                                },
                                onEdit = {
                                    viewModel.startEditingMessage(message)
                                },
                                onSaveAsRoutine = {
                                    val title = message.text.lineSequence().firstOrNull().orEmpty().take(24)
                                        .ifBlank { resolvedName }
                                    viewModel.createRoutine(title = title, goalPrompt = message.text)
                                    MuseToast.show(savedRoutineToastText)
                                },
                                onOpenMediaViewer = { images, index ->
                                    viewerMedia = images to index
                                },
                            )

                                tasksByAnchor[message.messageId].orEmpty().forEach { taskRow ->
                                    AgentLiveMissionCard(
                                        row = taskRow,
                                        onOpenDetails = onOpenTask ?: viewModel::openTaskDetails,
                                        onOpenComputer = { workbenchTab = AgentWorkbenchTab.LIVE },
                                        onSteer = viewModel::steerTask,
                                        onSaveAsRoutine = { taskId -> viewModel.saveTaskAsRoutine(taskId) },
                                        onContinue = viewModel::continueTask,
                                        onRetry = viewModel::retryTask,
                                        onCancel = viewModel::cancelTask,
                                    )
                                }

                                choiceCardsByAnchor[message.messageId].orEmpty().forEach { choice ->
                                    AgentChoiceCard(
                                        card = choice,
                                        onSelectOption = viewModel::selectChoiceOption,
                                    )
                                }
                            }
                        }

                        // 未绑定具体回复消息的近期已完成任务
                        items(unanchoredRecentTasks, key = { "recent-task:${it.taskId}" }) { taskRow ->
                            AgentLiveMissionCard(
                                row = taskRow,
                                onOpenDetails = onOpenTask ?: viewModel::openTaskDetails,
                                onOpenComputer = { workbenchTab = AgentWorkbenchTab.LIVE },
                                onSteer = viewModel::steerTask,
                                onSaveAsRoutine = { taskId -> viewModel.saveTaskAsRoutine(taskId) },
                                onContinue = viewModel::continueTask,
                                onRetry = viewModel::retryTask,
                                onCancel = viewModel::cancelTask,
                            )
                        }

                        // 进行中与待处理任务：统一置于对话流底部（输入栏上方），不再挤在顶部用户消息与助手回复之间
                        items(activeStreamTasks, key = { "active-task:${it.taskId}" }) { taskRow ->
                            AgentLiveMissionCard(
                                row = taskRow,
                                onOpenDetails = onOpenTask ?: viewModel::openTaskDetails,
                                onOpenComputer = { workbenchTab = AgentWorkbenchTab.LIVE },
                                onSteer = viewModel::steerTask,
                                onSaveAsRoutine = { taskId -> viewModel.saveTaskAsRoutine(taskId) },
                                onContinue = viewModel::continueTask,
                                onRetry = viewModel::retryTask,
                                onCancel = viewModel::cancelTask,
                            )
                        }

                        // 流内原生审批拍板卡
                        items(pendingApprovals, key = { "approval:${it.approvalId}" }) { approval ->
                            AgentInlineApprovalCard(
                                row = approval,
                                onApprove = { row ->
                                    viewModel.approve(row.approvalId, row.argumentsHash, row.resourceVersion)
                                },
                                onDeny = { row -> viewModel.deny(row.approvalId) },
                                onOpenTask = onOpenTask ?: viewModel::openTaskDetails,
                            )
                        }

                        // 流内待审阅工作区变更交付卡
                        items(candidates, key = { "candidate:${it.stagedId}" }) { candidate ->
                            AgentArtifactDeliveryCard(
                                candidate = candidate,
                                onOpen = onOpenArtifact ?: viewModel::openCandidatePreview,
                                onDiscard = viewModel::discardCandidate,
                            )
                        }

                        // 未锚定的后续建议卡
                        items(unanchoredChoiceCards, key = { "choice:${it.cardId}" }) { choice ->
                            AgentChoiceCard(
                                card = choice,
                                onSelectOption = viewModel::selectChoiceOption,
                            )
                        }

                        // 实时原生流式回复气泡
                        if (streaming.active) {
                            item(key = "streaming-bubble") {
                                AgentStreamingBubble(
                                    streaming = streaming,
                                    chatPreferences = chatPreferences,
                                )
                            }
                        }
                    }
            }
            }
        }

        androidx.compose.runtime.CompositionLocalProvider(
            io.zer0.muse.ui.theme.LocalLayerBackdrop provides
                (pageLayerBackdrop ?: io.zer0.muse.ui.theme.LocalLayerBackdrop.current),
            io.zer0.muse.ui.theme.LocalWaterGlassState provides
                (pageWaterState ?: io.zer0.muse.ui.theme.LocalWaterGlassState.current),
            io.zer0.muse.ui.theme.LocalGlassIsBackgroundDark provides backgroundIsDark,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // 上滑历史时浮现的回到底部悬浮圆按钮（与普通对话一致居中对齐，避让底部悬浮输入岛）
                androidx.compose.animation.AnimatedVisibility(
                    visible = !shouldFollowTranscript && transcript.isNotEmpty(),
                    enter = fadeIn() + scaleIn(),
                    exit = fadeOut() + scaleOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(
                            bottom = bottomOverlayPadding + MusePaddings.contentGap,
                        ),
                ) {
                    Surface(
                        onClick = {
                            shouldFollowTranscript = true
                            coroutineScope.launch {
                                val last = transcriptListState.layoutInfo.totalItemsCount - 1
                                if (last >= 0) transcriptListState.animateScrollToItem(last)
                            }
                        },
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = MuseElevation.high,
                        border = BorderStroke(
                            width = MuseBorderWidth.hairline,
                            color = MaterialTheme.colorScheme.outlineVariant,
                        ),
                        modifier = Modifier.size(MuseIconSizes.topBarSolid),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = MuseIcons.arrowDown,
                                contentDescription = stringResource(R.string.agent_hub_scroll_bottom_cd),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(MuseIconSizes.iconSmall),
                            )
                        }
                    }
                }

        // 顶部透明悬浮层：置于首页 Tab 下时已由首页统一接管顶栏，此处仅保留无遮挡加载条/错误横幅
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .onSizeChanged { topOverlayHeightPx = it.height }
                .padding(top = topOverlayInset),
        ) {
            if (topOverlayInset == 0.dp) {
                AgentFloatingHeader(
                    assistantName = resolvedName,
                    avatarUri = resolvedAvatar,
                    modelLabel = visibleModel,
                    activeBadgeCount = activeBadgeCount,
                    hasPendingApproval = pendingApprovals.isNotEmpty(),
                    onOpenIdentitySwitch = { workbenchTab = AgentWorkbenchTab.SQUAD },
                    onOpenWorkbench = { workbenchTab = AgentWorkbenchTab.LIVE },
                )
            }

            if (isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (scopeError) {
                ScopeErrorRow(onRetry = viewModel::retryScope)
            }
        }

        // 底部透明悬浮层：待拍板/进行中状态胶囊置顶于输入栏正上方 + 药丸输入岛
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .onSizeChanged { bottomOverlayHeightPx = it.height },
        ) {
            AgentLiveActivityStrip(
                runningCount = inProgressTasks.size,
                approvalCount = pendingApprovals.size,
                latestRunningNote = latestRunningNote,
                activeVirtualDisplay = activeVirtualDisplay,
                onClick = { workbenchTab = AgentWorkbenchTab.LIVE },
            )

            AgentFloatingComposer(
                composer = composer,
                busy = busy,
                isStreaming = streaming.active,
                errorText = busy.error?.let { agentErrorLabel(it) },
                enterToSend = chatPreferences.enterToSend,
                showExpandButton = chatPreferences.showExpandButton,
                isWebSearchEnabled = webSearchConfig.enabled,
                isDeepThinkingEnabled = deepThinkingEnabled,
                deepThinkingLevel = deepThinkingLevel,
                isRecording = asrState.isRecording || systemRecording,
                asrStatus = asrState.status,
                asrErrorMessage = asrState.errorMessage,
                recordingAmplitudes = asrState.amplitudes,
                showMic = showMic,
                onTextChange = viewModel::onComposerTextChange,
                onClearQuote = { viewModel.setQuotedMessage(null) },
                onCancelEdit = viewModel::cancelEditingMessage,
                onToggleWebSearch = viewModel::toggleWebSearch,
                onToggleDeepThinking = viewModel::toggleDeepThinking,
                onCycleDeepThinkingLevel = viewModel::cycleDeepThinkingLevel,
                onOpenKnowledgePicker = { showKnowledgePicker = true },
                onPreviewPendingImage = { index ->
                    val images = composer.pendingImages.map { "data:image/jpeg;base64,$it" }
                    if (images.isNotEmpty()) viewerMedia = images to index
                },
                onSend = viewModel::send,
                onStopReply = viewModel::stopReply,
                onPickImages = {
                    imagePicker.launchSafely(arrayOf("image/*"), context)
                },
                onPickGalleryImage = { uri -> viewModel.addPendingImage(uri, context) },
                onRemoveImage = viewModel::removePendingImage,
                onStartRecording = {
                    if (viewModel.shouldUseApiRecording()) {
                        val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                            context,
                            android.Manifest.permission.RECORD_AUDIO,
                        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            val ok = viewModel.startStreamingAsr()
                            if (!ok) {
                                val err = viewModel.asrState.value.errorMessage
                                    ?.takeIf { it.isNotBlank() }
                                    ?: context.getString(R.string.asr_missing_api_key_error)
                                MuseToast.show(err)
                            }
                            ok
                        } else {
                            micPermissionLauncher.launchSafely(
                                android.Manifest.permission.RECORD_AUDIO,
                                context,
                            )
                            MuseToast.show(context.getString(R.string.chat_err_mic_permission))
                            false
                        }
                    } else if (SpeechInput.isAvailable(context)) {
                        systemRecording = true
                        true
                    } else {
                        MuseToast.show(voiceUnavailableText)
                        false
                    }
                },
                onStopRecording = {
                    if (viewModel.shouldUseApiRecording()) {
                        viewModel.stopStreamingAsrAndSend()
                    } else {
                        systemRecording = false
                        runCatching {
                            voiceLauncher.launch(SpeechInput.createIntent(voiceRecordingPrompt))
                        }.onFailure {
                            Logger.w("AgentBotScreen", "拉起语音输入失败: ${it.message}")
                            MuseToast.show(voiceUnavailableText)
                        }
                    }
                },
                onCancelRecording = {
                    if (viewModel.shouldUseApiRecording()) {
                        viewModel.cancelStreamingAsr()
                    } else {
                        systemRecording = false
                    }
                },
                onOpenWorkbench = { workbenchTab = AgentWorkbenchTab.LIVE },
                onTriggerBriefing = triggerBriefing,
                onStartTeach = {
                    viewModel.startTeachRecording()
                    workbenchTab = AgentWorkbenchTab.ROUTINES
                },
                onOpenModel = onOpenModel,
                onOpenProviderSettings = onOpenProviderSettings,
                onOpenRuntimeInstall = onOpenRuntimeInstall,
            )
        }
            }
        }
    }

    viewerMedia?.let { (images, initialIndex) ->
        io.zer0.muse.ui.common.media.FullScreenMediaViewer(
            images = images,
            initialIndex = initialIndex,
            onDismiss = { viewerMedia = null },
        )
    }

    if (showKnowledgePicker) {
        val visibleDocs = remember(knowledgeDocs) { knowledgeDocs.filterNot { it.isInternal } }
        MuseDialog(
            onDismissRequest = { showKnowledgePicker = false },
            title = stringResource(R.string.chat_knowledge_dialog_title),
            content = {
                Column(modifier = Modifier.fillMaxWidth()) {
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
                                onClick = {
                                    val mention = "@${doc.title} "
                                    val current = composer.text
                                    viewModel.onComposerTextChange(if (current.isBlank()) mention else "$current\n$mention")
                                    showKnowledgePicker = false
                                },
                            )
                        }
                    }
                }
            },
            onConfirm = null,
            dismissText = stringResource(R.string.common_close),
            onDismiss = { showKnowledgePicker = false },
        )
    }

    // 四域智能体工作台 BottomSheet
    workbenchTab?.let { initialTab ->
        AgentWorkbenchSheet(
            initialTab = initialTab,
            currentBotId = composer.botId.ifBlank { AgentBotViewModel.DEFAULT_BOT_ID },
            projection = projection,
            allowedTools = allowedTools.toList(),
            sandboxLogs = sandboxLogs,
            activeVirtualDisplay = activeVirtualDisplay,
            modelRoutes = modelRoutes,
            providers = providers,
            routines = routines,
            teachState = teachState,
            assistants = assistants,
            channels = channels,
            squadIdentities = squadIdentities,
            artifacts = artifacts,
            onDismiss = { workbenchTab = null },
            onOpenTask = onOpenTask ?: viewModel::openTaskDetails,
            onSteerTask = viewModel::steerTask,
            onSaveTaskAsRoutine = { taskId -> viewModel.saveTaskAsRoutine(taskId) },
            onContinueTask = viewModel::continueTask,
            onRetryTask = viewModel::retryTask,
            onCancelTask = viewModel::cancelTask,
            onApprove = { row ->
                viewModel.approve(row.approvalId, row.argumentsHash, row.resourceVersion)
            },
            onDeny = { row -> viewModel.deny(row.approvalId) },
            onClearSandboxLogs = viewModel::clearSandboxLogs,
            onSetModelRoute = viewModel::setModelRoute,
            onClearModelRoute = viewModel::clearModelRoute,
            onCreateRoutine = viewModel::createRoutine,
            onRunRoutine = viewModel::runRoutineNow,
            onToggleRoutine = viewModel::toggleRoutine,
            onDeleteRoutine = viewModel::deleteRoutine,
            onStartTeach = viewModel::startTeachRecording,
            onUpdateTeachTitle = viewModel::updateTeachTitle,
            onAddTeachStep = { action -> viewModel.recordTeachStep(action) },
            onFinishTeach = viewModel::finishTeachRecording,
            onCancelTeach = viewModel::cancelTeachRecording,
            onSwitchAssistant = { assistantId ->
                onSwitchAssistant?.invoke(assistantId) ?: viewModel.bind(assistantId, "")
            },
            onToggleChannelForBot = viewModel::toggleChannelForBot,
            onRefreshChannels = viewModel::refreshChannels,
            onOpenGroupChats = onOpenGroupChats,
            onOpenCandidate = onOpenArtifact ?: viewModel::openCandidatePreview,
            onDiscardCandidate = viewModel::discardCandidate,
            onRevokeAllowance = viewModel::revokeAllowance,
        )
    }

    deleteTarget?.let { message ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.chat_delete_message),
            itemName = stringResource(R.string.agent_screen_transcript),
            onConfirm = {
                deleteTarget = null
                viewModel.deleteMessage(message.messageId)
            },
            onDismiss = { deleteTarget = null },
        )
    }

    taskDetails?.let { details ->
        MuseDialog(
            onDismissRequest = viewModel::closeTaskDetails,
            title = stringResource(R.string.agent_screen_task_details),
            content = {
                // MuseDialog 外层已提供 verticalScroll，此处不重复嵌套。
                Column(
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Text(details.goal, style = MaterialTheme.typography.bodyMedium)
                    Text(agentStatusLabel(details.status), style = MaterialTheme.typography.labelMedium)
                    if (details.acceptanceCriteria.isNotBlank()) {
                        Text(stringResource(R.string.agent_screen_acceptance), style = MaterialTheme.typography.titleSmall)
                        Text(details.acceptanceCriteria, style = MaterialTheme.typography.bodySmall)
                    }
                    if (details.summary.isNotBlank()) {
                        Text(stringResource(R.string.agent_screen_review_summary), style = MaterialTheme.typography.titleSmall)
                        MarkdownText(text = details.summary, style = MaterialTheme.typography.bodySmall)
                    }
                    details.findings.forEach { finding ->
                        Text(
                            text = "${stringResource(R.string.agent_screen_unverified)}: $finding",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    details.sideEffects.forEach { effect ->
                        Text(
                            text = "${stringResource(R.string.agent_screen_side_effects)}: $effect",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    details.candidateRefs.forEach { candidate ->
                        Text(candidate, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = viewModel::closeTaskDetails,
        )
    }

    candidatePreview?.let { preview ->
        MuseDialog(
            onDismissRequest = viewModel::closeCandidatePreview,
            title = stringResource(R.string.agent_screen_artifact_preview),
            content = {
                // MuseDialog 外层已提供 verticalScroll，此处不重复嵌套。
                Column(
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    Text(preview.targetRef, style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(
                            R.string.agent_screen_candidate_review_state,
                            preview.taskRevision,
                            candidateStateLabel(preview.verificationState),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (preview.truncated) {
                        Text(
                            stringResource(R.string.agent_screen_candidate_preview_truncated),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MarkdownText(text = preview.content, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmText = stringResource(R.string.common_close),
            onConfirm = viewModel::closeCandidatePreview,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AgentWelcomeCanvas(
    assistantName: String,
    assistant: io.zer0.muse.data.assistant.AssistantEntity?,
    avatarUri: String?,
    modelLabel: String,
    hasConfiguredModel: Boolean,
    onOpenIdentitySwitch: () -> Unit,
    onOpenModel: (() -> Unit)?,
    onTriggerBriefing: () -> Unit,
    onOpenTeach: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onOpenProviderSettings: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.largeGap),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
    ) {
        AgentInlineIdentityBar(
            assistantName = assistantName,
            assistant = assistant,
            avatarUri = avatarUri,
            modelLabel = modelLabel,
            onOpenIdentitySwitch = onOpenIdentitySwitch,
            onOpenModel = onOpenModel,
        )
        if (!hasConfiguredModel && onOpenProviderSettings != null) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_screen_provider_settings),
                onClick = onOpenProviderSettings,
                leadingIcon = MuseIcons.sliders,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(
                MusePaddings.contentGap,
                Alignment.CenterHorizontally,
            ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_quick_briefing),
                onClick = onTriggerBriefing,
                leadingIcon = MuseIcons.sun,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_quick_teach),
                onClick = onOpenTeach,
                leadingIcon = MuseIcons.handFinger,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.agent_hub_quick_workbench),
                onClick = onOpenWorkbench,
                leadingIcon = MuseIcons.computer,
                variant = IosCapsuleButtonVariant.Secondary,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
    }
}

@Composable
private fun ScopeErrorRow(onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.padding(horizontal = MusePaddings.screen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            Text(
                text = stringResource(R.string.agent_screen_load_failed),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            MuseCapsuleButton(
                text = stringResource(R.string.agent_screen_retry_load),
                onClick = onRetry,
                variant = IosCapsuleButtonVariant.Text,
                fillWidth = false,
                size = MuseButtonSize.Compact,
            )
        }
    }
}

@Composable
private fun agentErrorLabel(error: AgentBotError): String = when (error) {
    AgentBotError.NO_SESSION -> stringResource(R.string.agent_bot_error_no_session)
    AgentBotError.BINDING_UNREADABLE -> stringResource(R.string.agent_bot_error_binding_unreadable)
    AgentBotError.SAVE_FAILED -> stringResource(R.string.agent_bot_error_save_failed)
    AgentBotError.ALREADY_DECIDED -> stringResource(R.string.agent_bot_error_already_decided)
    AgentBotError.APPROVAL_EXPIRED -> stringResource(R.string.agent_bot_error_approval_expired)
    AgentBotError.APPROVAL_STALE -> stringResource(R.string.agent_bot_error_approval_stale)
    AgentBotError.MISSING_BOT_SAY -> stringResource(R.string.agent_bot_error_missing_say)
    AgentBotError.PROVIDER_TOOL_BLIND -> stringResource(R.string.agent_bot_error_tool_blind)
    AgentBotError.ATTACHMENT_FAILED -> stringResource(R.string.agent_screen_attachment_failed)
    AgentBotError.ATTACHMENT_LIMIT ->
        stringResource(R.string.agent_screen_attachment_limit, AgentBotUiState.MAX_PENDING_IMAGES)
    AgentBotError.HISTORY_LOAD_FAILED -> stringResource(R.string.agent_screen_history_load_failed)
}

@Composable
private fun candidateStateLabel(state: String): String = when (state) {
    io.zer0.muse.data.agentbot.VerificationState.PROPOSED -> stringResource(R.string.agent_screen_candidate_proposed)
    io.zer0.muse.data.agentbot.VerificationState.VERIFIED -> stringResource(R.string.agent_screen_candidate_verified)
    io.zer0.muse.data.agentbot.VerificationState.CONFLICTED -> stringResource(R.string.agent_screen_candidate_conflicted)
    io.zer0.muse.data.agentbot.VerificationState.APPLIED_PENDING_COMMIT ->
        stringResource(R.string.agent_screen_candidate_applied_pending)
    else -> stringResource(R.string.agent_screen_candidate_verified)
}
