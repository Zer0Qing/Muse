package io.zer0.muse.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import io.github.fletchmckee.liquid.rememberLiquidState
import io.zer0.common.Logger
import io.zer0.memory.fact.FactDao
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.agentbot.AgentBotUiState
import io.zer0.muse.data.agentbot.AgentModelRouter
import io.zer0.muse.data.agentbot.BotTaskStatus
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.session.FolderEntity
import io.zer0.muse.data.session.SessionEntity
import io.zer0.muse.data.session.displaySessionTitle
import io.zer0.muse.feature.FeatureFlags
import io.zer0.muse.ui.agentbot.AgentBotScreen
import io.zer0.muse.ui.agentbot.AgentBotViewModel
import io.zer0.muse.ui.agentbot.AgentWorkbenchTab
import io.zer0.muse.ui.assistant.ModelSwitchSheet
import io.zer0.muse.ui.chat.ChatScreen
import io.zer0.muse.ui.chat.ChatSurface
import io.zer0.muse.ui.chat.ChatViewModel
import io.zer0.muse.ui.chat.CommandPalette
import io.zer0.muse.ui.chat.WorkspaceQuickPickDialog
import io.zer0.muse.ui.common.GreetingHelper
import io.zer0.muse.ui.common.MuseFloatingActionItem
import io.zer0.muse.ui.common.form.MuseSearchField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.AssistantAvatar
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.common.navigation.MuseTopBarIconButton
import io.zer0.muse.ui.common.navigation.MuseTopBarMenu
import io.zer0.muse.ui.common.surface.MusePageScaffold
import io.zer0.muse.ui.groupchat.GroupChatListScreen
import io.zer0.muse.ui.theme.LocalGlassIsBackgroundDark
import io.zer0.muse.ui.theme.LocalInsideBackdropRecorder
import io.zer0.muse.ui.theme.LocalLayerBackdrop
import io.zer0.muse.ui.theme.LocalLiquidGlass
import io.zer0.muse.ui.theme.LocalWaterGlassState
import io.zer0.muse.ui.theme.MuseAlpha
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.MuseSpacing
import io.zer0.muse.ui.theme.bodyMediumStrong
import io.zer0.muse.ui.theme.isRealGlassSupported
import io.zer0.muse.ui.theme.statusColors
import io.zer0.muse.ui.theme.titleSmallStrong
import io.zer0.muse.update.UpdateChecker
import io.zer0.muse.update.UpdateNotifier
import io.zer0.muse.util.ShareIntentHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.time.LocalDateTime

private enum class DrawerHomeCanvas {
    AGENT,
    TASK,
    GROUP_CHATS,
}

@Composable
private fun DrawerHomeUpdateBanner(
    release: UpdateChecker.ReleaseInfo,
    context: Context,
    onClose: () -> Unit,
) {
    val openDownloadPage: () -> Unit = {
        val intent = UpdateNotifier.buildOfficialDownloadIntent()
        runCatching {
            ShareIntentHelper.startChooserSafely(context, intent)
        }.onFailure { Logger.w("DrawerHomeScreen", "startActivity failed: ${it.message}") }
    }
    UpdateAvailableBanner(
        release = release,
        onClose = onClose,
        onViewRelease = openDownloadPage,
        onDownloadApk = openDownloadPage,
    )
}

private data class DrawerHomeCanvasState(
    val activeCanvas: DrawerHomeCanvas,
    val botViewModel: AgentBotViewModel?,
    val assistantName: String,
    val avatarUri: String?,
    val modelName: String,
    val providers: List<io.zer0.ai.core.ProviderConfig>,
    val externalWorkbenchTab: AgentWorkbenchTab?,
    val chatViewModel: ChatViewModel,
)

private data class DrawerHomeAgentActions(
    val onOpenModel: () -> Unit,
    val onOpenProviderSettings: () -> Unit,
    val onOpenRuntimeInstall: () -> Unit,
    val onOpenMcpConnections: (() -> Unit)?,
    val onOpenArtifactCenter: (() -> Unit)?,
    val onSwitchAssistant: (String) -> Unit,
    val onOpenGroupChats: () -> Unit,
    val onOpenGroupChat: (String) -> Unit,
    val onExternalWorkbenchTabConsumed: () -> Unit,
)

private data class DrawerHomeChatActions(
    val onOpenAssistants: () -> Unit,
    val onOpenProviderSettings: () -> Unit,
    val onOpenMcpSettings: ((String?) -> Unit)?,
    val onHtmlPreview: (String) -> Unit,
    val onOpenSkills: () -> Unit,
    val onOpenWorkspace: (String?) -> Unit,
    val onOpenPromptTemplateManager: () -> Unit,
)

private data class DrawerModelSheetState(
    val activeCanvas: DrawerHomeCanvas,
    val botViewModel: AgentBotViewModel?,
    val providers: List<io.zer0.ai.core.ProviderConfig>,
    val activeAgentProviderId: String?,
    val activeAgentModelId: String?,
    val activeProviderId: String?,
    val selectedModelId: String?,
    val isFetchingModels: Boolean,
    val fetchModelsError: String?,
)

@Composable
private fun DrawerHomeCanvas(
    state: DrawerHomeCanvasState,
    agentActions: DrawerHomeAgentActions,
    chatActions: DrawerHomeChatActions,
) {
    when (state.activeCanvas) {
        DrawerHomeCanvas.AGENT -> {
            if (state.botViewModel != null) {
                AgentBotScreen(
                    viewModel = state.botViewModel,
                    assistantName = state.assistantName,
                    avatarUri = state.avatarUri,
                    modelName = state.modelName,
                    providers = state.providers,
                    onOpenModel = agentActions.onOpenModel,
                    onOpenProviderSettings = agentActions.onOpenProviderSettings,
                    onOpenRuntimeInstall = agentActions.onOpenRuntimeInstall,
                    onOpenMcpConnections = agentActions.onOpenMcpConnections,
                    onOpenArtifactCenter = agentActions.onOpenArtifactCenter,
                    onSwitchAssistant = agentActions.onSwitchAssistant,
                    onOpenGroupChats = agentActions.onOpenGroupChats,
                    topOverlayInset = 0.dp,
                    showFloatingHeader = false,
                    externalWorkbenchTab = state.externalWorkbenchTab,
                    onExternalWorkbenchTabConsumed = agentActions.onExternalWorkbenchTabConsumed,
                )
            } else {
                DrawerHomeChatCanvas(
                    surface = ChatSurface.AGENT,
                    state = state,
                    actions = chatActions,
                )
            }
        }
        DrawerHomeCanvas.TASK -> DrawerHomeChatCanvas(
            surface = ChatSurface.HOME_DRAWER_TASK,
            state = state,
            actions = chatActions,
        )
        DrawerHomeCanvas.GROUP_CHATS -> GroupChatListScreen(onOpenChat = agentActions.onOpenGroupChat)
    }
}

@Composable
private fun DrawerHomeChatCanvas(
    surface: ChatSurface,
    state: DrawerHomeCanvasState,
    actions: DrawerHomeChatActions,
) {
    ChatScreen(
        onOpenAssistants = actions.onOpenAssistants,
        onOpenProviderSettings = actions.onOpenProviderSettings,
        onOpenMcpSettings = actions.onOpenMcpSettings,
        surface = surface,
        viewModel = state.chatViewModel,
        onHtmlPreview = actions.onHtmlPreview,
        onOpenSkills = actions.onOpenSkills,
        onOpenWorkspace = actions.onOpenWorkspace,
        onOpenPromptTemplateManager = actions.onOpenPromptTemplateManager,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun DrawerHomeModelSheet(
    state: DrawerModelSheetState,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
) {
    val isAgentCanvas = state.activeCanvas == DrawerHomeCanvas.AGENT && state.botViewModel != null
    val sheetProviderId = if (isAgentCanvas) state.activeAgentProviderId else state.activeProviderId
    val sheetModelId = if (isAgentCanvas) state.activeAgentModelId else state.selectedModelId
    ModelSwitchSheet(
        providers = state.providers,
        activeProviderId = sheetProviderId,
        selectedModelId = sheetModelId,
        onPickProvider = { providerId ->
            viewModel.setActiveProvider(providerId)
            if (isAgentCanvas) {
                val firstModel = state.providers.firstOrNull { it.id == providerId }?.models?.firstOrNull()?.id
                if (!firstModel.isNullOrBlank()) {
                    state.botViewModel?.setModelRoute(
                        AgentModelRouter.Role.FOREGROUND,
                        providerId,
                        firstModel,
                    )
                } else {
                    state.botViewModel?.clearModelRoute(AgentModelRouter.Role.FOREGROUND)
                }
            }
        },
        onPickModel = { modelId ->
            viewModel.setSelectedModel(modelId)
            if (isAgentCanvas) {
                val ownerProviderId = modelId?.let { target ->
                    state.providers.firstOrNull {
                        it.id == sheetProviderId && it.models.any { model -> model.id == target }
                    }?.id ?: state.providers.firstOrNull { it.models.any { model -> model.id == target } }?.id
                } ?: sheetProviderId
                if (!modelId.isNullOrBlank() && !ownerProviderId.isNullOrBlank()) {
                    state.botViewModel?.setModelRoute(
                        AgentModelRouter.Role.FOREGROUND,
                        ownerProviderId,
                        modelId,
                    )
                } else {
                    state.botViewModel?.clearModelRoute(AgentModelRouter.Role.FOREGROUND)
                }
            }
        },
        onRefreshModels = viewModel::refreshModels,
        isFetchingModels = state.isFetchingModels,
        fetchModelsError = state.fetchModelsError,
        onDismiss = onDismiss,
    )
}

private val DrawerMaxWidth = 336.dp

/**
 * 极简抽屉首页（打开即开聊 + 左侧滑抽屉中枢）。
 *
 * 与经典三页布局共享全部业务数据、导航回调与 Agent 能力，只在交互骨架上收敛为：
 * 顶栏提供当前对话和模型的上下文，抽屉优先展示会话与文件夹。
 */
@Composable
internal fun DrawerHomeScreen(
    onOpenSettings: () -> Unit,
    onOpenAssistants: () -> Unit = {},
    onOpenScheduledTasks: () -> Unit = {},
    onOpenQuickNotes: () -> Unit = {},
    onOpenFolder: (String) -> Unit = {},
    onOpenQuickTranslate: () -> Unit = {},
    onOpenKnowledgeBase: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
    onOpenWorkspace: (String?) -> Unit = {},
    onOpenArtifactCenter: (() -> Unit)? = null,
    onOpenGroupChat: (String) -> Unit = {},
    onOpenArchivedChats: () -> Unit = {},
    onOpenArchivedGroupChats: () -> Unit = {},
    onOpenMiniPhone: () -> Unit = {},
    onHtmlPreview: (String) -> Unit = {},
    onOpenSkills: () -> Unit = {},
    onOpenPromptTemplateManager: () -> Unit = {},
    onOpenProviderSettings: () -> Unit = {},
    onOpenMcpSettings: ((String?) -> Unit)? = null,
    onOpenRuntimeInstall: () -> Unit = {},
    viewModel: ChatViewModel,
    settings: SettingsRepository,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
    val releaseJson by settings.latestReleaseInfoFlow.collectAsStateWithLifecycle(initialValue = null)
    val ignoredUpdateVersion by settings.ignoredUpdateVersionFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val reducedMotion = MuseMotion.isReducedMotion()

    val botViewModel: AgentBotViewModel? = if (FeatureFlags.AGENT_BOT) {
        koinInject()
    } else {
        null
    }

    var activeCanvas by rememberSaveable {
        mutableStateOf(if (botViewModel != null) DrawerHomeCanvas.AGENT else DrawerHomeCanvas.TASK)
    }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var drawerSearchQuery by rememberSaveable { mutableStateOf("") }
    var showModelSheet by remember { mutableStateOf(false) }
    var bannerDismissed by remember { mutableStateOf(false) }
    var showCommandPalette by rememberSaveable { mutableStateOf(false) }
    var showWorkspacePick by rememberSaveable { mutableStateOf(false) }
    var requestedWorkbenchTab by rememberSaveable { mutableStateOf<AgentWorkbenchTab?>(null) }

    val pendingHomeTab by viewModel.pendingHomeTab.collectAsStateWithLifecycle()
    LaunchedEffect(pendingHomeTab) {
        val target = pendingHomeTab ?: return@LaunchedEffect
        activeCanvas = when (target.coerceIn(0, 2)) {
            0 -> DrawerHomeCanvas.TASK
            2 -> DrawerHomeCanvas.GROUP_CHATS
            else -> if (botViewModel != null) DrawerHomeCanvas.AGENT else DrawerHomeCanvas.TASK
        }
        drawerOpen = false
        viewModel.consumePendingHomeTab()
    }

    LaunchedEffect(activeCanvas) {
        viewModel.setAgentMode(activeCanvas == DrawerHomeCanvas.AGENT)
    }

    LaunchedEffect(drawerOpen) {
        if (!drawerOpen) drawerSearchQuery = ""
    }

    LaunchedEffect(state.currentAssistant?.id, state.agentSessionId, activeCanvas) {
        if (botViewModel != null) {
            botViewModel.bind(
                botId = state.currentAssistant?.id ?: "default",
                originSessionId = state.agentSessionId.orEmpty(),
            )
        }
    }

    val botTaskBoardState by (
        botViewModel?.taskBoard ?: flowOf(
            AgentBotUiState.TaskBoard(),
        )
        ).collectAsStateWithLifecycle(initialValue = AgentBotUiState.TaskBoard())
    val botModelRoutesState by (
        botViewModel?.modelRoutes ?: flowOf(
            AgentModelRouter.Snapshot(),
        )
        ).collectAsStateWithLifecycle(initialValue = AgentModelRouter.Snapshot())
    val botActiveBadgeCount = botTaskBoardState.tasks.count { it.status in BotTaskStatus.NON_TERMINAL } +
        botTaskBoardState.approvals.size
    val botHasPendingApproval = botTaskBoardState.approvals.isNotEmpty()
    val effectiveAgentForegroundBinding = botModelRoutesState.foreground
    val effectiveAgentProviderId = effectiveAgentForegroundBinding.providerId.ifBlank {
        state.currentAssistant?.providerId?.takeIf { it.isNotBlank() } ?: state.activeProviderId
    }
    val effectiveAgentModelId = effectiveAgentForegroundBinding.modelId.ifBlank {
        state.currentAssistant?.modelId?.takeIf { it.isNotBlank() } ?: state.selectedModelId
    }
    val displayedProviderId = if (activeCanvas == DrawerHomeCanvas.AGENT) {
        effectiveAgentProviderId
    } else {
        state.activeProviderId
    }
    val displayedModelId = if (activeCanvas == DrawerHomeCanvas.AGENT) {
        effectiveAgentModelId
    } else {
        state.selectedModelId
    }
    val displayedModelName = remember(state.providers, displayedProviderId, displayedModelId) {
        state.providers.firstOrNull { it.id == displayedProviderId }
            ?.models?.firstOrNull { it.id == displayedModelId }?.name
            ?.takeIf(String::isNotBlank) ?: displayedModelId.orEmpty()
    }

    val currentVersionName = remember { UpdateNotifier.getCurrentVersionName(context) }
    val release = remember(releaseJson, bannerDismissed, ignoredUpdateVersion, currentVersionName) {
        if (bannerDismissed || releaseJson.isNullOrBlank()) {
            null
        } else {
            parseReleaseInfo(releaseJson)?.takeIf { info ->
                info.tagName != ignoredUpdateVersion &&
                    UpdateNotifier.compareVersions(currentVersionName, info.tagName) < 0
            }
        }
    }

    val glassConfig = LocalLiquidGlass.current
    val glassActive = glassConfig.enabled &&
        isRealGlassSupported() &&
        !LocalInsideBackdropRecorder.current
    val homeLayerBackdrop = if (glassActive && glassConfig.style == io.zer0.muse.ui.theme.GlassStyle.FROST) {
        rememberLayerBackdrop()
    } else {
        null
    }
    val homeWaterState = if (glassActive && glassConfig.style == io.zer0.muse.ui.theme.GlassStyle.WATER) {
        rememberLiquidState()
    } else {
        null
    }
    val homeBackgroundIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f

    val openNewTaskCanvas: () -> Unit = {
        drawerOpen = false
        activeCanvas = DrawerHomeCanvas.TASK
        viewModel.setAgentMode(false)
        viewModel.createNewSession()
    }

    val openSessionInCanvas: (String) -> Unit = { sessionId ->
        drawerOpen = false
        viewModel.setAgentMode(false)
        viewModel.switchSession(sessionId)
        activeCanvas = DrawerHomeCanvas.TASK
    }

    BackHandler(enabled = drawerOpen) {
        drawerOpen = false
    }
    BackHandler(enabled = !drawerOpen && activeCanvas != DrawerHomeCanvas.AGENT && botViewModel != null) {
        activeCanvas = DrawerHomeCanvas.AGENT
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayerBackdrop provides (homeLayerBackdrop ?: LocalLayerBackdrop.current),
        LocalWaterGlassState provides (homeWaterState ?: LocalWaterGlassState.current),
        LocalGlassIsBackgroundDark provides homeBackgroundIsDark,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .onKeyEvent { event ->
                    if (!desktopShortcutsEnabled || event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        event.key == DesktopShortcuts.FOCUS_SEARCH && event.isCtrlPressed -> {
                            showCommandPalette = true
                            true
                        }
                        event.key == DesktopShortcuts.NEW_CHAT && event.isCtrlPressed -> {
                            openNewTaskCanvas()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            MusePageScaffold(
                modifier = if (drawerOpen) Modifier.semantics { hideFromAccessibility() } else Modifier,
                topBarHandlesInsets = true,
                topBar = {
                    DrawerHomeTopBar(
                        activeCanvas = activeCanvas,
                        currentAssistant = state.currentAssistant,
                        currentSessionTitle = resolveActiveSessionTitle(
                            sessions = state.sessions,
                            currentSessionId = state.currentSessionId,
                            fallback = stringResource(R.string.chat_new_session),
                        ),
                        modelLabel = displayedModelName,
                        botViewModelPresent = botViewModel != null,
                        botActiveBadgeCount = botActiveBadgeCount,
                        botHasPendingApproval = botHasPendingApproval,
                        onOpenDrawer = { drawerOpen = true },
                        onOpenModelSheet = { showModelSheet = true },
                        onOpenWorkbench = { requestedWorkbenchTab = AgentWorkbenchTab.FOLLOW_UP },
                        onCreateNewTask = openNewTaskCanvas,
                        onOpenSearch = { showCommandPalette = true },
                        onOpenWorkspace = { showWorkspacePick = true },
                        onOpenArchivedChats = onOpenArchivedChats,
                        onOpenArchivedGroupChats = onOpenArchivedGroupChats,
                        onOpenMiniPhone = onOpenMiniPhone,
                        archivedChatsCount = state.archivedSessions.size,
                    )
                },
                containerColor = MaterialTheme.colorScheme.background,
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                ) {
                    if (release != null) {
                        DrawerHomeUpdateBanner(
                            release = release,
                            context = context,
                            onClose = {
                                bannerDismissed = true
                                scope.launch { settings.saveIgnoredUpdateVersion(release.tagName) }
                            },
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        DrawerHomeCanvas(
                            state = DrawerHomeCanvasState(
                                activeCanvas = activeCanvas,
                                botViewModel = botViewModel,
                                assistantName = state.currentAssistant?.name.orEmpty(),
                                avatarUri = state.currentAssistant?.avatarImageUrl?.takeIf { it.isNotBlank() },
                                modelName = effectiveAgentModelId.orEmpty(),
                                providers = state.providers,
                                externalWorkbenchTab = requestedWorkbenchTab,
                                chatViewModel = viewModel,
                            ),
                            agentActions = DrawerHomeAgentActions(
                                onOpenModel = { showModelSheet = true },
                                onOpenProviderSettings = onOpenProviderSettings,
                                onOpenRuntimeInstall = onOpenRuntimeInstall,
                                onOpenMcpConnections = onOpenMcpSettings?.let { open -> { open(null) } },
                                onOpenArtifactCenter = onOpenArtifactCenter,
                                onSwitchAssistant = viewModel::switchAgentAssistant,
                                onOpenGroupChats = { activeCanvas = DrawerHomeCanvas.GROUP_CHATS },
                                onOpenGroupChat = onOpenGroupChat,
                                onExternalWorkbenchTabConsumed = { requestedWorkbenchTab = null },
                            ),
                            chatActions = DrawerHomeChatActions(
                                onOpenAssistants = onOpenAssistants,
                                onOpenProviderSettings = onOpenProviderSettings,
                                onOpenMcpSettings = onOpenMcpSettings,
                                onHtmlPreview = onHtmlPreview,
                                onOpenSkills = onOpenSkills,
                                onOpenWorkspace = onOpenWorkspace,
                                onOpenPromptTemplateManager = onOpenPromptTemplateManager,
                            ),
                        )
                    }
                }
            }

            DrawerHomeNavigationOverlay(
                visible = drawerOpen,
                reducedMotion = reducedMotion,
                settings = settings,
                activeCanvas = activeCanvas,
                agentAvailable = botViewModel != null,
                currentSessionId = state.currentSessionId,
                sessions = state.sessions,
                folders = state.folders,
                searchQuery = drawerSearchQuery,
                onSearchQueryChange = { drawerSearchQuery = it },
                onClose = { drawerOpen = false },
                onSelectAgent = {
                    drawerOpen = false
                    activeCanvas = DrawerHomeCanvas.AGENT
                },
                onSelectGroupChats = {
                    drawerOpen = false
                    activeCanvas = DrawerHomeCanvas.GROUP_CHATS
                },
                onCreateNewTask = openNewTaskCanvas,
                onOpenGlobalSearch = {
                    drawerOpen = false
                    onOpenSearch()
                },
                onOpenKnowledgeBase = {
                    drawerOpen = false
                    onOpenKnowledgeBase()
                },
                onOpenArtifactCenter = onOpenArtifactCenter?.let { callback ->
                    {
                        drawerOpen = false
                        callback()
                    }
                },
                onOpenScheduledTasks = {
                    drawerOpen = false
                    onOpenScheduledTasks()
                },
                onOpenQuickNotes = {
                    drawerOpen = false
                    onOpenQuickNotes()
                },
                onOpenQuickTranslate = {
                    drawerOpen = false
                    onOpenQuickTranslate()
                },
                onOpenWorkspace = {
                    drawerOpen = false
                    showWorkspacePick = true
                },
                onOpenAssistants = {
                    drawerOpen = false
                    onOpenAssistants()
                },
                onOpenArchivedChats = {
                    drawerOpen = false
                    onOpenArchivedChats()
                },
                onOpenFolder = { folderId ->
                    drawerOpen = false
                    onOpenFolder(folderId)
                },
                onOpenSession = openSessionInCanvas,
                onOpenSettings = {
                    drawerOpen = false
                    onOpenSettings()
                },
            )
        }

        if (showModelSheet) {
            DrawerHomeModelSheet(
                state = DrawerModelSheetState(
                    activeCanvas = activeCanvas,
                    botViewModel = botViewModel,
                    providers = state.providers,
                    activeAgentProviderId = effectiveAgentProviderId,
                    activeAgentModelId = effectiveAgentModelId,
                    activeProviderId = state.activeProviderId,
                    selectedModelId = state.selectedModelId,
                    isFetchingModels = state.isFetchingModels,
                    fetchModelsError = state.fetchModelsError,
                ),
                viewModel = viewModel,
                onDismiss = { showModelSheet = false },
            )
        }

        if (showWorkspacePick) {
            WorkspaceQuickPickDialog(
                onOpen = { pickPath ->
                    showWorkspacePick = false
                    onOpenWorkspace(pickPath)
                },
                onDismiss = { showWorkspacePick = false },
            )
        }

        if (showCommandPalette) {
            CommandPalette(
                onDismiss = { showCommandPalette = false },
                onNewChat = openNewTaskCanvas,
                onOpenSettings = onOpenSettings,
                onOpenSearch = onOpenSearch,
                onOpenChat = {
                    viewModel.setAgentMode(false)
                    activeCanvas = DrawerHomeCanvas.TASK
                },
            )
        }
    }
}

@Composable
private fun DrawerHomeTopBar(
    activeCanvas: DrawerHomeCanvas,
    currentAssistant: AssistantEntity?,
    currentSessionTitle: String,
    modelLabel: String,
    botViewModelPresent: Boolean,
    botActiveBadgeCount: Int,
    botHasPendingApproval: Boolean,
    onOpenDrawer: () -> Unit,
    onOpenModelSheet: () -> Unit,
    onOpenWorkbench: () -> Unit,
    onCreateNewTask: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenArchivedChats: () -> Unit,
    onOpenArchivedGroupChats: () -> Unit,
    onOpenMiniPhone: () -> Unit,
    archivedChatsCount: Int,
) {
    val defaultAssistantName = stringResource(R.string.agent_screen_title)
    val groupChatTitle = stringResource(R.string.home_tab_group_chat)
    val centerTitle = when (activeCanvas) {
        DrawerHomeCanvas.AGENT -> currentAssistant?.name?.takeIf { it.isNotBlank() } ?: defaultAssistantName
        DrawerHomeCanvas.TASK -> currentSessionTitle
        DrawerHomeCanvas.GROUP_CHATS -> groupChatTitle
    }
    val switchModelCd = stringResource(R.string.home_drawer_switch_model_cd)
    val showMiniPhone = FeatureFlags.MINI_PHONE_ENTRY
    val searchLabel = stringResource(R.string.home_search_cd)
    val workspaceLabel = stringResource(R.string.workspace_title)
    val archivedLabel = stringResource(R.string.chat_list_archived_entry, archivedChatsCount)
    val archivedGroupsLabel = stringResource(R.string.groupchat_archived_title)
    val miniPhoneLabel = stringResource(R.string.home_miniphone_cd)
    val menuItems = buildList {
        add(
            MuseFloatingActionItem(
                key = "search",
                icon = MuseIcons.search,
                label = searchLabel,
                onClick = onOpenSearch,
            ),
        )
        if (showMiniPhone) {
            add(
                MuseFloatingActionItem(
                    key = "miniphone",
                    icon = MuseIcons.deviceMobile,
                    label = miniPhoneLabel,
                    onClick = onOpenMiniPhone,
                ),
            )
        }
        add(
            MuseFloatingActionItem(
                key = "workspace",
                icon = MuseIcons.folderOpen,
                label = workspaceLabel,
                onClick = onOpenWorkspace,
            ),
        )
        if (activeCanvas == DrawerHomeCanvas.GROUP_CHATS) {
            add(
                MuseFloatingActionItem(
                    key = "archived_groups",
                    icon = MuseIcons.archive,
                    label = archivedGroupsLabel,
                    onClick = onOpenArchivedGroupChats,
                ),
            )
        } else {
            add(
                MuseFloatingActionItem(
                    key = "archived_chats",
                    icon = MuseIcons.archive,
                    label = archivedLabel,
                    onClick = onOpenArchivedChats,
                ),
            )
        }
    }

    Surface(color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                    .statusBarsPadding()
                    .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MuseTopBarIconButton(
                    icon = MuseIcons.menu,
                    contentDescription = stringResource(R.string.home_drawer_open_cd),
                    onClick = onOpenDrawer,
                    solid = false,
                )

                val canSwitchModel = activeCanvas != DrawerHomeCanvas.GROUP_CHATS
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = MuseIconSizes.touchTarget)
                        .clip(MuseShapes.medium)
                        .then(
                            if (canSwitchModel) {
                                Modifier.clickable(
                                    role = Role.Button,
                                    onClickLabel = switchModelCd,
                                    onClick = onOpenModelSheet,
                                )
                            } else {
                                Modifier
                            },
                        )
                        .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                ) {
                    if (activeCanvas == DrawerHomeCanvas.AGENT && currentAssistant != null) {
                        AssistantAvatar(assistant = currentAssistant, avatarSize = MuseIconSizes.iconLarge)
                    }
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = centerTitle,
                            style = MaterialTheme.typography.titleSmallStrong,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (canSwitchModel && modelLabel.isNotBlank()) {
                            Text(
                                text = modelLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (canSwitchModel) {
                        Icon(
                            imageVector = MuseIcons.chevronDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(MuseIconSizes.iconTiny),
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    if (activeCanvas == DrawerHomeCanvas.AGENT && botViewModelPresent) {
                        BadgedBox(
                            badge = {
                                if (botActiveBadgeCount > 0) {
                                    Badge(
                                        containerColor = if (botHasPendingApproval) {
                                            MaterialTheme.statusColors.warningContainer
                                        } else {
                                            MaterialTheme.colorScheme.primaryContainer
                                        },
                                        contentColor = if (botHasPendingApproval) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        },
                                    ) {
                                        Text(if (botActiveBadgeCount > 9) "9+" else botActiveBadgeCount.toString())
                                    }
                                }
                            },
                        ) {
                            MuseTopBarIconButton(
                                icon = MuseIcons.computer,
                                contentDescription = stringResource(R.string.agent_hub_open_workbench),
                                onClick = onOpenWorkbench,
                                solid = false,
                                tint = if (botHasPendingApproval) {
                                    MaterialTheme.statusColors.warning
                                } else {
                                    Color.Unspecified
                                },
                            )
                        }
                    }
                    if (activeCanvas != DrawerHomeCanvas.GROUP_CHATS) {
                        MuseTopBarIconButton(
                            icon = MuseIcons.plus,
                            contentDescription = stringResource(R.string.chat_list_new_task),
                            onClick = onCreateNewTask,
                        )
                    } else {
                        MuseTopBarMenu(
                            contentDescription = stringResource(R.string.action_more),
                            items = menuItems,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.mutedSurface))
        }
    }
}

@Composable
private fun DrawerHomeNavigationOverlay(
    visible: Boolean,
    reducedMotion: Boolean,
    settings: SettingsRepository,
    activeCanvas: DrawerHomeCanvas,
    agentAvailable: Boolean,
    currentSessionId: String?,
    sessions: List<SessionEntity>,
    folders: List<FolderEntity>,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onSelectAgent: () -> Unit,
    onSelectGroupChats: () -> Unit,
    onCreateNewTask: () -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onOpenKnowledgeBase: () -> Unit,
    onOpenArtifactCenter: (() -> Unit)?,
    onOpenScheduledTasks: () -> Unit,
    onOpenQuickNotes: () -> Unit,
    onOpenQuickTranslate: () -> Unit,
    onOpenWorkspace: () -> Unit,
    onOpenAssistants: () -> Unit,
    onOpenArchivedChats: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val scrimInteraction = remember { MutableInteractionSource() }
    val closeLabel = stringResource(R.string.home_drawer_close_cd)
    val closeDrawer by rememberUpdatedState(onClose)
    val slideDirection = if (LocalLayoutDirection.current == LayoutDirection.Ltr) -1 else 1
    val keyboard = LocalSoftwareKeyboardController.current

    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) fadeIn(tween(0)) else fadeIn(tween(MuseAnimation.FAST_NORMAL_MS)),
        exit = if (reducedMotion) fadeOut(tween(0)) else fadeOut(tween(MuseAnimation.FAST_NORMAL_MS)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = MuseAlpha.mutedSurface))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onClose,
                )
                .semantics { hideFromAccessibility() },
        )
    }

    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) {
            fadeIn(tween(0))
        } else {
            slideInHorizontally(tween(MuseAnimation.NORMAL_MS)) { it * slideDirection } +
                fadeIn(tween(MuseAnimation.FAST_NORMAL_MS))
        },
        exit = if (reducedMotion) {
            fadeOut(tween(0))
        } else {
            slideOutHorizontally(tween(MuseAnimation.FAST_NORMAL_MS)) { it * slideDirection } +
                fadeOut(tween(MuseAnimation.FAST_NORMAL_MS))
        },
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val drawerWidth = minOf(DrawerMaxWidth, maxWidth * 0.88f)
            val navigatorTitle = stringResource(R.string.home_drawer_navigation_title)
            val newSessionFallback = stringResource(R.string.chat_new_session)
            val needle = searchQuery.trim()
            val searching = needle.isNotEmpty()
            val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            var toolsExpanded by rememberSaveable { mutableStateOf(false) }
            val listState = rememberLazyListState()
            val orderedSessions = remember(sessions) {
                sessions.distinctBy { it.id }.sortedByDescending { it.updatedAt }
            }
            val matchingSessions = remember(orderedSessions, needle, newSessionFallback) {
                if (!searching) {
                    orderedSessions
                } else {
                    orderedSessions.filter { session ->
                        displaySessionTitle(session.title, newSessionFallback).contains(needle, ignoreCase = true) ||
                            session.lastMessagePreview.contains(needle, ignoreCase = true)
                    }
                }
            }
            val matchingFolders = remember(folders, needle) {
                if (!searching) folders else folders.filter { it.name.contains(needle, ignoreCase = true) }
            }
            val (pinnedSessions, recentSessions) = remember(matchingSessions) {
                matchingSessions.partition { it.pinned }
            }
            LaunchedEffect(searching) {
                listState.scrollToItem(0)
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(topEnd = MuseSpacing.xxl, bottomEnd = MuseSpacing.xxl),
                modifier = Modifier
                    .width(drawerWidth)
                    .fillMaxHeight()
                    .semantics { paneTitle = navigatorTitle }
                    .pointerInput(slideDirection) {
                        var closeDistance = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { closeDistance = 0f },
                            onHorizontalDrag = { _, amount -> closeDistance += amount * slideDirection },
                            onDragEnd = {
                                if (closeDistance >= MuseIconSizes.touchTarget.toPx()) closeDrawer()
                            },
                        )
                    },
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal))
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .imePadding(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = MusePaddings.screen, end = MuseSpacing.sm, top = MuseSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
                    ) {
                        Text(
                            text = navigatorTitle,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { heading() },
                        )
                        MuseTopBarIconButton(
                            icon = MuseIcons.x,
                            onClick = onClose,
                            contentDescription = closeLabel,
                            solid = false,
                        )
                    }
                    MuseSearchField(
                        query = searchQuery,
                        onQueryChange = onSearchQueryChange,
                        placeholder = stringResource(R.string.home_drawer_search_placeholder),
                        modifier = Modifier.padding(horizontal = MusePaddings.screen),
                        onSearch = { keyboard?.hide() },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        minHeight = MuseIconSizes.touchTarget,
                    )

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(
                            horizontal = MusePaddings.screen,
                            vertical = MuseSpacing.sm,
                        ),
                    ) {
                        if (searching) {
                            item(key = "global_search") {
                                DrawerLink(
                                    icon = MuseIcons.search,
                                    label = stringResource(R.string.home_drawer_global_search),
                                    onClick = onOpenGlobalSearch,
                                )
                            }
                        } else {
                            if (!keyboardVisible) {
                                item(key = "greeting") { DrawerGreeting(settings) }
                            }
                            item(key = "destinations") {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = MuseSpacing.xs),
                                    horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                                ) {
                                    if (agentAvailable) {
                                        DrawerDestination(
                                            icon = MuseIcons.robot,
                                            label = stringResource(R.string.home_drawer_mode_agent),
                                            selected = activeCanvas == DrawerHomeCanvas.AGENT,
                                            onClick = onSelectAgent,
                                            modifier = Modifier.weight(1f),
                                        )
                                    }
                                    DrawerDestination(
                                        icon = MuseIcons.users,
                                        label = stringResource(R.string.home_drawer_mode_groups),
                                        selected = activeCanvas == DrawerHomeCanvas.GROUP_CHATS,
                                        onClick = onSelectGroupChats,
                                        modifier = Modifier.weight(1f),
                                    )
                                    DrawerDestination(
                                        icon = MuseIcons.plus,
                                        label = stringResource(R.string.home_drawer_mode_new_task),
                                        selected = false,
                                        primaryAction = true,
                                        onClick = onCreateNewTask,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            item(key = "tools_toggle") {
                                DrawerLink(
                                    icon = MuseIcons.stack,
                                    label = stringResource(R.string.home_drawer_hub_title),
                                    trailingIcon = if (toolsExpanded) MuseIcons.chevronUp else MuseIcons.chevronDown,
                                    actionLabel = stringResource(if (toolsExpanded) R.string.common_collapse else R.string.common_expand),
                                    onClick = { toolsExpanded = !toolsExpanded },
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.mutedSurface))
                            }
                            if (toolsExpanded) {
                                item(key = "tools") {
                                    Column(
                                        modifier = Modifier.padding(vertical = MuseSpacing.xs),
                                    ) {
                                        val tools = buildList {
                                            add(
                                                MuseFloatingActionItem(
                                                    "knowledge",
                                                    MuseIcons.book,
                                                    stringResource(R.string.home_drawer_hub_knowledge),
                                                    onClick = onOpenKnowledgeBase,
                                                ),
                                            )
                                            onOpenArtifactCenter?.let {
                                                add(
                                                    MuseFloatingActionItem(
                                                        "artifacts",
                                                        MuseIcons.packageIcon,
                                                        stringResource(R.string.home_drawer_hub_artifacts),
                                                        onClick = it,
                                                    ),
                                                )
                                            }
                                            add(
                                                MuseFloatingActionItem(
                                                    "workspace",
                                                    MuseIcons.folderOpen,
                                                    stringResource(R.string.home_drawer_hub_workspace),
                                                    onClick = onOpenWorkspace,
                                                ),
                                            )
                                            add(
                                                MuseFloatingActionItem(
                                                    "schedules",
                                                    MuseIcons.calendarTime,
                                                    stringResource(R.string.home_drawer_hub_schedules),
                                                    onClick = onOpenScheduledTasks,
                                                ),
                                            )
                                            add(
                                                MuseFloatingActionItem(
                                                    "notes",
                                                    MuseIcons.edit,
                                                    stringResource(R.string.home_drawer_hub_notes),
                                                    onClick = onOpenQuickNotes,
                                                ),
                                            )
                                            add(
                                                MuseFloatingActionItem(
                                                    "translate",
                                                    MuseIcons.languages,
                                                    stringResource(R.string.home_drawer_hub_translate),
                                                    onClick = onOpenQuickTranslate,
                                                ),
                                            )
                                            add(
                                                MuseFloatingActionItem(
                                                    "assistants",
                                                    MuseIcons.robot,
                                                    stringResource(R.string.home_drawer_hub_assistants),
                                                    onClick = onOpenAssistants,
                                                ),
                                            )
                                            add(
                                                MuseFloatingActionItem(
                                                    "search",
                                                    MuseIcons.search,
                                                    stringResource(R.string.home_drawer_global_search),
                                                    onClick = onOpenGlobalSearch,
                                                ),
                                            )
                                        }
                                        tools.chunked(2).forEach { pair ->
                                            Row(horizontalArrangement = Arrangement.spacedBy(MuseSpacing.xs)) {
                                                pair.forEach { tool ->
                                                    DrawerLink(tool.icon, tool.label, tool.onClick, Modifier.weight(1f))
                                                }
                                            }
                                        }
                                    }
                                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.mutedSurface))
                                }
                            }
                        }

                        if (pinnedSessions.isNotEmpty()) {
                            item(key = "hdr_pinned") {
                                DrawerSectionHeader(stringResource(R.string.home_drawer_section_pinned))
                            }
                            items(pinnedSessions, key = { "session_${it.id}" }) { session ->
                                DrawerSessionRow(
                                    session = session,
                                    fallbackTitle = newSessionFallback,
                                    selected = activeCanvas == DrawerHomeCanvas.TASK && session.id == currentSessionId,
                                    onOpenSession = onOpenSession,
                                )
                            }
                        }

                        if (recentSessions.isNotEmpty()) {
                            item(key = "hdr_recent") {
                                DrawerSectionHeader(stringResource(R.string.home_drawer_section_recent))
                            }
                            items(recentSessions, key = { "session_${it.id}" }) { session ->
                                DrawerSessionRow(
                                    session = session,
                                    fallbackTitle = newSessionFallback,
                                    selected = activeCanvas == DrawerHomeCanvas.TASK && session.id == currentSessionId,
                                    onOpenSession = onOpenSession,
                                )
                            }
                        }

                        if (matchingFolders.isNotEmpty()) {
                            item(key = "hdr_folders") {
                                DrawerSectionHeader(stringResource(R.string.home_drawer_section_folders))
                            }
                            items(matchingFolders, key = { "folder_${it.id}" }) { folder ->
                                DrawerLink(
                                    icon = MuseIcons.folder,
                                    label = folder.name,
                                    detail = folder.sessionCount.takeIf { it > 0 }?.toString(),
                                    onClick = { onOpenFolder(folder.id) },
                                )
                            }
                        }

                        if (matchingSessions.isEmpty() && (!searching || matchingFolders.isEmpty())) {
                            item(key = "empty_conversations") {
                                Text(
                                    text = stringResource(
                                        if (searching) R.string.home_drawer_no_matches else R.string.home_drawer_no_conversations,
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.lg),
                                )
                            }
                        }
                    }

                    if (!searching) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = MuseAlpha.mutedSurface))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MusePaddings.screen, vertical = MuseSpacing.xs),
                            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
                        ) {
                            DrawerLink(
                                icon = MuseIcons.archive,
                                label = stringResource(R.string.home_drawer_hub_archived),
                                onClick = onOpenArchivedChats,
                                modifier = Modifier.weight(1f),
                            )
                            DrawerLink(
                                icon = MuseIcons.sliders,
                                label = stringResource(R.string.home_drawer_settings),
                                onClick = onOpenSettings,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerGreeting(settings: SettingsRepository, factDao: FactDao = koinInject()) {
    val context = LocalContext.current
    val resources = context.resources
    val now = remember { LocalDateTime.now() }
    val today = now.toLocalDate()
    val greeting = GreetingHelper.getTimeGreeting(now.hour, resources)
    val summary by settings.dailySummaryFlow.collectAsStateWithLifecycle(initialValue = null)
    val memoryEnabled by settings.memoryEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val languageTag = resources.configuration.locales[0].toLanguageTag()
    val reminder by produceState<String?>(null, memoryEnabled, today, languageTag) {
        value = null
        if (!memoryEnabled) return@produceState
        try {
            val prefix = GreetingHelper.hintCachePrefix(today, languageTag)
            val cached = settings.getGreetingHintCache()?.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            value = GreetingHelper.compactGreetingText(cached, GreetingHelper.PERSONALIZED_HINT_MAX_LENGTH)
                ?: GreetingHelper.getMemoryHint(factDao.likeSearch(query = "", limit = 100, scope = "main"), today, resources)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            Logger.w("DrawerHomeScreen", "侧栏提示读取失败", error)
        } catch (error: IllegalStateException) {
            Logger.w("DrawerHomeScreen", "侧栏提示读取失败", error)
        }
    }
    val dailySummary = remember(context, summary, today) {
        GreetingHelper.getDrawerSummaryHint(context, summary?.text, summary?.date, today)
    }
    val detail = reminder
        ?.takeIf { memoryEnabled }
        ?.removePrefix(greeting)
        ?.trimStart('，', ',', ' ')
        ?.ifBlank { null }
        ?: GreetingHelper.getDateLine(today, resources)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.smTight),
        verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs),
    ) {
        Text(greeting, style = MaterialTheme.typography.titleSmallStrong)
        dailySummary?.let { summaryText ->
            Text(
                text = summaryText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (dailySummary == null || detail != dailySummary) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DrawerDestination(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primaryAction: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val foreground = when {
        primaryAction -> colors.onPrimary
        selected -> colors.onSecondaryContainer
        else -> colors.onSurfaceVariant
    }
    Surface(
        shape = MuseShapes.medium,
        color = when {
            primaryAction -> colors.primary
            selected -> colors.secondaryContainer
            else -> Color.Transparent
        },
        modifier = modifier
            .clip(MuseShapes.medium)
            .clickable(role = Role.Button, onClick = onClick)
            .then(if (primaryAction) Modifier else Modifier.semantics { this.selected = selected }),
    ) {
        Column(
            modifier = Modifier
                .heightIn(min = MuseIconSizes.fab)
                .padding(horizontal = MuseSpacing.xs, vertical = MuseSpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MuseSpacing.xs, Alignment.CenterVertically),
        ) {
            Icon(icon, null, tint = foreground, modifier = Modifier.size(MuseIconSizes.iconSmall))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = foreground,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun DrawerLink(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailingIcon: ImageVector? = null,
    detail: String? = null,
    actionLabel: String? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MuseIconSizes.touchTarget)
            .clip(MuseShapes.medium)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(MuseSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(MuseIconSizes.iconSmall))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (detail != null) {
            Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (trailingIcon != null) {
            Icon(trailingIcon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(MuseIconSizes.iconTiny))
        }
    }
}

@Composable
private fun DrawerSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmallStrong,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = MuseSpacing.sm, top = MuseSpacing.lg, bottom = MuseSpacing.xs)
            .semantics { heading() },
    )
}

@Composable
private fun DrawerSessionRow(session: SessionEntity, fallbackTitle: String, selected: Boolean, onOpenSession: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MuseShapes.medium,
        color = if (selected) colors.secondaryContainer else Color.Transparent,
        contentColor = if (selected) colors.onSecondaryContainer else colors.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MuseShapes.medium)
            .clickable(role = Role.Button) { onOpenSession(session.id) }
            .semantics { this.selected = selected },
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = MuseIconSizes.touchTarget)
                .padding(horizontal = MuseSpacing.sm, vertical = MuseSpacing.smTight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MuseSpacing.md),
        ) {
            Icon(
                imageVector = if (session.pinned) MuseIcons.pin else MuseIcons.chat,
                contentDescription = null,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
                tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(MuseSpacing.xxs)) {
                Text(
                    text = displaySessionTitle(session.title, fallbackTitle),
                    style = MaterialTheme.typography.bodyMediumStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (session.lastMessagePreview.isNotBlank()) {
                    Text(
                        text = session.lastMessagePreview,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun resolveActiveSessionTitle(sessions: List<SessionEntity>, currentSessionId: String?, fallback: String): String {
    val session = currentSessionId?.let { id -> sessions.firstOrNull { it.id == id } } ?: return fallback
    return displaySessionTitle(session.title, fallback)
}
