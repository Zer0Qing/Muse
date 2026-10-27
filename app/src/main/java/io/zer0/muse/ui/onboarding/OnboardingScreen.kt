package io.zer0.muse.ui.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.data.provider.ProviderModelFetcher
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.settings.ModelAbilityChips
import io.zer0.muse.ui.theme.MuseAnimation
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.PresetThemes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

// ── 测试连接状态 ──────────────────────────────────────────────
private sealed class TestStatus {
    data object Idle : TestStatus()
    data object Loading : TestStatus()
    data class Success(val models: List<Model>) : TestStatus()
    data class Error(val message: String) : TestStatus()
}

/**
 * 开机引导页面 — 全屏 HorizontalPager,8 步完成初始配置:
 * 欢迎(多语言问候轮播) → 语言 → 认识 → 记忆 → 接入 → 模型 → 外观 → 出发。
 *
 * 数据流：OnboardingScreen 接收 [onComplete] 回调，内部用 koinInject 获取
 * [SettingsRepository] / [AssistantRepository] / [PresetProviders]，
 * 每步完成后即时保存到 SettingsRepository。
 *
 * 设计令牌：使用 [MuseShapes]（圆角）与 [MusePaddings]（间距令牌），
 * 全程 Material Icons（无 emoji），Material 3 组件。
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val assistantRepo: AssistantRepository = koinInject()
    val presetProviders: PresetProviders = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 8 })
    val keyboardController = LocalSoftwareKeyboardController.current

    // ── 各步骤状态 ──
    var selectedLanguage by rememberSaveable { mutableStateOf("zh") }
    var selectedThemeId by rememberSaveable { mutableStateOf("mono") }
    var userName by rememberSaveable { mutableStateOf("") }
    var agentName by rememberSaveable { mutableStateOf("Muse") }
    var selectedPresetId by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var customBaseUrl by rememberSaveable { mutableStateOf("") }
    var apiKeyVisible by rememberSaveable { mutableStateOf(false) }
    var testStatus by remember { mutableStateOf<TestStatus>(TestStatus.Idle) }
    var fetchedModels by remember { mutableStateOf<List<Model>>(emptyList()) }
    var selectedModelId by rememberSaveable { mutableStateOf("") }
    var modelSearchQuery by rememberSaveable { mutableStateOf("") }
    var providerSkipped by rememberSaveable { mutableStateOf(false) }
    // v2.x: 记忆系统开关(新增步骤;默认开,切换即时落库)
    var memoryEnabled by rememberSaveable { mutableStateOf(true) }

    // 翻页时收起键盘
    LaunchedEffect(pagerState.currentPage) {
        keyboardController?.hide()
    }

    // 当前选中的预设供应商（自定义时注入用户填写的 baseUrl）
    val selectedPreset: ProviderConfig? = remember(selectedPresetId, customBaseUrl) {
        presetProviders.all.firstOrNull { it.id == selectedPresetId }?.let { preset ->
            if (preset.id == "preset_custom_openai" && customBaseUrl.isNotBlank()) {
                preset.copy(baseUrl = customBaseUrl)
            } else {
                preset
            }
        }
    }

    val pageCount = 8
    val currentPage = pagerState.currentPage

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶部进度圆点
        ProgressDots(
            currentPage = currentPage,
            pageCount = pageCount,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MusePaddings.sectionGap),
        )

        // 内容区域
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
            userScrollEnabled = false,
        ) { page ->
            when (page) {
                0 -> StepWelcome()
                1 -> StepLanguage(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { lang ->
                        selectedLanguage = lang
                        // I4: 语言热切换 — 保存后由 RuntimeLocaleProvider 即时重组,不重建 Activity
                        scope.launch { settings.saveLanguage(lang) }
                    },
                )
                2 -> StepNames(
                    userName = userName,
                    onUserNameChange = { userName = it },
                    agentName = agentName,
                    onAgentNameChange = { agentName = it },
                )
                3 -> StepMemory(
                    memoryEnabled = memoryEnabled,
                    onMemoryEnabledChange = { v ->
                        memoryEnabled = v
                        scope.launch { settings.saveMemoryEnabled(v) }
                    },
                )
                4 -> StepProviderConfig(
                    presetProviders = presetProviders,
                    selectedPresetId = selectedPresetId,
                    onPresetSelected = { id ->
                        selectedPresetId = id
                        testStatus = TestStatus.Idle
                        fetchedModels = emptyList()
                    },
                    apiKey = apiKey,
                    onApiKeyChange = {
                        apiKey = it
                        testStatus = TestStatus.Idle
                    },
                    customBaseUrl = customBaseUrl,
                    onCustomBaseUrlChange = {
                        customBaseUrl = it
                        testStatus = TestStatus.Idle
                    },
                    apiKeyVisible = apiKeyVisible,
                    onToggleApiKeyVisible = { apiKeyVisible = !apiKeyVisible },
                    testStatus = testStatus,
                    onTestConnection = {
                        val preset = selectedPreset
                        // v1.0.18: allowMissingApiKey 供应商(如 SiliconFlow 免费)允许不填 key 测试连接
                        if (preset == null || (apiKey.isBlank() && !preset.allowMissingApiKey)) return@StepProviderConfig
                        testStatus = TestStatus.Loading
                        scope.launch {
                            val config = preset.copy(apiKey = apiKey)
                            // 以一次真实对话验证，而不是只看 /models。
                            // 引导页是用户第一次接触一个供应商的地方，这里判失败等于告诉他“用不了”，
                            // 而 /models 往往只是没实现或做了单独鉴权。
                            when (val outcome = ProviderModelFetcher.verify(config)) {
                                is ProviderModelFetcher.VerifyOutcome.Ready -> {
                                    fetchedModels = outcome.models
                                    testStatus = TestStatus.Success(outcome.models)
                                    selectedModelId = outcome.modelId
                                    // 测试成功即落库(幂等),不再依赖"下一步"时机 ——
                                    // 此前测试成功后再点"跳过"会把已填内容静默丢弃。
                                    val bound = config.copy(
                                        baseUrl = outcome.usedConfig.baseUrl,
                                        models = outcome.models,
                                    )
                                    settings.upsertProvider(bound)
                                    settings.setActiveProvider(bound.id)
                                    settings.saveSelectedModel(outcome.modelId)
                                }
                                is ProviderModelFetcher.VerifyOutcome.NotReady -> {
                                    Logger.w("Onboarding", "测试连接失败: ${outcome.kind} ${outcome.reason}")
                                    testStatus = TestStatus.Error(
                                        context.getString(
                                            R.string.settings_provider_test_error_failed,
                                            friendlyConnectError(outcome.reason),
                                        ),
                                    )
                                }
                            }
                        }
                    },
                    onSkip = {
                        // v2.x: 测试成功时配置已在测试环节即时落库;跳过 = 跳过"接入+模型"两步,
                        // 主题与收尾保留(跳转到"外观"步)。
                        val preset = selectedPreset
                        if (testStatus is TestStatus.Success && preset != null) {
                            scope.launch {
                                if (selectedModelId.isNotBlank()) {
                                    settings.saveSelectedModel(selectedModelId)
                                }
                                pagerState.animateScrollToPage(6)
                            }
                        } else {
                            providerSkipped = true
                            scope.launch { pagerState.animateScrollToPage(6) }
                        }
                    },
                )
                5 -> StepSelectModel(
                    models = fetchedModels,
                    selectedModelId = selectedModelId,
                    onSelectModel = { id ->
                        selectedModelId = id
                        scope.launch { settings.saveSelectedModel(id) }
                    },
                    searchQuery = modelSearchQuery,
                    onSearchQueryChange = { modelSearchQuery = it },
                    providerSkipped = providerSkipped,
                )
                6 -> StepTheme(
                    selectedThemeId = selectedThemeId,
                    onThemeSelected = { id ->
                        selectedThemeId = id
                        scope.launch { settings.saveThemeId(id) }
                    },
                )
                7 -> StepComplete()
            }
        }

        // 底部按钮区域
        BottomButtons(
            currentPage = currentPage,
            // 接入步(4)需测试成功才能下一步
            canGoNext = when (currentPage) {
                4 -> testStatus is TestStatus.Success
                else -> true
            },
            onPrevious = {
                scope.launch { pagerState.animateScrollToPage(currentPage - 1) }
            },
            onNext = {
                // 翻页前保存当前步骤数据
                scope.launch {
                    saveStepData(
                        currentPage = currentPage,
                        settings = settings,
                        assistantRepo = assistantRepo,
                        userName = userName,
                        agentName = agentName,
                        selectedPreset = selectedPreset,
                        apiKey = apiKey,
                        fetchedModels = fetchedModels,
                        selectedModelId = selectedModelId,
                        providerSkipped = providerSkipped,
                    )
                    val targetPage = when {
                        // 接入步(4)→ 若已跳过配置则直接去"外观"(6),顺带跳过模型步
                        currentPage == 4 && providerSkipped -> 6
                        else -> currentPage + 1
                    }
                    pagerState.animateScrollToPage(targetPage)
                }
            },
            onComplete = {
                scope.launch {
                    saveStepData(
                        currentPage = currentPage,
                        settings = settings,
                        assistantRepo = assistantRepo,
                        userName = userName,
                        agentName = agentName,
                        selectedPreset = selectedPreset,
                        apiKey = apiKey,
                        fetchedModels = fetchedModels,
                        selectedModelId = selectedModelId,
                        providerSkipped = providerSkipped,
                    )
                    settings.saveOnboardingShown()
                    onComplete()
                }
            },
        )
    }
}

// ── 顶部进度圆点 ──────────────────────────────────────────────
@Composable
private fun ProgressDots(currentPage: Int, pageCount: Int, modifier: Modifier = Modifier) {
    // 圆点只能表达「大概还有多少」，读不出具体位置。右侧补一个「N / M」
    // 让用户直接知道自己在第几步、总共几步。
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isCurrent = index == currentPage
            val isCompleted = index < currentPage
            val size by animateDpAsState(
                targetValue = if (isCurrent) 10.dp else 6.dp,
                animationSpec = MuseMotion.tween(MuseAnimation.FAST_NORMAL_MS),
                label = "dot_size",
            )
            val color = when {
                isCurrent -> MaterialTheme.colorScheme.onSurface
                isCompleted -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Box(
                modifier = Modifier
                    .padding(horizontal = MusePaddings.tightGap)
                    .size(size)
                    .clip(CircleShape)
                    .background(color),
            )
        }
        Spacer(Modifier.width(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_step_progress, currentPage + 1, pageCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 步骤 0：欢迎页 ──────────────────────────────────────────────
/**
 * 问候语轮播表 — 跟随支持的语言逐条切换(中文优先,其次英文,其余顺序轮转)。
 * 每条本身就是对应语言的原文,不走资源本地化。
 */
private val WELCOME_GREETINGS = listOf(
    "嗨，终于见面了。",
    "Hi, nice to meet you.",
    "はじめまして。",
    "반가워요.",
    "Hola, encantado.",
    "Olá, prazer.",
    "Привет, рады знакомству.",
)

@Composable
private fun StepWelcome() {
    // v2.x: 首屏重构 — 大 logo 单放(无底框),名字在下,问候语从 logo 处"展开"入场。
    // 不再有副标题与特性磁贴:首屏只留 logo / 名字 / 一句问候。
    var entered by remember { mutableStateOf(false) }
    var nameVisible by remember { mutableStateOf(false) }
    var greetingVisible by remember { mutableStateOf(false) }
    var greetingIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        // 入场时序:logo → 名字 → 问候"展开";之后进入多语言轮播(2.6s/条)
        entered = true
        delay(260)
        nameVisible = true
        delay(390)
        greetingVisible = true
        while (true) {
            delay(2600)
            greetingIndex = (greetingIndex + 1) % WELCOME_GREETINGS.size
        }
    }

    val logoAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = MuseMotion.tween(700),
        label = "welcomeLogoAlpha",
    )
    val logoScale by animateFloatAsState(
        targetValue = if (entered) 1f else 0.86f,
        animationSpec = MuseMotion.tween(900),
        label = "welcomeLogoScale",
    )
    val nameAlpha by animateFloatAsState(
        targetValue = if (nameVisible) 1f else 0f,
        animationSpec = MuseMotion.tween(520),
        label = "welcomeNameAlpha",
    )
    val nameOffset by animateDpAsState(
        targetValue = if (nameVisible) 0.dp else 10.dp,
        animationSpec = MuseMotion.tween(560),
        label = "welcomeNameOffset",
    )
    val greetingAlpha by animateFloatAsState(
        targetValue = if (greetingVisible) 1f else 0f,
        animationSpec = MuseMotion.tween(560),
        label = "welcomeGreetingAlpha",
    )
    val greetingScale by animateFloatAsState(
        targetValue = if (greetingVisible) 1f else 0.5f,
        animationSpec = MuseMotion.tween(680, easing = MuseAnimation.EaseOutCubic),
        label = "welcomeGreetingScale",
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 大 logo — 单放,不加底框
        Image(
            painter = painterResource(R.drawable.ic_muse_logo),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(176.dp)
                .graphicsLayer {
                    alpha = logoAlpha
                    scaleX = logoScale
                    scaleY = logoScale
                },
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .graphicsLayer { alpha = nameAlpha }
                .offset(y = nameOffset),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        // 问候语:从 logo 方向"展开"出场(顶端为缩放原点),之后多语言轮播交叉淡入
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .graphicsLayer {
                    alpha = greetingAlpha
                    scaleX = greetingScale
                    scaleY = greetingScale
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = greetingIndex,
                animationSpec = MuseMotion.tween(MuseAnimation.NORMAL_MS),
                // 用 Crossfade 而不是 AnimatedContent：后者默认带 SizeTransform，
                // 两条不同高度的问候语会同时参与布局并叠在同一位置，
                // 视觉上是两句话压在一起。Crossfade 只在同一槽位内做淡入淡出。
                label = "welcomeGreeting",
            ) { index ->
                Text(
                    text = WELCOME_GREETINGS[index],
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private data class FeatureItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val description: String,
)

// ── 步骤 1：语言 ────────────────────────────────────────────────
@Composable
private fun StepLanguage(selectedLanguage: String, onLanguageSelected: (String) -> Unit) {
    val languages = listOf(
        "zh" to stringResource(R.string.onboarding_lang_zh),
        "zh-TW" to stringResource(R.string.onboarding_lang_zh_tw),
        "en" to stringResource(R.string.onboarding_lang_en),
        "ja" to stringResource(R.string.onboarding_lang_ja),
        "ko" to stringResource(R.string.onboarding_lang_ko),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_language_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_language_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        languages.forEach { (code, label) ->
            LanguageCard(
                label = label,
                selected = selectedLanguage == code,
                onClick = { onLanguageSelected(code) },
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }
    }
}

// ── 步骤 6：外观 ────────────────────────────────────────────────
@Composable
private fun StepTheme(selectedThemeId: String, onThemeSelected: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_theme_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_theme_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        // 12 套主题网格（3 列 x 4 行）
        PresetThemes.chunked(3).forEach { rowThemes ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
            ) {
                rowThemes.forEach { theme ->
                    ThemeColorBlock(
                        theme = theme,
                        selected = selectedThemeId == theme.id,
                        onClick = { onThemeSelected(theme.id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // 不足 3 个时补齐占位，保持网格对齐
                repeat(3 - rowThemes.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }
    }
}

@Composable
private fun LanguageCard(label: String, selected: Boolean, onClick: () -> Unit) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val borderWidth = if (selected) 1.5.dp else 1.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (selected) {
                Icon(
                    imageVector = MuseIcons.circleCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ThemeColorBlock(
    theme: io.zer0.muse.ui.theme.PresetTheme,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    }
    val borderWidth = if (selected) 1.5.dp else 1.dp
    val label = stringResource(theme.nameResId)
    Surface(
        modifier = modifier
            .heightIn(min = 72.dp)
            .clickable(onClick = onClick),
        shape = MuseShapes.semiLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = theme.lightScheme.primary,
                modifier = Modifier.size(28.dp),
            ) {}
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = if (selected) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── 步骤 2：你的名字 ──────────────────────────────────────────
@Composable
private fun StepNames(userName: String, onUserNameChange: (String) -> Unit, agentName: String, onAgentNameChange: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_names_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_names_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        // v1.0.28: label 放到输入框上方,避免 OutlinedTextField 内部 label 的白色背景块
        Text(
            text = stringResource(R.string.onboarding_names_user_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.tightGap))
        MuseTextField(
            value = userName,
            onValueChange = onUserNameChange,
            placeholder = { Text(stringResource(R.string.onboarding_names_user_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_names_agent_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.tightGap))
        MuseTextField(
            value = agentName,
            onValueChange = onAgentNameChange,
            placeholder = { Text(stringResource(R.string.onboarding_names_agent_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_names_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ── 步骤 3：记忆 ────────────────────────────────────────────────
@Composable
private fun StepMemory(memoryEnabled: Boolean, onMemoryEnabledChange: (Boolean) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.onboarding_memory_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_memory_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        // 记忆开关卡片:标题 + 一行说明 + 右侧 Switch
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MuseShapes.semiLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(MusePaddings.cardInner),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = MuseIcons.atom,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.width(MusePaddings.iconPadding))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.onboarding_memory_switch_title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.onboarding_memory_switch_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(MusePaddings.contentGap))
                MuseSwitch(
                    checked = memoryEnabled,
                    onCheckedChange = onMemoryEnabledChange,
                )
            }
        }
    }
}

// ── 步骤 4：配置供应商 ──────────────────────────────────────────
/** 引导页供应商预设列表的最大可视高度（约 5 行），超出后列表内部滚动。 */
private val ONBOARDING_PRESET_LIST_MAX_HEIGHT = 320.dp

/**
 * 把上游/内部错误翻译成用户能行动的一句话。
 *
 * 上游返回的原文（如 `HTTP 500 [server error]: Model ...`）留在日志里，
 * 界面上先给出「该去改什么」，再附一小段原始信息供求助时复制。
 */
private fun friendlyConnectError(raw: String): String {
    val lower = raw.lowercase()
    // 规则表：按顺序匹配，命中即返回。
    // 用表而非长 when，避免单函数圈复杂度超标。
    val rules = listOf(
        listOf("401", "unauthorized", "invalid api key") to "API 密钥无效或已过期，请重新填写",
        listOf("403", "forbidden") to "没有访问权限，请确认该密钥已开通此模型",
        listOf("404", "model_not_found", "no such model") to "接口地址或模型名称不对，请核对后重试",
        listOf("429", "rate limit") to "请求过于频繁，请稍后再试",
        listOf("timeout", "timed out") to "连接超时，请检查网络或接口地址",
        listOf("500", "502", "503", "server error") to "供应商服务异常，稍后再试；若持续失败，可能是内置模型已下线",
        listOf("no model") to "没有找到可测试的模型，请先点击「获取」拉取模型列表",
    )
    val hit = rules.firstOrNull { (keys, _) -> keys.any { it in lower } }
    return hit?.second ?: raw
}

@Composable
private fun StepProviderConfig(
    presetProviders: PresetProviders,
    selectedPresetId: String,
    onPresetSelected: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    customBaseUrl: String,
    onCustomBaseUrlChange: (String) -> Unit,
    apiKeyVisible: Boolean,
    onToggleApiKeyVisible: () -> Unit,
    testStatus: TestStatus,
    onTestConnection: () -> Unit,
    onSkip: () -> Unit,
) {
    val isCustomSelected = selectedPresetId == "preset_custom_openai"
    val selectedPreset = presetProviders.all.firstOrNull { it.id == selectedPresetId }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_provider_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_provider_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(MusePaddings.itemGap))

        // 供应商选择列表（精选：海外/国产/中转各约 1/3 + 自定义）
        Text(
            text = stringResource(R.string.onboarding_provider_select_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        // 预设列表独立滚动并限高：此前 20+ 项平铺，把下方的 Key 输入框与
        // 「测试连接」按钮推到屏幕外，用户选完供应商却找不到继续的办法。
        // 底部渐隐提示「还有内容可滚」，避免最后一行被整齐切断成半张残缺卡片。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = ONBOARDING_PRESET_LIST_MAX_HEIGHT),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                presetProviders.onboardingPresets.forEach { preset ->
                    LanguageCard(
                        label = preset.displayName,
                        selected = selectedPresetId == preset.id,
                        onClick = { onPresetSelected(preset.id) },
                    )
                    Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                }
            }
            // 底部渐隐遮罩：不拦截触摸，仅做视觉提示。
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background.copy(alpha = 0f),
                                MaterialTheme.colorScheme.background,
                            ),
                        ),
                    ),
            )
        }

        // 自定义供应商：baseUrl 输入
        androidx.compose.animation.AnimatedVisibility(
            visible = isCustomSelected,
            enter = MuseMotion.expandFadeEnter(),
            exit = MuseMotion.expandFadeExit(),
        ) {
            Column {
                Text(
                    text = stringResource(R.string.onboarding_provider_custom_baseurl_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                MuseTextField(
                    value = customBaseUrl,
                    onValueChange = onCustomBaseUrlChange,
                    label = { Text(stringResource(R.string.onboarding_provider_custom_baseurl_input)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
            }
        }

        // API Key 输入
        Text(
            text = stringResource(R.string.onboarding_provider_apikey_label),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        MuseTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            label = { Text(stringResource(R.string.onboarding_provider_apikey_input)) },
            singleLine = true,
            visualTransformation = if (apiKeyVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            trailingIcon = {
                MuseTactileButton(
                    icon = if (apiKeyVisible) {
                        MuseIcons.eyeOff
                    } else {
                        MuseIcons.eye
                    },
                    onClick = onToggleApiKeyVisible,
                    contentDescription = stringResource(
                        if (apiKeyVisible) {
                            R.string.onboarding_provider_apikey_hide
                        } else {
                            R.string.onboarding_provider_apikey_show
                        },
                    ),
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(MusePaddings.itemGap))

        // 测试连接按钮（自定义供应商还需 baseUrl）
        // v1.0.18: allowMissingApiKey 供应商(如 SiliconFlow 免费)允许不填 key 测试连接
        val canTest = selectedPresetId.isNotBlank() &&
            (apiKey.isNotBlank() || selectedPreset?.allowMissingApiKey == true) &&
            (!isCustomSelected || customBaseUrl.isNotBlank())
        val testEnabled = canTest && testStatus !is TestStatus.Loading
        Surface(
            onClick = onTestConnection,
            enabled = testEnabled,
            shape = MuseShapes.pill,
            color = if (testEnabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                if (testStatus is TestStatus.Loading) {
                    MuseSpinner(
                        size = 20.dp,
                        color = MaterialTheme.colorScheme.surface,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.onboarding_provider_test_button),
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = if (testEnabled) {
                            MaterialTheme.colorScheme.surface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        },
                    )
                }
            }
        }

        // 测试结果展示
        when (testStatus) {
            is TestStatus.Success -> {
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MuseIcons.circleCheck,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.width(MusePaddings.iconPadding))
                    Text(
                        text = stringResource(R.string.onboarding_provider_test_success, testStatus.models.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // v2.x: 能力匹配摘要 — 证明"拉取到的清单已经带上能力信息"(视觉/推理/工具)
                val visionCount = testStatus.models.count { it.supportsVisionInput() }
                val reasoningCount = testStatus.models.count { it.supportsReasoning() }
                val toolCount = testStatus.models.count { it.supportsToolCalling() }
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                Text(
                    text = stringResource(R.string.onboarding_provider_caps, visionCount, reasoningCount, toolCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is TestStatus.Error -> {
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = MuseIcons.alertCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(MusePaddings.iconPadding))
                    Text(
                        text = testStatus.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            else -> {}
        }

        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        // 跳过配置
        MuseCapsuleButton(
            text = stringResource(R.string.onboarding_provider_skip),
            onClick = onSkip,
            variant = IosCapsuleButtonVariant.Text,
            fillWidth = false,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

// ── 步骤 4：选择模型 ──────────────────────────────────────────
@Composable
private fun StepSelectModel(
    models: List<Model>,
    selectedModelId: String,
    onSelectModel: (String) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    providerSkipped: Boolean,
) {
    if (providerSkipped || models.isEmpty()) {
        // 步骤 3 被跳过或无模型时，提示用户
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = MusePaddings.screen),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.onboarding_model_empty_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
            Text(
                text = stringResource(R.string.onboarding_model_empty_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val filteredModels = remember(searchQuery, models) {
        if (searchQuery.isBlank()) {
            models
        } else {
            models.filter { model ->
                model.id.contains(searchQuery, ignoreCase = true) ||
                    model.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = MusePaddings.screen),
    ) {
        Text(
            text = stringResource(R.string.onboarding_model_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        MuseTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            label = { Text(stringResource(R.string.onboarding_model_search_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            items(filteredModels, key = { "${it.providerId}:${it.id}" }) { model ->
                ModelItem(
                    model = model,
                    selected = selectedModelId == model.id,
                    onClick = { onSelectModel(model.id) },
                )
            }
        }
    }
}

@Composable
private fun ModelItem(model: Model, selected: Boolean, onClick: () -> Unit) {
    val containerColor = MaterialTheme.colorScheme.surfaceContainer
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }
    val borderWidth = if (selected) 1.5.dp else 1.dp
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MuseShapes.medium,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = model.name,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                Text(
                    text = model.id,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                // v2.x: 能力徽章 — 与供应商编辑页同口径(复用同一组件)
                ModelAbilityChips(model = model)
            }
            if (selected) {
                Icon(
                    imageVector = MuseIcons.circleCheck,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

// ── 步骤 5：完成页 ──────────────────────────────────────────────
@Composable
private fun StepComplete() {
    val tutorials = listOf(
        FeatureItem(
            MuseIcons.chat,
            stringResource(R.string.onboarding_tutorial_chat_title),
            stringResource(R.string.onboarding_tutorial_chat_desc),
        ),
        FeatureItem(
            MuseIcons.user,
            stringResource(R.string.onboarding_tutorial_assistant_title),
            stringResource(R.string.onboarding_tutorial_assistant_desc),
        ),
        FeatureItem(
            MuseIcons.wrench,
            stringResource(R.string.onboarding_tutorial_tools_title),
            stringResource(R.string.onboarding_tutorial_tools_desc),
        ),
        FeatureItem(
            MuseIcons.cloudUpload,
            stringResource(R.string.onboarding_tutorial_backup_title),
            stringResource(R.string.onboarding_tutorial_backup_desc),
        ),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 完成图标:使用默认助手头像
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(80.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.default_assistant_avatar),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        Text(
            text = stringResource(R.string.onboarding_complete_title),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        Text(
            text = stringResource(R.string.onboarding_complete_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        tutorials.forEach { item ->
            TutorialCard(item = item)
            Spacer(modifier = Modifier.height(MusePaddings.itemGap))
        }
    }
}

@Composable
private fun TutorialCard(item: FeatureItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(44.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(MusePaddings.cardInner.calculateTopPadding()))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MusePaddings.tightGap))
                Text(
                    text = item.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 胶囊按钮 ──────────────────────────────────────────

@Composable
private fun PrimaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MuseShapes.pill,
        color = if (enabled) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
        },
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (enabled) {
                        MaterialTheme.colorScheme.surface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
                Spacer(modifier = Modifier.width(MusePaddings.tightGap))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = if (enabled) {
                    MaterialTheme.colorScheme.surface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}

@Composable
private fun SecondaryPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Surface(
        onClick = onClick,
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            // 原先 0.2 alpha 的描边几乎看不见，按钮轮廓虚化；
            // 主按钮是实心黑底，两者对比过于极端。加深到 0.45 让次要操作同样可辨。
            width = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        ),
        modifier = modifier.heightIn(min = 52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.width(MusePaddings.tightGap))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── 底部按钮 ──────────────────────────────────────────────────

@Composable
private fun BottomButtons(currentPage: Int, canGoNext: Boolean, onPrevious: () -> Unit, onNext: () -> Unit, onComplete: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        when (currentPage) {
            // 步骤 0：只有"开始设置"按钮（居中）
            0 -> {
                PrimaryPillButton(
                    text = stringResource(R.string.onboarding_button_start),
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                )
            }
            // 步骤 7：只有"开始使用"按钮（居中）
            7 -> {
                // v2.0 修复: Surface 内部为 Box,两个按钮直接并列会叠画导致文字重叠 —
                // 改为垂直排列(主按钮在上、游客入口在下)。
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PrimaryPillButton(
                        text = stringResource(R.string.onboarding_button_finish),
                        onClick = onComplete,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen)
                            .padding(top = MusePaddings.screen),
                    )
                }
            }
            // 步骤 1-6：左边"上一步"，右边"下一步"
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    SecondaryPillButton(
                        text = stringResource(R.string.onboarding_button_previous),
                        icon = MuseIcons.arrowLeft,
                        onClick = onPrevious,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryPillButton(
                        text = stringResource(R.string.onboarding_button_next),
                        icon = MuseIcons.arrowRight,
                        onClick = onNext,
                        enabled = canGoNext,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ── 步骤数据保存 ──────────────────────────────────────────────

/**
 * 翻页前保存当前步骤的数据到持久化存储。
 *
 * 各步骤对应的保存逻辑：
 *  - 步骤 2：保存用户名（mockLogin）+ 更新默认助手名字
 *  - 步骤 4：创建并保存 ProviderConfig + 设置激活供应商
 *  - 步骤 5：保存选中的模型 id
 *
 * 语言(步骤 1)/记忆开关(步骤 3)/主题(步骤 6)在用户操作时即时保存，此处不重复。
 */
private suspend fun saveStepData(
    currentPage: Int,
    settings: SettingsRepository,
    assistantRepo: AssistantRepository,
    userName: String,
    agentName: String,
    selectedPreset: ProviderConfig?,
    apiKey: String,
    fetchedModels: List<Model>,
    selectedModelId: String,
    providerSkipped: Boolean,
) {
    when (currentPage) {
        2 -> {
            // 保存用户名
            settings.mockLogin(userName)
            // 更新默认助手名字
            val defaultAssistant = assistantRepo.getById("default")
            if (defaultAssistant != null && agentName.isNotBlank()) {
                assistantRepo.upsert(defaultAssistant.copy(name = agentName))
            }
            // v1.0.27 修复: 引导页填写的称呼同步到 UserProfile,让个人资料页能看到且注入 system prompt
            // 旧实现只写到 account_user_name + Assistant 表,UserProfileEditPage 读的是 user_profile_json,三套独立存储无同步
            val profile = settings.getUserProfile()
            settings.saveUserProfile(
                profile.copy(
                    userNickName = userName.ifBlank { profile.userNickName },
                    assistantName = agentName.ifBlank { profile.assistantName },
                ),
            )
        }
        4 -> {
            // 创建并保存供应商配置（仅当测试成功且未跳过时）
            // v1.0.18: allowMissingApiKey 供应商(如 SiliconFlow 免费)允许不填 key 保存
            // v2.0: 改用 upsert 幂等保存 — 测试成功时已即时落库,此处为翻页兑底,避免重复条目
            if (!providerSkipped && selectedPreset != null &&
                (apiKey.isNotBlank() || selectedPreset.allowMissingApiKey)
            ) {
                val config = selectedPreset.copy(
                    apiKey = apiKey,
                    models = fetchedModels,
                )
                settings.upsertProvider(config)
                settings.setActiveProvider(config.id)
            }
        }
        5 -> {
            // 保存选中的模型
            settings.saveSelectedModel(selectedModelId.ifBlank { null })
        }
    }
}
