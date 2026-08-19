package io.zer0.muse.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.zer0.muse.ui.common.feedback.MuseToast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import io.zer0.muse.ui.common.navigation.MuseTopBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import androidx.core.content.pm.PackageInfoCompat
import compose.icons.TablerIcons
import compose.icons.tablericons.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.muse.BuildConfig
import io.zer0.muse.UpdateChecker
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.settings.ChevronRight
import io.zer0.muse.ui.common.feedback.MuseAlertDialog
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.common.settings.SettingsSwitchRow
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.theme.MusePaddings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import androidx.compose.runtime.mutableStateMapOf
import io.zer0.ai.ProviderRegistry
import io.zer0.common.Result
import io.zer0.common.resultOf

/**
 * v0.34: 通用二级设置页容器 — iOS 风格 Large Title 顶部栏。
 *
 * 三段式导航(左返回 + 中大标题 + 右空)。用 LazyColumn(支持 LazyListScope 扩展如
 * providerListSection)。
 *
 * P1-4 平板适配:[onBack] 改为可空(双列模式下右栏无需返回按钮);
 * Expanded 模式下 LazyColumn 居中限宽 720dp,避免大屏上列表项过度拉伸。
 */
@Composable
fun SettingsSubPageScaffold(
    title: String,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    // P1-4: 读取窗口宽度分级,Expanded 模式下居中限宽
    val widthClass = rememberWindowWidthClass()
    Scaffold(
        topBar = {
            MuseTopBar(
                title = title,
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        // P1-4: Box 包裹 LazyColumn,Expanded 模式下用 contentAlignment 居中限宽后的列表
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()  // v1.48: 键盘弹出时收缩列表,避免底部表单被遮挡(影响 16 个复用页)
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        // P1-4: Expanded 模式下 LazyColumn 最大宽度 720dp,Box 的 TopCenter 居中
                        if (widthClass == WindowWidthClass.Expanded) {
                            Modifier.widthIn(max = 720.dp)
                        } else {
                            Modifier
                        }
                    )
                    .padding(horizontal = MusePaddings.screen),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
                ),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.sectionGap),
                content = content,
            )
        }
    }
}

/**
 * 二级页:模型与服务(供应商列表)
 *
 * v1.133: 拆分重构 — 只保留供应商列表。其他原内嵌 section 已拆为独立二级页:
 *  - [SettingsWebSearchPage] 联网搜索
 *  - [SettingsAsrPage] 语音识别 ASR
 *  - [SettingsImageGenPage] 图像生成
 *  - [SettingsMcpPage] MCP 服务器
 *  - [SettingsAssistantResourcesPage] 助手资源(收藏夹/世界书/快捷消息/模式注入/Skills/记忆开关)
 *  用户画像入口已在顶级"通用"分组,无需在此重复。
 */
@Composable
fun SettingsModelPage(
    onBack: () -> Unit,
    /** v1.x: 语音识别引擎设置入口(供应商页 → ASR 页)。 */
    onOpenAsr: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    // v1.48: h13 initialValue 用 null,首次进入显示加载态而非闪空状态
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = null)
    val visibleProviders = providers?.filterNot { it.hiddenFromSettings }
    val activeProviderId by settings.activeProviderIdFlow.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var editingConfig by remember { mutableStateOf<io.zer0.ai.core.ProviderConfig?>(null) }
    var isNewProvider by remember { mutableStateOf(false) }
    var isFromPreset by remember { mutableStateOf(false) }
    // v1.38: 预设供应商选择全屏页面显示状态(原 ModalBottomSheet 改为全屏页面)
    var showPresetPicker by remember { mutableStateOf(false) }
    // v1.97: 扫描二维码导入 Provider
    var showQrScanDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // v1.133: 供应商批量健康检测状态(列表头部"全部检测"按钮触发)
    //  - testStatuses: 按 provider.id 索引的单测状态(Testing / Success(latency) / Failed(error))
    //  - isTestingAllProviders: 全局检测进行中(用于禁用按钮 + 显示头部转圈)
    // 用 mutableStateMapOf 而非 mutableStateOf<Map> 是为了让并发 async 各自更新自己的 key,
    // 避免读-改-写竞态丢失(每条 async 完成后独立 set 自己的 key,SnapshotStateMap 保证原子)。
    val providerTestStatuses = remember { mutableStateMapOf<String, ProviderTestStatus>() }
    var isTestingAllProviders by remember { mutableStateOf(false) }

    // v1.133: "全部检测" — 并发测试所有已配置供应商,各 provider 独立显示 loading/结果
    //  - 复用 ProviderEditPage.testConnection 的轻量测试逻辑(只调 /models 端点,不发真实 chat)
    //  - 协程并发:async + awaitAll,每个 async 独立更新自己的状态(支持快慢 provider 异步完成)
    //  - 错误分级复用 settings_provider_test_error_* 字符串,保持与单测一致
    val onTestAllProviders: () -> Unit = {
        val list = visibleProviders
        when {
            // 正在检测中或列表未加载完成,忽略重复触发
            isTestingAllProviders || list == null -> Unit
            list.isEmpty() -> {
                io.zer0.muse.ui.common.feedback.MuseToast.show(
                    context.getString(R.string.settings_provider_test_all_no_providers),
                )
            }
            else -> {
                scope.launch {
                    isTestingAllProviders = true
                    // 重置:所有 provider 标记为 Testing
                    providerTestStatuses.clear()
                    list.forEach { providerTestStatuses[it.id] = ProviderTestStatus.Testing }
                    // 并发测试:每个 provider 一个 async,完成后独立更新状态
                    coroutineScope {
                        list.map { config ->
                            async(Dispatchers.IO) {
                                val status: ProviderTestStatus = if (config.apiKey.isBlank()) {
                                    // 无 API Key 直接判失败,不发网络请求
                                    ProviderTestStatus.Failed(
                                        context.getString(R.string.settings_provider_test_skip_no_key),
                                    )
                                } else {
                                    val start = System.currentTimeMillis()
                                    val result = resultOf {
                                        ProviderRegistry.create(config).listModels(config)
                                    }
                                    val latency = System.currentTimeMillis() - start
                                    when (result) {
                                        is Result.Success -> ProviderTestStatus.Success(latency, result.data.size)
                                        is Result.Error -> {
                                            val msg = result.throwable?.message.orEmpty()
                                            val classified = when {
                                                msg.contains("401") || msg.contains("403") ->
                                                    context.getString(R.string.settings_provider_test_error_auth)
                                                msg.contains("404") ->
                                                    context.getString(R.string.settings_provider_test_error_404)
                                                msg.contains("Unable to resolve", ignoreCase = true) ||
                                                    msg.contains("UnknownHost", ignoreCase = true) ->
                                                    context.getString(R.string.settings_provider_test_error_unknown_host)
                                                msg.contains("timeout", ignoreCase = true) ->
                                                    context.getString(R.string.settings_provider_test_error_timeout)
                                                else -> context.getString(
                                                    R.string.settings_provider_test_error_failed,
                                                    msg.take(80),
                                                )
                                            }
                                            ProviderTestStatus.Failed(classified)
                                        }
                                    }
                                }
                                // 单条完成 — 独立更新自己的 key(原子,无竞态)
                                providerTestStatuses[config.id] = status
                            }
                        }.awaitAll()
                    }
                    isTestingAllProviders = false
                    // 汇总:成功 / 失败计数,Toast 通知用户最终结果
                    val successCount = providerTestStatuses.values.count { it is ProviderTestStatus.Success }
                    val failedCount = providerTestStatuses.values.count { it is ProviderTestStatus.Failed }
                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                        context.getString(R.string.settings_provider_test_all_done, successCount, failedCount),
                    )
                }
            }
        }
    }

    // v1.97: 扫描二维码弹窗
    if (showQrScanDialog) {
        io.zer0.muse.ui.qrcode.QrCodeScanDialog(
            onDismiss = { showQrScanDialog = false },
            onResult = { content ->
                showQrScanDialog = false
                val provider = io.zer0.muse.ui.qrcode.QrCodeGenerator.parseProviderQr(content)
                if (provider != null) {
                    // 生成新 id 避免与原配置冲突,把 models 的 providerId 也一并更新
                    val newId = "provider-" + System.currentTimeMillis()
                    val imported = provider.copy(
                        id = newId,
                        builtIn = false,
                        models = provider.models.map { it.copy(providerId = newId) },
                    )
                    scope.launch {
                        settings.addProvider(imported)
                        io.zer0.muse.ui.common.feedback.MuseToast.show(
                            context.getString(R.string.qr_import_success, imported.displayName),
                        )
                    }
                } else {
                    io.zer0.muse.ui.common.feedback.MuseToast.show(
                        context.getString(R.string.qr_import_invalid),
                    )
                }
            },
        )
    }

    // v1.35: 编辑供应商时直接渲染全屏页面(不用 Dialog,避免系统导航栏遮挡底部按钮)
    editingConfig?.let { config ->
        ProviderEditPage(
            config = config,
            isNew = isNewProvider,
            isFromPreset = isFromPreset,
            onDismiss = { editingConfig = null },
            onSave = { saved ->
                scope.launch {
                    if (isNewProvider) settings.addProvider(saved)
                    else settings.updateProvider(saved)
                    editingConfig = null
                }
            },
            onDelete = {
                scope.launch {
                    settings.deleteProvider(config.id)
                    editingConfig = null
                }
            },
        )
        return
    }

    // v1.38: 预设供应商选择全屏页面(替代 ModalBottomSheet,根治关闭后卡死)
    if (showPresetPicker) {
        PresetProviderPickerDialog(
            onDismiss = { showPresetPicker = false },
            onPickPreset = { preset ->
                val newId = "provider-" + System.currentTimeMillis()
                val newConfig = preset.copy(
                    id = newId,
                    builtIn = false,
                    apiKey = "",
                    models = preset.models.map { it.copy(providerId = newId) },
                )
                editingConfig = newConfig
                isNewProvider = true
                isFromPreset = true
                showPresetPicker = false
            },
            onPickCustom = {
                editingConfig = io.zer0.ai.core.ProviderConfig(
                    id = "provider-" + System.currentTimeMillis(),
                    displayName = "",
                    type = io.zer0.ai.core.ProviderType.OPENAI,
                    category = io.zer0.ai.core.ProviderCategory.CUSTOM,
                )
                isNewProvider = true
                isFromPreset = false
                showPresetPicker = false
            },
        )
        return
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_sub_model_services), onBack = onBack) {
        val providersList = visibleProviders
        if (providersList == null) {
            // v1.48: h13 首次加载显示居中加载指示器,避免 providers 列表闪空状态
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator()
                }
            }
        } else {
            // Provider 列表(同包 internal LazyListScope 扩展,直接调用)
            providerListSection(
                providers = providersList,
                activeProviderId = activeProviderId,
                onActivate = { id -> scope.launch { settings.setActiveProvider(id) } },
                onEdit = { config ->
                    editingConfig = config
                    isNewProvider = false
                    isFromPreset = false
                },
                onDelete = { id -> scope.launch { settings.deleteProvider(id) } },
                onAddProvider = { showPresetPicker = true },
                // v1.97: 扫描二维码导入 Provider
                onScanQr = { showQrScanDialog = true },
                // v1.133: 批量健康检测(列表头部"全部检测"按钮)
                testStatuses = providerTestStatuses,
                isTestingAll = isTestingAllProviders,
                onTestAll = onTestAllProviders,
            )
        }

        // v1.x: 媒体生成模型配置 — 从设置二级页移入供应商页
        item { SectionLabel(stringResource(R.string.settings_provider_media_gen_section)) }
        item { ImageGenSection(settings = settings, scope = scope) }
        item { VideoGenSection(settings = settings, scope = scope) }
        // v1.x: 语音识别引擎(供应商模型体系外的独立引擎,入口收拢到供应商页)
        item {
            SettingsItemRow(
                icon = TablerIcons.Microphone,
                title = stringResource(R.string.settings_asr_entry_title),
                subtitle = stringResource(R.string.settings_asr_entry_subtitle),
                onClick = onOpenAsr,
            )
        }
    }
}

/**
 * v1.133: 二级页 — 联网搜索(从 SettingsModelPage 拆出)。
 *
 * 仅渲染 [WebSearchSection],让"模型与服务"页只保留供应商列表。
 */
@Composable
fun SettingsWebSearchPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val webSearchConfig by settings.webSearchConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.web.WebSearchConfig()
    )
    SettingsSubPageScaffold(title = stringResource(R.string.settings_web_search_section), onBack = onBack) {
        item { WebSearchSection(webSearchConfig = webSearchConfig, settings = settings) }
    }
}

/**
 * v1.133: 二级页 — 助手资源(从 SettingsModelPage 拆出)。
 *
 * 容纳 [AssistantEntriesSection]:助手管理 / 收藏夹 / 世界书 / 快捷消息 / 模式注入 / Skills / 长期记忆开关。
 * 让"模型与服务"页只保留供应商列表。
 */
@Composable
fun SettingsAssistantResourcesPage(
    onBack: () -> Unit,
    onOpenAssistants: () -> Unit = {},
    onOpenFavorites: () -> Unit = {},
    onOpenLorebooks: () -> Unit = {},
    onOpenWorldbook: () -> Unit = {},
    onOpenQuickMessages: () -> Unit = {},
    onOpenPromptInjections: () -> Unit = {},
    onOpenSkills: () -> Unit = {},
) {
    val settings: SettingsRepository = koinInject()
    val memoryEnabled by settings.memoryEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val scope = rememberCoroutineScope()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_assistant_resources_title), onBack = onBack) {
        item {
            AssistantEntriesSection(
                onOpenAssistants = onOpenAssistants,
                onOpenFavorites = onOpenFavorites,
                onOpenLorebooks = onOpenLorebooks,
                onOpenWorldbook = onOpenWorldbook,
                onOpenQuickMessages = onOpenQuickMessages,
                onOpenPromptInjections = onOpenPromptInjections,
                onOpenSkills = onOpenSkills,
                memoryEnabled = memoryEnabled,
                onMemoryEnabledChange = { enabled ->
                    scope.launch { settings.saveMemoryEnabled(enabled) }
                },
            )
        }
    }
}

/**
 * v1.133: 二级页 — 语音识别 ASR(从 SettingsModelPage 拆出)。
 */
@Composable
fun SettingsAsrPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val asrConfig by settings.asrConfigFlow.collectAsStateWithLifecycle(
        initialValue = io.zer0.muse.asr.AsrConfig()
    )
    SettingsSubPageScaffold(title = stringResource(R.string.section_asr), onBack = onBack) {
        item { AsrSection(asrConfig = asrConfig, settings = settings) }
    }
}

/**
 * v1.133: 二级页 — 图像生成(从 SettingsModelPage 拆出)。
 */
@Composable
fun SettingsImageGenPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_image_gen_section), onBack = onBack) {
        item { ImageGenSection(settings = settings, scope = scope) }
    }
}

/**
 * 二级页 — 视频生成(默认供应商/模型/时长/分辨率配置)。
 */
@Composable
fun SettingsVideoGenPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    SettingsSubPageScaffold(title = stringResource(R.string.settings_video_gen_section), onBack = onBack) {
        item { VideoGenSection(settings = settings, scope = scope) }
    }
}

/**
 * v1.133: 二级页 — MCP 服务器(从 SettingsModelPage 拆出)。
 */
@Composable
fun SettingsMcpPage(
    onBack: () -> Unit,
) {
    SettingsSubPageScaffold(title = stringResource(R.string.section_mcp), onBack = onBack) {
        item { McpSection() }
    }
}

/**
 * 二级页:数据与备份(Backup + WebServer)
 *
 * v1.132: 原本同时内嵌 [BackupSection] 与跳转 [CloudBackupPage] 的独立入口,二者功能重复,
 *         v1.133 起只保留 [BackupSection] 内嵌完整云备份配置,删除重复入口。
 */
@Composable
fun SettingsDataPage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val sessionRepository: io.zer0.muse.data.session.SessionRepository = koinInject()
    val backupService: io.zer0.muse.backup.BackupService = koinInject()
    val sessionCount by sessionRepository.observeSessionCount().collectAsStateWithLifecycle(initialValue = 0)
    val messageCount by sessionRepository.observeMessageCount().collectAsStateWithLifecycle(initialValue = 0)

    SettingsSubPageScaffold(title = stringResource(R.string.settings_sub_data_backup), onBack = onBack) {
        item {
            BackupSection(
                sessionCount = sessionCount,
                messageCount = messageCount,
                backupService = backupService,
                settings = settings,
            )
        }
        item { WebServerSection(settings = settings) }
    }
}

/**
 * 二级页:外观(主题 + 字号)
 */
@Composable
fun SettingsAppearancePage(
    onBack: () -> Unit,
) {
    val settings: SettingsRepository = koinInject()
    val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
    val fontSizeScale by settings.fontSizeScaleFlow.collectAsStateWithLifecycle(initialValue = "medium")
    val language by settings.languageFlow.collectAsStateWithLifecycle(initialValue = "system")

    SettingsSubPageScaffold(title = stringResource(R.string.settings_theme_appearance), onBack = onBack) {
        item {
            ThemeSection(
                themeMode = themeMode,
                fontSizeScale = fontSizeScale,
                settings = settings,
            )
        }
        item {
            LanguageSection(
                language = language,
                settings = settings,
            )
        }
        // v1.95: 启动默认页(任务 / Agent / 群聊)
        item {
            DefaultHomePageSection(
                settings = settings,
            )
        }
    }
}

/**
 * 二级页:关于
 * AboutSection 原为 private,此处内联重写(版本号 + 开源许可入口)。
 *
 * P3-14:新增"反馈"入口 — 调起邮件 Intent(预填收件地址/主题/设备信息),无邮件应用时
 *       fallback 到复制反馈内容到剪贴板 + Toast 提示。
 * P3-15:新增"检查更新"按钮 — 异步请求 GitHub Releases API,与本地 versionName 比较,
 *       有新版本用 MuseDialog 提示(确认后浏览器打开 release 页),最新用 MuseAlertDialog 提示,
 *       网络错误用 Toast 提示。
 */
@Composable
fun SettingsAboutPage(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit = {},
) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val updateCheckEnabled by settings.updateCheckEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    // v1.4: 登录态 — 仅当已登录或游客时显示"退出登录"
    val accountState by settings.accountStateFlow.collectAsStateWithLifecycle(initialValue = io.zer0.muse.data.AccountState())
    var showLogoutDialog by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    val versionName = remember {
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)
                ).versionName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            }
        }.getOrNull() ?: "0.26"
    }
    val versionCode = remember {
        runCatching {
            val packageInfo = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            // 用 PackageInfoCompat.getLongVersionCode 替代 PackageInfo.longVersionCode
            // (longVersionCode 字段需 API 28,但 minSdk=26;PackageInfoCompat 内部做版本兼容)
            PackageInfoCompat.getLongVersionCode(packageInfo)
        }.getOrNull() ?: 26L
    }

    val updateChecker = remember { UpdateChecker() }
    // P3-15:更新检查状态
    var checking by remember { mutableStateOf(false) }
    var newVersion by remember { mutableStateOf<UpdateChecker.Result.NewVersion?>(null) }
    var upToDate by remember { mutableStateOf(false) }

    SettingsSubPageScaffold(title = stringResource(R.string.section_about), onBack = onBack) {
        // 应用信息
        item {
            SectionLabel(stringResource(R.string.settings_about_app_info))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(MusePaddings.cardInner),
                ) {
                    Text(
                        text = "muse",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.settings_about_version_full, versionName, versionCode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        // v1.66: 改为动态 BuildConfig 值,不再硬编码 "debug"
                        text = "Build: ${if (BuildConfig.DEBUG) "debug" else "release"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        }
        // P3-14: 反馈入口 — 调起邮件 Intent,fallback 到剪贴板
        item {
            SectionLabel(stringResource(R.string.settings_about_feedback_section))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_feedback),
                    onClick = { sendFeedback(context, versionName) },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_join_qq_group),
                    subtitle = stringResource(R.string.settings_about_join_qq_group_subtitle),
                    onClick = { openQQGroup(context) },
                ) {
                    ChevronRight()
                }
            }
        }
        // P3-15: 检查更新入口 — 异步请求 GitHub Releases
        item {
            SectionLabel(stringResource(R.string.settings_about_update_section))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_check_update),
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            val result = updateChecker.check(versionName)
                            checking = false
                            when (result) {
                                is UpdateChecker.Result.NewVersion -> newVersion = result
                                is UpdateChecker.Result.UpToDate -> upToDate = true
                                is UpdateChecker.Result.Error -> MuseToast.show(context.getString(R.string.settings_about_update_check_failed, result.message))
                            }
                        }
                    },
                ) {
                    if (checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        ChevronRight()
                    }
                }
                SettingsGroupDivider()
                SettingsSwitchRow(
                    icon = TablerIcons.Refresh,
                    title = stringResource(R.string.settings_about_auto_update),
                    subtitle = stringResource(R.string.settings_about_auto_update_subtitle),
                    checked = updateCheckEnabled,
                    onCheckedChange = { v -> scope.launch { settings.saveUpdateCheckEnabled(v) } },
                )
            }
        }
        item {
            SectionLabel(stringResource(R.string.settings_about_legal_section))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    title = stringResource(R.string.about_oss_licenses),
                    onClick = onOpenLicenses,
                ) {
                    ChevronRight()
                }
            }
        }
        // v1.119: 开源仓库分组 — 仓库主页 / Star / 问题反馈(均跳转 GitHub)
        item {
            SectionLabel(stringResource(R.string.settings_about_repo_section))
        }
        item {
            SettingsGroup(
                modifier = Modifier.padding(top = 8.dp),
            ) {
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_repo_home),
                    onClick = { openUrl(context, "https://github.com/5352124/Muse") },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_star),
                    onClick = { openUrl(context, "https://github.com/5352124/Muse") },
                ) {
                    ChevronRight()
                }
                SettingsGroupDivider()
                SettingsItemRow(
                    title = stringResource(R.string.settings_about_issues),
                    onClick = { openUrl(context, "https://github.com/5352124/Muse/issues") },
                ) {
                    ChevronRight()
                }
            }
        }
        // v1.4: 退出登录入口 — 仅当已登录或游客模式时显示
        if (accountState.isAuthed) {
            item {
                SectionLabel(stringResource(R.string.settings_about_account_section))
            }
            item {
                SettingsGroup(
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    SettingsItemRow(
                        title = stringResource(R.string.settings_about_logout),
                        enabled = !loggingOut,
                        onClick = { showLogoutDialog = true },
                    ) {
                        if (loggingOut) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            ChevronRight()
                        }
                    }
                }
            }
        }
    }

    // v1.4: 退出登录确认弹窗 — 确认后调 settings.logout(),由 MainActivity 的 key(accountState.isAuthed) 监听变化自动跳回 AUTH
    if (showLogoutDialog) {
        MuseDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = stringResource(R.string.settings_about_logout),
            content = {
                Text(
                    text = stringResource(R.string.settings_about_logout_confirm),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            },
            confirmText = stringResource(R.string.settings_about_logout_button),
            onConfirm = {
                loggingOut = true
                scope.launch {
                    settings.logout()
                    loggingOut = false
                    showLogoutDialog = false
                }
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { showLogoutDialog = false },
            destructive = true,
        )
    }

    // P3-15: 发现新版本弹窗 — 确认后用浏览器打开 release html_url
    newVersion?.let { version ->
        MuseDialog(
            onDismissRequest = { newVersion = null },
            title = stringResource(R.string.settings_about_new_version_title),
            content = {
                Text(
                    text = stringResource(R.string.settings_about_new_version_desc, version.tagName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            },
            confirmText = stringResource(R.string.settings_about_download),
            onConfirm = {
                if (version.htmlUrl.isNotBlank()) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(version.htmlUrl))
                    runCatching { context.startActivity(browserIntent) }
                }
                newVersion = null
            },
            dismissText = stringResource(R.string.action_cancel),
            onDismiss = { newVersion = null },
        )
    }

    // P3-15: 已是最新版本提示
    if (upToDate) {
        MuseAlertDialog(
            onDismissRequest = { upToDate = false },
            title = stringResource(R.string.settings_about_check_update),
            message = stringResource(R.string.settings_about_up_to_date),
            confirmText = stringResource(R.string.settings_about_got_it),
        )
    }
}

/**
 * v1.119: 打开外部 URL — 用 ACTION_VIEW 启动浏览器,失败时 Toast 提示。
 */
private fun openUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    runCatching { context.startActivity(intent) }.onFailure {
        MuseToast.show(context.getString(R.string.settings_about_open_url_failed))
    }
}

/**
 * 打开 QQ 群卡片;未安装 QQ 时把群号复制到剪贴板并提示。
 */
private fun openQQGroup(context: Context) {
    val uri = Uri.parse(
        "mqqapi://card/show_pslcard?src_type=internal&version=1&uin=905451314" +
            "&card_type=group&source=external&jump_from=webui"
    )
    val intent = Intent(Intent.ACTION_VIEW, uri)
    runCatching { context.startActivity(intent) }.onFailure {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(
            ClipData.newPlainText(
                context.getString(R.string.settings_about_join_qq_group),
                "905451314",
            ),
        )
        MuseToast.show(context.getString(R.string.settings_about_qq_not_installed))
    }
}

/**
 * P3-14: 发送反馈 — 调起邮件 Intent 预填收件地址/主题/设备信息,
 * 无邮件应用时 fallback 到复制反馈内容到剪贴板 + Toast 提示。
 */
private fun sendFeedback(context: Context, versionName: String) {
    val deviceInfo = buildString {
        appendLine(context.getString(R.string.settings_feedback_android_version, android.os.Build.VERSION.RELEASE, android.os.Build.VERSION.SDK_INT))
        appendLine(context.getString(R.string.settings_feedback_device_model, android.os.Build.MANUFACTURER, android.os.Build.MODEL))
        appendLine(context.getString(R.string.settings_feedback_app_version, versionName))
    }
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf("2119280571@qq.com"))
        putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.settings_feedback_subject))
        putExtra(Intent.EXTRA_TEXT, deviceInfo)
    }
    val chooser = Intent.createChooser(intent, context.getString(R.string.settings_feedback_chooser_title))
    runCatching {
        context.startActivity(chooser)
    }.onFailure {
        // 无邮件应用:fallback 到剪贴板
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val feedbackText = buildString {
            appendLine(context.getString(R.string.settings_feedback_recipient))
            appendLine(context.getString(R.string.settings_feedback_subject_label))
            appendLine()
            appendLine(deviceInfo)
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.settings_feedback_subject), feedbackText))
        MuseToast.show(context.getString(R.string.settings_feedback_no_email_app), 3500)
    }
}
