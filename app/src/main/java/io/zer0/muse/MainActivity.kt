@file:Suppress("FunctionNaming", "LongMethod", "CyclomaticComplexMethod")

package io.zer0.muse

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.zer0.common.Logger
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.data.ChatPreferences
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.AppearanceSettingsStore
import io.zer0.muse.intent.ShareIntentHandler
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.MuseRoutes
import io.zer0.muse.ui.onboarding.OnboardingScreen
import io.zer0.muse.ui.SafeModeScreen
import io.zer0.muse.ui.common.media.DesktopShortcuts
import io.zer0.muse.ui.common.feedback.MuseToastHost
import io.zer0.muse.ui.common.media.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.navigation.assistantNavGraph
import io.zer0.muse.ui.navigation.chatNavGraph
import io.zer0.muse.ui.navigation.MuseTransitions
import io.zer0.muse.ui.navigation.settingsNavGraph
import io.zer0.muse.ui.navigation.toolsNavGraph
import io.zer0.muse.ui.quicknotes.QuickCaptureEdgeOverlay
import io.zer0.muse.ui.quicknotes.QuickCaptureOverlayService
import io.zer0.muse.ui.quicknotes.QuickNotesViewModel
import io.zer0.muse.ui.theme.MuseTheme
import io.zer0.muse.ui.theme.MuseMotion
import io.zer0.muse.ui.theme.loadCustomFontFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext
import io.zer0.muse.ui.navigation.SettingsRoute
import io.zer0.muse.ui.navigation.AssistantsRoute
import io.zer0.muse.ui.navigation.HomeRoute
import io.zer0.muse.ui.navigation.ScheduledTasksRoute
import io.zer0.muse.ui.navigation.ScheduledTaskRoute
import io.zer0.muse.ui.navigation.TranslateRoute
import io.zer0.muse.ui.navigation.QuickNotesRoute
import io.zer0.muse.ui.navigation.QuickNoteRoute
import io.zer0.muse.ui.navigation.ChatDetailRoute
import io.zer0.muse.ui.navigation.SettingsDataRoute
import io.zer0.muse.ui.navigation.SettingsCloudBackupRoute
import io.zer0.muse.ui.navigation.SettingsAboutRoute
import io.zer0.muse.ui.navigation.SettingsAgentRoute
import io.zer0.muse.ui.navigation.SettingsModelRoute
import io.zer0.muse.ui.navigation.SettingsMemoryRoute
import io.zer0.muse.ui.navigation.KnowledgeRoute
import io.zer0.muse.ui.navigation.KnowledgeBaseManageRoute

/**
 * 应用唯一 Activity。
 *
 * 职责:
 *  - 安装 SplashScreen(warm-paper 背景 + muse logo)
 *  - 申请运行时权限(POST_NOTIFICATIONS / 录音 / 存储)
 *  - 注册 NavHost,装配所有页面路由(见 [io.zer0.muse.ui.MuseRoutes])
 *  - 处理分享/Deep Link Intent(把外部文本/图片投递到 ChatViewModel)
 *  - 监听主题/字号变更,应用到 MuseTheme
 *
 * 页面过渡动画:首页 Tab 之间用 fade,详情页用右滑入/左滑出(对标 iOS push)。
 */

class MainActivity : ComponentActivity() {

    /** Phase 8.10: 分享/Deep Link 处理器(SAF 解析需 Context)。 */
    private val shareIntentHandler: ShareIntentHandler by lazy {
        ShareIntentHandler(applicationContext)
    }

    /** Phase 8.10: 当前待消费的 Intent 处理结果(由 NavGraph 观察)。 */
    private var pendingShareResult by mutableStateOf<ShareIntentHandler.ShareResult>(
        ShareIntentHandler.ShareResult.None
    )

    /** 通知权限申请 launcher(Android 13+ 必需 POST_NOTIFICATIONS 运行时权限)。 */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Logger.i("MainActivity", "POST_NOTIFICATIONS granted=$granted")
        }

    /**
     * v1.102: 在 attachBaseContext 中用存储的 language 偏好包装 Context。
     *
     * Compose 的 [androidx.compose.ui.res.stringResource] 读的是 Activity 的 Resources,
     * 其 Configuration 由 baseContext 决定。[androidx.appcompat.app.AppCompatDelegate.setApplicationLocales]
     * 在 ComponentActivity(非 AppCompatActivity)上 Android 12 及以下的 backport 不生效,
     * 因此这里手动用 [android.content.Context.createConfigurationContext] 覆盖 Configuration。
     *
     * [recreate] 会创建新的 Activity 实例并重新调用 attachBaseContext,
     * 自动读取最新的 language 偏好,无需在 Compose 里处理(避免之前的死循环)。
     *
     * runBlocking 读 DataStore:仅启动/recreate 时各一次,DataStore 有内存缓存,< 2ms。
     */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(wrapWithLanguage(newBase))
    }

    private fun wrapWithLanguage(base: android.content.Context): android.content.Context {
        // Koin 在 Application.onCreate 启动,attachBaseContext 在其后执行,此时已可用。
        val settings = GlobalContext.getOrNull()?.get<SettingsRepository>() ?: return base
        // v1.131: 改用 SharedPreferences 同步缓存,消除主线程 runBlocking 读 DataStore 的 ANR 风险。
        // 历史 DataStore 值会在 SettingsRepository.init 中异步迁移到 SP,首次冷启动可能短暂返回默认值,
        // 但下次启动即正确,可接受(用户感知不到语言切换的差异)。
        val lang = settings.getLanguageSync()
        // I4: 语言解析收敛到 parseAppLocale,system/未知返回 null(跟随系统)。
        val locale = parseAppLocale(lang) ?: return base
        // v1.133: 复制当前 Configuration 并覆盖 locale,避免修改全局 Configuration。
        // 同时显式设置 layoutDirection,避免某些 RTL/LTR 边界场景。
        val config = android.content.res.Configuration(base.resources.configuration)
        config.setLocale(locale)
        // v1.133: setLayoutDirection 兜底,部分 ROM 上 setLocale 不联动 layoutDirection
        config.setLayoutDirection(locale)
        // v1.133: 用 createConfigurationContext 创建新 Context。
        // 额外调用 resources.updateConfiguration 兜底,部分国产 ROM(MIUI/EMUI)对
        // createConfigurationContext 的 locale 应用不稳定,需要 updateConfiguration 强制刷新。
        val newContext = base.createConfigurationContext(config)
        try {
            val wrappedResources = newContext.resources
            wrappedResources.updateConfiguration(config, wrappedResources.displayMetrics)
        } catch (e: Exception) {
            // updateConfiguration 在部分新版本被标记 deprecated 但仍可用,容错忽略
        }
        return newContext
    }

    // Activity 启动初始化(系统栏/安全模式/通知权限/字体/主题等)为屏幕级固有聚合结构,
    // 行数恰超 detekt LongMethod 阈值 1,拆分反损可读性
    @Suppress("LongMethod")
    override fun onCreate(savedInstanceState: Bundle?) {
        // v1.7: 必须在 super.onCreate 之前安装系统 SplashScreen
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // v1.7: 控制 SplashScreen 显示时长,应用初始化完成后保持 1.2s 再退出
        var splashReady by mutableStateOf(false)
        splashScreen.setKeepOnScreenCondition { !splashReady }

        // 申请 POST_NOTIFICATIONS 运行时权限(Android 13+ 必需,否则通知不显示)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // Phase 8.10: 处理 Safe Mode(上次崩溃)→ 跳过完整启动,仅显示崩溃提示
        if (MuseCrashHandler.checkSafeMode(applicationContext)) {
            Logger.w("MainActivity", "Safe mode active — last crash detected")
            splashReady = true
            setContent {
                MuseTheme(darkTheme = isSystemInDarkTheme()) {
                    SafeModeScreen()
                }
            }
            return
        }
        // Phase 8.10: 处理启动 Intent(分享/Deep Link/桌面小部件)
        // Phase 12: 桌面小部件快捷启动优先于分享/Deep Link 解析
        // M4: shareIntentHandler.handle() 已改为 suspend,在协程中异步处理,避免阻塞主线程
        // Launcher 快捷方式优先级最高:与 widget/share 不冲突(基于 action,而非 extra/data)
        val shortcutResult = shortcutActionResult(intent)
        val widgetResult = widgetActionResult(intent)
        when {
            shortcutResult != null -> pendingShareResult = shortcutResult
            widgetResult != null -> pendingShareResult = widgetResult
            else -> lifecycleScope.launch {
                pendingShareResult = shareIntentHandler.handle(intent)
            }
        }
        // v1.91-hotfix: 防御性检查 — Koin 可能因进程未重启(例如 SafeModeScreen 清除 flag
        // 后仅 finishAffinity 未杀进程,或 startKoin 本身失败)而未初始化。
        // 此时 by inject() 会触发 SynchronizedLazyImpl.getValue → GlobalContext.get()
        // → 崩溃 "KoinApplication has not been started"。
        // 检测到 Koin 未启动时走 Safe Mode 路径,避免崩溃并引导用户恢复。
        if (GlobalContext.getOrNull() == null) {
            Logger.e("MainActivity", "Koin not started — showing SafeModeScreen (process not restarted or startKoin failed)")
            splashReady = true
            setContent {
                MuseTheme(darkTheme = isSystemInDarkTheme()) {
                    SafeModeScreen()
                }
            }
            return
        }
        val settings: SettingsRepository by inject()
        // v1.102: locale 应用由 attachBaseContext + wrapWithLanguage 处理(覆盖所有 Android 版本),
        // 不再在 onCreate 里调用 applyLanguage(setApplicationLocales 在 ComponentActivity 上
        // Android 12 及以下 backport 不生效)。
        setContent {
            // I4: 语言热切换 — 收集语言流,经 RuntimeLocaleProvider 覆盖 Compose 资源,
            // 切换语言仅重组 UI,不重建 Activity(冷启动初始语言仍由 attachBaseContext 保证)。
            val language by settings.languageFlow.collectAsStateWithLifecycle(initialValue = "system")
            RuntimeLocaleProvider(lang = language) {
                // P6-C: 主题模式跟随用户设置(System / Light / Dark)
                val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
                val darkTheme = when (themeMode) {
                    "light" -> false
                    "dark" -> true
                    else -> isSystemInDarkTheme()
                }
                // 修复:initialValue 改为 "mono" 与 SettingsRepository.themeIdFlow 默认值一致,
                // 避免冷启动时首帧渲染 warm_paper 主题、随后切换到 mono 造成主题闪烁。
                val themeId by settings.themeIdFlow.collectAsStateWithLifecycle(initialValue = AppearanceSettingsStore.DEFAULT_THEME_ID)
                // 深色模式独立主题
                val darkThemeId by settings.darkThemeIdFlow.collectAsStateWithLifecycle(initialValue = "")
                // v1.65: 动态取色开关(Android 12+,代码早已就绪,此前未传参导致永远不可用)
                val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
                val fontSizeScale by settings.fontSizeScaleFlow.collectAsStateWithLifecycle(initialValue = "medium")
                // v1.97 gap7: 用户自定义主题列表 — 基于种子色生成 ColorScheme,
                // 在 MuseTheme 中作为动态色与预设主题之间的回退层
                val customThemes by settings.customThemesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
                // H5: 高对比主题开关(增强前景/背景对比,面向弱视用户)
                val highContrast by settings.highContrastFlow.collectAsStateWithLifecycle(initialValue = false)
                // E2: 自定义正文字体 — 路径→FontFamily 加载在 remember 中缓存(同步磁盘 IO 避免重复)
                val customFontPath by settings.customFontPathFlow.collectAsStateWithLifecycle(initialValue = null)
                val bodyFontFamily by remember(customFontPath) { mutableStateOf(loadCustomFontFamily(customFontPath)) }
                // I4: 语言热切换后 Compose 资源已由 RuntimeLocaleProvider 覆盖,
                // 不再需要 recreate;冷启动初始语言仍由 attachBaseContext 保证。
                MuseTheme(
                    darkTheme = darkTheme,
                    themeId = themeId,
                    darkThemeId = darkThemeId,
                    fontSizeScale = fontSizeScale,
                    dynamicColor = dynamicColor,
                    customThemes = customThemes,
                    bodyFontFamily = bodyFontFamily,
                    highContrast = highContrast,
                ) {
                    // v1.56: Compose 渲染异常由 MuseCrashHandler(Thread.UncaughtExceptionHandler)兜底,
                    // logComposeException 方法已就绪,待未来 Compose 版本提供 RuntimeExceptionHandler API 后接入。
                    Box(modifier = Modifier.fillMaxSize()) {
                        MuseNavGraph(
                            pendingShareResult = pendingShareResult,
                            onPendingIntentConsumed = {
                                pendingShareResult = ShareIntentHandler.ShareResult.None
                            },
                            onSplashReady = { splashReady = true },
                        )
                        MuseToastHost()
                    }
                }
            } // RuntimeLocaleProvider
        }
    }

    /** Phase 8.10: 处理 onNewIntent(应用已在后台,新分享/Deep Link 到达)。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // M5: shareIntentHandler.handle() 已改为 suspend,在协程中异步处理,避免阻塞主线程
        // Launcher 快捷方式优先级最高:与 widget/share 不冲突
        val shortcutResult = shortcutActionResult(intent)
        val widgetResult = widgetActionResult(intent)
        when {
            shortcutResult != null -> pendingShareResult = shortcutResult
            widgetResult != null -> pendingShareResult = widgetResult
            else -> lifecycleScope.launch {
                pendingShareResult = shareIntentHandler.handle(intent)
            }
        }
    }

    /**
     * Launcher 长按快捷方式:根据 Intent.action 判断是否来自 shortcuts.xml。
     *
     * shortcuts.xml 中的静态快捷方式点击后,系统会用 intent 中声明的 action 启动 MainActivity。
     * 这里把 action 映射到 [ShareIntentHandler.ShareResult],复用既有 pendingShareResult 流水线,
     * 由 MuseNavGraph 中的 LaunchedEffect 消费并导航到对应页面。
     */
    private fun shortcutActionResult(intent: Intent?): ShareIntentHandler.ShareResult? {
        val action = intent?.action ?: return null
        return when (action) {
            ShareIntentHandler.ACTION_NEW_CHAT -> ShareIntentHandler.ShareResult.NewSession
            ShareIntentHandler.ACTION_TRANSLATE -> ShareIntentHandler.ShareResult.OpenTranslate
            ShareIntentHandler.ACTION_VOICE_INPUT -> ShareIntentHandler.ShareResult.StartVoiceInput
            ShareIntentHandler.ACTION_SETTINGS -> ShareIntentHandler.ShareResult.OpenSettings
            // v1.0.18: 快速记录快捷方式
            ShareIntentHandler.ACTION_QUICK_NOTES -> ShareIntentHandler.ShareResult.OpenQuickNotes
            else -> null
        }
    }

    /** Phase 12: 桌面小部件快捷启动 — 检查 widget_action extra。 */
    private fun widgetActionResult(intent: Intent?): ShareIntentHandler.ShareResult? {
        val action = intent?.getStringExtra(ShareIntentHandler.EXTRA_WIDGET_ACTION) ?: return null
        return when (action) {
            ShareIntentHandler.WIDGET_ACTION_NEW_SESSION -> ShareIntentHandler.ShareResult.NewSession
            ShareIntentHandler.WIDGET_ACTION_OPEN_CHATS -> ShareIntentHandler.ShareResult.OpenChats
            // P3-16: 对话小部件 → 打开指定会话
            ShareIntentHandler.WIDGET_ACTION_OPEN_SESSION -> {
                val sessionId = intent.getStringExtra(ShareIntentHandler.EXTRA_WIDGET_SESSION_ID)
                if (sessionId != null) ShareIntentHandler.ShareResult.OpenSession(sessionId) else null
            }
            else -> null
        }
    }
}

/**
 * v0.22 重写: 主导航 — 顶部 Tab 架构。
 *
 * 架构变更:
 *  - 移除 Drawer(侧边栏全是 bug)
 *  - 首页改为 [HomeScreen](startDestination = HOME)
 *  - HomeScreen 顶部胶囊 Tab: 会话 / Agent
 *  - 左上角头像 → 设置中心
 *  - 设置/助手/记忆等通过路由跳转(slide-in 动画,对标 iOS push)
 *
 * 路由列表:
 *  - HOME (首页): 顶部 Tab 导航(会话列表 + Agent 聊天)
 *  - CHAT_DETAIL: 聊天详情页(保留,可通过路由直接访问)
 *  - SETTINGS: 设置页
 *  - ASSISTANTS: 助手管理
 *  - MEMORY: 记忆系统
 *  - FAVORITES / LOREBOOKS / QUICK_MESSAGES / PROMPT_INJECTIONS / SKILLS: 子页面
 */
@Composable
private fun MuseNavGraph(
    pendingShareResult: ShareIntentHandler.ShareResult = ShareIntentHandler.ShareResult.None,
    onPendingIntentConsumed: () -> Unit = {},
    /** v1.7: 由外部传入的 SplashScreen 就绪回调,NavGraph 初始化完成后延迟触发。 */
    onSplashReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val sharedViewModel: ChatViewModel = koinInject()
    val quickNotesViewModel: QuickNotesViewModel = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()

    // P2-17: SYSTEM(默认)ASR 走系统语音识别 Intent 的 launcher。
    // 语音快捷方式在 SYSTEM 下此前调用流式 API 路径为空实现,静默无响应;
    // 这里负责弹出系统识别对话框,并把识别文本回填到输入框。
    val systemSpeechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val text = io.zer0.muse.ui.speech.SpeechInput.parseResult(result.resultCode, result.data?.extras)
        if (!text.isNullOrBlank()) {
            sharedViewModel.updateInput(text)
        }
    }

    // v1.7: Provider 列表(原用于判断首次引导,v1.131 引导已移除,保留用于其他用途)
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val chatPreferences by settings.chatPreferencesFlow.collectAsStateWithLifecycle(
        initialValue = ChatPreferences(),
    )

    // H2: settings 加载门控 — 等 providersFlow 首次 emit 后再组合 NavHost,
    // 避免首帧空列表导致 NavHost 用错误状态组合。NavHost 仅在首次组合读取 startDestination
    var providersLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        settings.providersFlow.first()
        providersLoaded = true
    }
    val settingsReady = providersLoaded

    // 开机引导:首次启动时显示引导页,完成后进入主界面
    // 老用户兼容:已有 Provider 配置的跳过完整引导
    val onboardingShown by settings.onboardingShownFlow.collectAsStateWithLifecycle(initialValue = true)
    var onboardingCompleted by rememberSaveable { mutableStateOf(false) }
    val showOnboarding = settingsReady && !onboardingShown && !onboardingCompleted
    val quickCaptureEnabled by settings.quickCaptureEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    val quickCaptureOverlayEnabled by settings.quickCaptureOverlayEnabledFlow.collectAsStateWithLifecycle(initialValue = false)

    // v1.7: 系统 SplashScreen 由 MainActivity 的 keepOnScreenCondition 控制,
    // 这里只负责在 NavHost 初始化 1.2s 后把条件放开。
    var splashDelayDone by remember { mutableStateOf(false) }
    val splashDelayMillis = MuseMotion.duration(1200)
    LaunchedEffect(splashDelayMillis) {
        delay(splashDelayMillis.toLong())
        splashDelayDone = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // P2-13: 桌面端全局快捷键 — Ctrl+, 打开设置,Esc 退出当前页(popBackStack)
        // 仅在物理键盘 + Expanded 窗口下生效;Esc 在根页(HOME)上 popBackStack 返回 false,
        // 不会退出 App,系统返回键语义一致
        val desktopShortcutsEnabled = rememberDesktopShortcutsEnabled()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onKeyEvent { event ->
                    if (!desktopShortcutsEnabled) return@onKeyEvent false
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        // Ctrl+,: 打开设置(对齐 VS Code / Slack 等桌面应用习惯)
                        event.key == DesktopShortcuts.OPEN_SETTINGS && event.isCtrlPressed -> {
                            navController.navigate(SettingsRoute)
                            true
                        }
                        // Esc: 退出当前页(等价于系统返回键)
                        event.key == DesktopShortcuts.CLOSE -> {
                            navController.popBackStack()
                            true
                        }
                        else -> false
                    }
                },
        ) {
            // v1.x: 应用锁功能已移除(产品决策),不再订阅 appPin/biometric 设置
        LaunchedEffect(splashDelayDone, settingsReady) {
            if (splashDelayDone && settingsReady) {
                onSplashReady()
            }
        }

        LaunchedEffect(
            settingsReady,
            showOnboarding,
            quickCaptureEnabled,
            quickCaptureOverlayEnabled,
        ) {
            val shouldRunOverlay = settingsReady &&
                !showOnboarding &&
                quickCaptureEnabled &&
                quickCaptureOverlayEnabled &&
                android.provider.Settings.canDrawOverlays(context)
            if (shouldRunOverlay) {
                QuickCaptureOverlayService.start(context)
            } else if (!quickCaptureEnabled || !quickCaptureOverlayEnabled || showOnboarding) {
                QuickCaptureOverlayService.stop(context)
            }
        }

        // v1.27: 不再依赖登录态重建 NavHost,直接使用固定导航图
        // H2: settings 加载完成后再组合 NavHost,确保 startDestination 用真实 providers/onboardingShown
        if (settingsReady) {
            // 开机引导页:首次启动且未完成引导时,全屏覆盖显示引导
            if (showOnboarding) {
                OnboardingScreen(
                    onComplete = { onboardingCompleted = true },
                )
            } else {
                // Phase 8.10: 消费分享/Deep Link 结果
                LaunchedEffect(pendingShareResult) {
                    val result = pendingShareResult
                    when (result) {
                        is ShareIntentHandler.ShareResult.PrefillText -> {
                            sharedViewModel.updateInput(result.text)
                            navController.navigate(HomeRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        is ShareIntentHandler.ShareResult.OpenSession -> {
                            navController.navigate(ChatDetailRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                            sharedViewModel.openSessionFromNotification(result.sessionId)
                        }
                        is ShareIntentHandler.ShareResult.NewSession -> {
                            sharedViewModel.createNewSession()
                            navController.navigate(HomeRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        is ShareIntentHandler.ShareResult.OpenAssistants -> {
                            navController.navigate(AssistantsRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenSettings -> {
                            navController.navigate(SettingsRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenChats -> {
                            navController.navigate(HomeRoute) {
                                popUpTo(HomeRoute) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        }
                        is ShareIntentHandler.ShareResult.OpenScheduledTasks -> {
                            navController.navigate(ScheduledTasksRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenScheduledTask -> {
                            navController.navigate(ScheduledTaskRoute(result.taskId))
                        }
                        is ShareIntentHandler.ShareResult.OpenQuickNote -> {
                            navController.navigate(QuickNoteRoute(result.noteId))
                        }
                        is ShareIntentHandler.ShareResult.OpenChat -> {
                            navController.navigate(ChatDetailRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenHome -> {
                            navController.navigate(HomeRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        is ShareIntentHandler.ShareResult.OpenSettingsData -> {
                            navController.navigate(SettingsDataRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenCloudBackup -> {
                            navController.navigate(SettingsCloudBackupRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenSettingsAbout -> {
                            navController.navigate(SettingsAboutRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenSettingsAgent -> {
                            navController.navigate(SettingsAgentRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenSettingsModel -> {
                            navController.navigate(SettingsModelRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenMemory -> {
                            navController.navigate(SettingsMemoryRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenKnowledge -> {
                            navController.navigate(KnowledgeRoute)
                        }
                        is ShareIntentHandler.ShareResult.OpenKnowledgeBases -> {
                            navController.navigate(KnowledgeBaseManageRoute)
                        }
                        // Launcher 快捷方式:打开翻译页
                        is ShareIntentHandler.ShareResult.OpenTranslate -> {
                            navController.navigate(TranslateRoute)
                        }
                        // Launcher 快捷方式:进入主页并触发语音输入
                        is ShareIntentHandler.ShareResult.StartVoiceInput -> {
                            navController.navigate(HomeRoute) {
                                popUpTo(HomeRoute) { inclusive = false }
                                launchSingleTop = true
                            }
                            // P2-17: SYSTEM(默认)/文件模式无流式 ASR 实现,直接调用会静默无响应;
                            // 改走系统语音识别 Intent(SpeechInput),识别文本回填输入框。
                            if (sharedViewModel.shouldUseApiRecording()) {
                                // 触发流式 ASR(麦克风录音识别)
                                // 注意:这里直接调用 sharedViewModel 上的 ASR 入口,
                                // UI(InputBar)会通过 asrState 状态观察并显示录音中状态。
                                sharedViewModel.startStreamingAsr()
                            } else if (io.zer0.muse.ui.speech.SpeechInput.isAvailable(context)) {
                                systemSpeechLauncher.launch(
                                    io.zer0.muse.ui.speech.SpeechInput.createIntent(
                                        context.getString(R.string.settings_asr_provider_system),
                                    ),
                                )
                            } else {
                                // 无可用语音识别器时给明确提示,不再静默
                                io.zer0.muse.ui.common.feedback.MuseToast.show(
                                    context.getString(R.string.asr_system_unavailable),
                                )
                            }
                        }
                        // v1.0.18: Launcher 快捷方式:打开快速记录页
                        is ShareIntentHandler.ShareResult.OpenQuickNotes -> {
                            navController.navigate(QuickNotesRoute)
                        }
                        ShareIntentHandler.ShareResult.None -> Unit
                    }
                    // 一次性消费通知/Deep Link 事件，确保连续点击同类通知也能重新导航。
                    if (result != ShareIntentHandler.ShareResult.None) {
                        onPendingIntentConsumed()
                    }
                }

                // v1.131: 首次启动引导已移除,直接进入主页
                val startDestination = HomeRoute

                // 返回交给 NavHost 自带的 Navigation Compose 回调：
                // 子页面由导航栈回退，根页面没有可回退栈时交给 Activity 默认行为退出。
                // 页面级 BackHandler、Dialog 和 Popup 仍按 Compose 的内层优先级先消费。

                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    // 默认过渡与各子 NavGraph 复用同一套 helper，避免根图和二级图的
                    // 时长/位移/淡入淡出语义漂移。
                    enterTransition = { MuseTransitions.horizontalPushEnter() },
                    exitTransition = { MuseTransitions.horizontalPushExit() },
                    popEnterTransition = { MuseTransitions.horizontalPopEnter() },
                    popExitTransition = { MuseTransitions.horizontalPushPopExit() },
                ) {
                    // P0-3: NavGraph 子图抽取 — 66 个 composable 拆分到 4 个域文件
                    chatNavGraph(
                        navController = navController,
                        sharedViewModel = sharedViewModel,
                        context = context,
                    )
                    assistantNavGraph(
                        navController = navController,
                        sharedViewModel = sharedViewModel,
                    )
                    settingsNavGraph(
                        navController = navController,
                    )
                    toolsNavGraph(
                        navController = navController,
                        sharedViewModel = sharedViewModel,
                    )
                }
            } // 关闭 else(showOnboarding) 分支
        }

        // 高频入口:在 Muse 内任何页面从右侧边缘左滑,唤起快速记录侧滑面板。
        // 不申请悬浮窗权限,也不覆盖引导页,避免与系统手势冲突。
        QuickCaptureEdgeOverlay(
            enabled = settingsReady &&
                !showOnboarding &&
                quickCaptureEnabled &&
                !quickCaptureOverlayEnabled,
            viewModel = quickNotesViewModel,
        )

        } // 关闭 P2-13 内层 onKeyEvent Box
    }
}

/**
 * 语言偏好字符串 → Locale(与 values-* 资源目录对应)。
 * "system" 或未知值返回 null,表示跟随系统默认语言。
 */
private fun parseAppLocale(lang: String): java.util.Locale? = when (lang) {
    "zh" -> java.util.Locale.SIMPLIFIED_CHINESE
    "en" -> java.util.Locale.US
    "ja" -> java.util.Locale.JAPAN
    "ko" -> java.util.Locale.KOREA
    "ru" -> java.util.Locale("ru", "RU")
    "es" -> java.util.Locale("es", "ES")
    "pt-rBR" -> java.util.Locale("pt", "BR")
    else -> null
}

/**
 * I4: 运行时语言热切换 — 覆盖 Compose 的 [LocalContext]/[LocalConfiguration],
 * 语言变化时仅重组 UI,不重建 Activity。
 *
 * 以 Application 级系统配置为基准(attachBaseContext 只包 Activity 基资源,
 * applicationContext 始终是系统配置),因此无论从哪种语言切回 "system" 都能正确恢复。
 * stringResource 在 Compose 1.7 直接读 LocalContext.current.resources,故覆盖 LocalContext
 * 为新 locale 的 ContextWrapper(applicationContext 透传,系统服务/launcher 均不受影响;
 * 依赖 Activity 身份的 as? Activity 调用点数量有限且均有兜底)。
 * ROM 兜底: 与 wrapWithLanguage 一致,额外 updateConfiguration 强制刷新 locale。
 */
@Composable
private fun RuntimeLocaleProvider(
    lang: String,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    // 覆盖 LocalContext 时显式透传 Activity Result 宿主,否则聊天页的
    // rememberLauncherForActivityResult 在进入 ChatScreen 时会直接崩溃。
    val activityResultRegistryOwner = checkNotNull(LocalActivityResultRegistryOwner.current) {
        "MainActivity requires an ActivityResultRegistryOwner"
    }
    val appContext = context.applicationContext
    val locale = remember(lang) { parseAppLocale(lang) }
    val config = remember(lang) {
        val base = Configuration(appContext.resources.configuration)
        if (locale != null) {
            base.setLocale(locale)
            base.setLayoutDirection(locale)
        }
        base
    }
    val localizedContext = remember(lang) {
        val wrapped = appContext.createConfigurationContext(config)
        try {
            wrapped.resources.updateConfiguration(config, wrapped.resources.displayMetrics)
        } catch (e: Exception) {
            // updateConfiguration 在部分新版本被标记 deprecated 但仍可用,容错忽略
        }
        wrapped
    }
    CompositionLocalProvider(
        LocalActivityResultRegistryOwner provides activityResultRegistryOwner,
        LocalConfiguration provides config,
        LocalContext provides localizedContext,
        content = content,
    )
}
