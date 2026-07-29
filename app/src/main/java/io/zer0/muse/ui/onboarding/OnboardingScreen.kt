package io.zer0.muse.ui.onboarding

import android.app.Activity
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.layout.ContentScale
import io.zer0.muse.ui.theme.MuseShapes
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.zer0.muse.ui.common.IosTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import io.zer0.ai.ProviderRegistry
import io.zer0.ai.core.Model
import io.zer0.ai.core.ProviderConfig
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.PresetThemes
import io.zer0.muse.ui.theme.pill
import io.zer0.muse.ui.theme.semiLarge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

// ── 测试连接状态 ──────────────────────────────────────────────
private sealed class TestStatus {
    data object Idle : TestStatus()
    data object Loading : TestStatus()
    data class Success(val models: List<Model>) : TestStatus()
    data class Error(val message: String) : TestStatus()
}

/**
 * 开机引导页面 — 全屏 HorizontalPager，6 步完成初始配置。
 *
 * 数据流：OnboardingScreen 接收 [onComplete] 回调，内部用 koinInject 获取
 * [SettingsRepository] / [AssistantRepository] / [PresetProviders]，
 * 每步完成后即时保存到 SettingsRepository。
 *
 * 设计令牌：使用 [MuseShapes]（iOS 风格圆角）与 [MusePaddings]（间距令牌），
 * 全程 Material Icons（无 emoji），Material 3 组件。
 */
@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val settings: SettingsRepository = koinInject()
    val assistantRepo: AssistantRepository = koinInject()
    val presetProviders: PresetProviders = koinInject()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { 6 })
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

    val pageCount = 6
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
                1 -> StepLanguageTheme(
                    selectedLanguage = selectedLanguage,
                    onLanguageSelected = { lang ->
                        selectedLanguage = lang
                        scope.launch {
                            settings.saveLanguage(lang)
                            (context as? Activity)?.recreate()
                        }
                    },
                    selectedThemeId = selectedThemeId,
                    onThemeSelected = { id ->
                        selectedThemeId = id
                        scope.launch { settings.saveThemeId(id) }
                    },
                )
                2 -> StepNames(
                    userName = userName,
                    onUserNameChange = { userName = it },
                    agentName = agentName,
                    onAgentNameChange = { agentName = it },
                )
                3 -> StepProviderConfig(
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
                            val result = withContext(Dispatchers.IO) {
                                resultOf {
                                    ProviderRegistry.create(config).listModels(config)
                                }
                            }
                            result.onSuccess { models ->
                                fetchedModels = models
                                testStatus = TestStatus.Success(models)
                                if (models.isNotEmpty()) {
                                    selectedModelId = models.first().id
                                }
                            }.onError { msg, t ->
                                Logger.w("Onboarding", "测试连接失败: ${t?.message ?: msg}", t)
                                testStatus = TestStatus.Error(t?.message ?: "连接失败")
                            }
                        }
                    },
                    onSkip = {
                        providerSkipped = true
                        scope.launch { pagerState.animateScrollToPage(5) }
                    },
                )
                4 -> StepSelectModel(
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
                5 -> StepComplete()
            }
        }

        // 底部按钮区域
        BottomButtons(
            currentPage = currentPage,
            // 步骤 3 需测试成功才能下一步
            canGoNext = when (currentPage) {
                3 -> testStatus is TestStatus.Success
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
                        // 步骤 3 → 若跳过则直接到步骤 5
                        currentPage == 3 && providerSkipped -> 5
                        // 步骤 4 → 若无供应商则直接到步骤 5
                        currentPage == 4 && providerSkipped -> 5
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
private fun ProgressDots(
    currentPage: Int,
    pageCount: Int,
    modifier: Modifier = Modifier,
) {
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
    }
}

// ── 步骤 0：欢迎页 ──────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepWelcome() {
    val features = listOf(
        FeatureItem(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.onboarding_feature_chat_title), stringResource(R.string.onboarding_feature_chat_desc)),
        FeatureItem(Icons.Outlined.Psychology, stringResource(R.string.onboarding_feature_memory_title), stringResource(R.string.onboarding_feature_memory_desc)),
        FeatureItem(Icons.Outlined.Build, stringResource(R.string.onboarding_feature_tools_title), stringResource(R.string.onboarding_feature_tools_desc)),
        FeatureItem(Icons.AutoMirrored.Outlined.LibraryBooks, stringResource(R.string.onboarding_feature_knowledge_title), stringResource(R.string.onboarding_feature_knowledge_desc)),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MusePaddings.screen),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // 品牌图标:使用项目图标
        Surface(
            shape = MuseShapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.size(96.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.ic_muse_logo),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            )
        }
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.tightGap))
        Text(
            text = stringResource(R.string.onboarding_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap * 2))
        // 特性标签:带图标的胶囊,比纯文字更有层次
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
        ) {
            features.forEach { item ->
                FeatureChip(item = item)
            }
        }
    }
}

private data class FeatureItem(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val description: String,
)

@Composable
private fun FeatureChip(
    item: FeatureItem,
) {
    Surface(
        shape = MuseShapes.pill,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

// ── 步骤 1：语言与外观 ──────────────────────────────────────────
@Composable
private fun StepLanguageTheme(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    selectedThemeId: String,
    onThemeSelected: (String) -> Unit,
) {
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
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        languages.forEach { (code, label) ->
            LanguageCard(
                label = label,
                selected = selectedLanguage == code,
                onClick = { onLanguageSelected(code) },
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }

        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        Text(
            text = stringResource(R.string.onboarding_theme_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.contentGap))
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
private fun LanguageCard(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
                    imageVector = Icons.Filled.CheckCircle,
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
            .height(72.dp)
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
                color = if (selected) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ── 步骤 2：你的名字 ──────────────────────────────────────────
@Composable
private fun StepNames(
    userName: String,
    onUserNameChange: (String) -> Unit,
    agentName: String,
    onAgentNameChange: (String) -> Unit,
) {
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
        Spacer(modifier = Modifier.height(MusePaddings.sectionGap))
        // v1.0.28: label 放到输入框上方,避免 OutlinedTextField 内部 label 的白色背景块
        Text(
            text = stringResource(R.string.onboarding_names_user_label),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(MusePaddings.tightGap))
        IosTextField(
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
        IosTextField(
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

// ── 步骤 3：配置供应商 ──────────────────────────────────────────
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
        presetProviders.onboardingPresets.forEach { preset ->
            LanguageCard(
                label = preset.displayName,
                selected = selectedPresetId == preset.id,
                onClick = { onPresetSelected(preset.id) },
            )
            Spacer(modifier = Modifier.height(MusePaddings.contentGap))
        }

        // 自定义供应商：baseUrl 输入
        androidx.compose.animation.AnimatedVisibility(visible = isCustomSelected) {
            Column {
                Text(
                    text = stringResource(R.string.onboarding_provider_custom_baseurl_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                IosTextField(
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
        IosTextField(
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
                IconButton(onClick = onToggleApiKeyVisible) {
                    Icon(
                        imageVector = if (apiKeyVisible) {
                            Icons.Filled.VisibilityOff
                        } else {
                            Icons.Filled.Visibility
                        },
                        contentDescription = stringResource(
                            if (apiKeyVisible) R.string.onboarding_provider_apikey_hide
                            else R.string.onboarding_provider_apikey_show,
                        ),
                    )
                }
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
                .height(48.dp),
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                if (testStatus is TestStatus.Loading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
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
                        imageVector = Icons.Filled.CheckCircle,
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
            }
            is TestStatus.Error -> {
                Spacer(modifier = Modifier.height(MusePaddings.contentGap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
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
        TextButton(
            onClick = onSkip,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Text(
                text = stringResource(R.string.onboarding_provider_skip),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            )
        }
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
        IosTextField(
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
            items(filteredModels, key = { it.id }) { model ->
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
private fun ModelItem(
    model: Model,
    selected: Boolean,
    onClick: () -> Unit,
) {
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
            }
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
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
        FeatureItem(Icons.AutoMirrored.Outlined.Chat, stringResource(R.string.onboarding_tutorial_chat_title), stringResource(R.string.onboarding_tutorial_chat_desc)),
        FeatureItem(Icons.Outlined.Person, stringResource(R.string.onboarding_tutorial_assistant_title), stringResource(R.string.onboarding_tutorial_assistant_desc)),
        FeatureItem(Icons.Outlined.Build, stringResource(R.string.onboarding_tutorial_tools_title), stringResource(R.string.onboarding_tutorial_tools_desc)),
        FeatureItem(Icons.Outlined.CloudUpload, stringResource(R.string.onboarding_tutorial_backup_title), stringResource(R.string.onboarding_tutorial_backup_desc)),
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

// ── iOS 风格胶囊按钮 ──────────────────────────────────────────

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
        modifier = modifier.height(52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
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
            width = 1.dp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
        ),
        modifier = modifier.height(52.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
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
private fun BottomButtons(
    currentPage: Int,
    canGoNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onComplete: () -> Unit,
) {
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
            // 步骤 5：只有"开始使用"按钮（居中）
            5 -> {
                PrimaryPillButton(
                    text = stringResource(R.string.onboarding_button_finish),
                    onClick = onComplete,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                )
            }
            // 步骤 1-4：左边"上一步"，右边"下一步"
            else -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MusePaddings.screen),
                    horizontalArrangement = Arrangement.spacedBy(MusePaddings.contentGap),
                ) {
                    SecondaryPillButton(
                        text = stringResource(R.string.onboarding_button_previous),
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        onClick = onPrevious,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryPillButton(
                        text = stringResource(R.string.onboarding_button_next),
                        icon = Icons.AutoMirrored.Filled.ArrowForward,
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
 *  - 步骤 3：创建并保存 ProviderConfig + 设置激活供应商
 *  - 步骤 4：保存选中的模型 id
 *
 * 语言（步骤 1）和主题（步骤 1）在用户选择时即时保存，此处不重复。
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
        3 -> {
            // 创建并保存供应商配置（仅当测试成功且未跳过时）
            // v1.0.18: allowMissingApiKey 供应商(如 SiliconFlow 免费)允许不填 key 保存
            if (!providerSkipped && selectedPreset != null &&
                (apiKey.isNotBlank() || selectedPreset.allowMissingApiKey)
            ) {
                val config = selectedPreset.copy(
                    apiKey = apiKey,
                    models = fetchedModels,
                )
                settings.addProvider(config)
                settings.setActiveProvider(config.id)
            }
        }
        4 -> {
            // 保存选中的模型
            settings.saveSelectedModel(selectedModelId.ifBlank { null })
        }
    }
}
