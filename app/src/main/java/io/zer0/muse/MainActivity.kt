package io.zer0.muse

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.zer0.common.Logger
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.R
import io.zer0.muse.intent.ShareIntentHandler
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.MuseRoutes
import io.zer0.muse.ui.onboarding.OnboardingScreen
import io.zer0.muse.ui.SafeModeScreen
import io.zer0.muse.ui.common.DesktopShortcuts
import io.zer0.muse.ui.common.MuseToastHost
import io.zer0.muse.ui.common.rememberDesktopShortcutsEnabled
import io.zer0.muse.ui.navigation.assistantNavGraph
import io.zer0.muse.ui.navigation.chatNavGraph
import io.zer0.muse.ui.navigation.settingsNavGraph
import io.zer0.muse.ui.navigation.toolsNavGraph
import io.zer0.muse.ui.theme.MuseTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.compose.koinInject
import org.koin.core.context.GlobalContext

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
        val locale = when (lang) {
            "zh" -> java.util.Locale.SIMPLIFIED_CHINESE
            "en" -> java.util.Locale.US
            "ja" -> java.util.Locale.JAPAN
            "ko" -> java.util.Locale.KOREA
            "ru" -> java.util.Locale("ru", "RU")
            else -> return base // system 或未知,用系统默认
        }
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
            // P6-C: 主题模式跟随用户设置(System / Light / Dark)
            val themeMode by settings.themeModeFlow.collectAsStateWithLifecycle(initialValue = "system")
            val darkTheme = when (themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // 修复:initialValue 改为 "mono" 与 SettingsRepository.themeIdFlow 默认值一致,
            // 避免冷启动时首帧渲染 warm_paper 主题、随后切换到 mono 造成主题闪烁。
            val themeId by settings.themeIdFlow.collectAsStateWithLifecycle(initialValue = "mono")
            // 深色模式独立主题
            val darkThemeId by settings.darkThemeIdFlow.collectAsStateWithLifecycle(initialValue = "")
            // v1.65: 动态取色开关(Android 12+,代码早已就绪,此前未传参导致永远不可用)
            val dynamicColor by settings.dynamicColorFlow.collectAsStateWithLifecycle(initialValue = false)
            val fontSizeScale by settings.fontSizeScaleFlow.collectAsStateWithLifecycle(initialValue = "medium")
            // v1.97 gap7: 用户自定义主题列表 — 基于种子色生成 ColorScheme,
            // 在 MuseTheme 中作为动态色与预设主题之间的回退层
            val customThemes by settings.customThemesFlow.collectAsStateWithLifecycle(initialValue = emptyList())
            // v1.102: 语言应用由 attachBaseContext + wrapWithLanguage 处理,
            // ThemeSection 切语言后 saveLanguage + recreate,不再在 Compose 里处理 locale。
            MuseTheme(
                darkTheme = darkTheme,
                themeId = themeId,
                darkThemeId = darkThemeId,
                fontSizeScale = fontSizeScale,
                dynamicColor = dynamicColor,
                customThemes = customThemes,
            ) {
                // v1.56: Compose 渲染异常由 MuseCrashHandler(Thread.UncaughtExceptionHandler)兜底,
                // logComposeException 方法已就绪,待未来 Compose 版本提供 RuntimeExceptionHandler API 后接入。
                Box(modifier = Modifier.fillMaxSize()) {
                    MuseNavGraph(
                        pendingShareResult = pendingShareResult,
                        onSplashReady = { splashReady = true },
                    )
                    MuseToastHost()
                }
            }
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
    /** v1.7: 由外部传入的 SplashScreen 就绪回调,NavGraph 初始化完成后延迟触发。 */
    onSplashReady: () -> Unit = {},
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val sharedViewModel: ChatViewModel = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()

    // v1.7: Provider 列表(原用于判断首次引导,v1.131 引导已移除,保留用于其他用途)
    val providers by settings.providersFlow.collectAsStateWithLifecycle(initialValue = emptyList())

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

    // v1.7: 系统 SplashScreen 由 MainActivity 的 keepOnScreenCondition 控制,
    // 这里只负责在 NavHost 初始化 1.2s 后把条件放开。
    // v0.33 修复(M6): 同时等待 appPinFlow 首次 emit(置 appPinLoading=false),
    // 避免 PIN 空串初值期间主界面短暂绕过锁屏;loading 期间保持 splash 不退出
    var splashDelayDone by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(1200)
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
                            navController.navigate(MuseRoutes.SETTINGS)
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
            // 功能1: 生物识别解锁 + PIN 锁拦截
            var appPin by remember { mutableStateOf("") }
            var appPinLoading by remember { mutableStateOf(true) }
            var biometricEnabled by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            settings.appPinFlow.collect { pin ->
                appPin = pin
                appPinLoading = false
            }
        }
        LaunchedEffect(Unit) {
            settings.biometricEnabledFlow.collect { enabled ->
                biometricEnabled = enabled
            }
        }
        LaunchedEffect(splashDelayDone, appPinLoading, settingsReady) {
            if (splashDelayDone && !appPinLoading && settingsReady) {
                onSplashReady()
            }
        }
        var pinUnlocked by rememberSaveable { mutableStateOf(false) }
        var biometricSkipped by rememberSaveable { mutableStateOf(false) }
        // v1.125: App 退后台时重置 pinUnlocked,返回后需重新输入 PIN
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    pinUnlocked = false
                    biometricSkipped = false
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
            }
        }
        val needPin = appPin.isNotEmpty() && !pinUnlocked
        val needBiometric = biometricEnabled && appPin.isNotEmpty() && !pinUnlocked && !biometricSkipped

        // 功能1: 生物识别弹窗
        if (needBiometric) {
            val activity = context as? FragmentActivity
            if (activity != null) {
                val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
                val biometricPrompt = remember {
                    BiometricPrompt(
                        activity,
                        executor,
                        object : BiometricPrompt.AuthenticationCallback() {
                            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                                pinUnlocked = true
                            }
                            // 仅用户取消/硬件错误(errorCode)才视为跳过,放行到 PIN 锁屏;
                            // onAuthenticationFailed 是单次指纹不匹配,不应绕过锁屏,否则误触即解锁。
                            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                                biometricSkipped = true
                            }
                        },
                    )
                }
                LaunchedEffect(Unit) {
                    biometricPrompt.authenticate(
                        BiometricPrompt.PromptInfo.Builder()
                            .setTitle(context.getString(R.string.biometric_prompt_title))
                            .setSubtitle(context.getString(R.string.biometric_prompt_subtitle))
                            .setAllowedAuthenticators(
                                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG,
                            )
                            .setConfirmationRequired(false)
                            .build()
                    )
                }
            } else {
                biometricSkipped = true
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
                // M5: PIN 锁屏期间不消费 deep link/share intent,解锁后(needPin 变 false)重新触发
                LaunchedEffect(pendingShareResult, needPin) {
                    if (needPin) return@LaunchedEffect
                    when (pendingShareResult) {
                        is ShareIntentHandler.ShareResult.PrefillText -> {
                            sharedViewModel.updateInput(pendingShareResult.text)
                            navController.navigate(MuseRoutes.HOME) {
                                popUpTo(MuseRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        is ShareIntentHandler.ShareResult.OpenSession -> {
                            sharedViewModel.switchSession(pendingShareResult.sessionId)
                            navController.navigate(MuseRoutes.HOME) {
                                popUpTo(MuseRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        is ShareIntentHandler.ShareResult.NewSession -> {
                            sharedViewModel.createNewSession()
                            navController.navigate(MuseRoutes.HOME) {
                                popUpTo(MuseRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                        is ShareIntentHandler.ShareResult.OpenAssistants -> {
                            navController.navigate(MuseRoutes.ASSISTANTS)
                        }
                        is ShareIntentHandler.ShareResult.OpenSettings -> {
                            navController.navigate(MuseRoutes.SETTINGS)
                        }
                        is ShareIntentHandler.ShareResult.OpenChats -> {
                            navController.navigate(MuseRoutes.HOME) {
                                popUpTo(MuseRoutes.HOME) {
                                    inclusive = true
                                }
                                launchSingleTop = true
                            }
                        }
                        is ShareIntentHandler.ShareResult.OpenScheduledTasks -> {
                            navController.navigate(MuseRoutes.SCHEDULED_TASKS)
                        }
                        // Launcher 快捷方式:打开翻译页
                        is ShareIntentHandler.ShareResult.OpenTranslate -> {
                            navController.navigate(MuseRoutes.TRANSLATE)
                        }
                        // Launcher 快捷方式:进入主页并触发语音输入
                        is ShareIntentHandler.ShareResult.StartVoiceInput -> {
                            navController.navigate(MuseRoutes.HOME) {
                                popUpTo(MuseRoutes.HOME) { inclusive = false }
                                launchSingleTop = true
                            }
                            // 触发流式 ASR(麦克风录音识别)
                            // 注意:这里直接调用 sharedViewModel 上的 ASR 入口,
                            // UI(InputBar)会通过 asrState 状态观察并显示录音中状态。
                            sharedViewModel.startStreamingAsr()
                        }
                        // v1.0.18: Launcher 快捷方式:打开快速记录页
                        is ShareIntentHandler.ShareResult.OpenQuickNotes -> {
                            navController.navigate(MuseRoutes.QUICK_NOTES)
                        }
                        ShareIntentHandler.ShareResult.None -> Unit
                    }
                }

                // v1.131: 首次启动引导已移除,直接进入主页
                val startDestination = MuseRoutes.HOME

                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background),
                    // v1.40: 统一 iOS push 动画 — 新页右滑入 + 旧页左滑出,返回时反向
                    // 二级路由会覆盖 enterTransition;这里作为默认保证所有路由都有完整 4 段动画
                    // Phase 7: 使用 MuseAnimation 令牌统一动画规范
                    enterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { it },
                            animationSpec = tween(io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS, easing = io.zer0.muse.ui.theme.MuseAnimation.EaseOutCubic),
                        ) + fadeIn(tween(io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS, easing = io.zer0.muse.ui.theme.MuseAnimation.EaseOutCubic))
                    },
                    exitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { -it / 3 },
                            animationSpec = tween(io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS, easing = io.zer0.muse.ui.theme.MuseAnimation.EaseOutCubic),
                        ) + fadeOut(tween(io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS, easing = io.zer0.muse.ui.theme.MuseAnimation.EaseOutCubic))
                    },
                    popEnterTransition = {
                        slideInHorizontally(
                            initialOffsetX = { -it / 3 },
                            animationSpec = tween(io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS, easing = io.zer0.muse.ui.theme.MuseAnimation.EaseOutCubic),
                        ) + fadeIn(tween(io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS, easing = io.zer0.muse.ui.theme.MuseAnimation.EaseOutCubic))
                    },
                    popExitTransition = {
                        slideOutHorizontally(
                            targetOffsetX = { it },
                            animationSpec = tween(io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS, easing = io.zer0.muse.ui.theme.MuseAnimation.EaseOutCubic),
                        ) + fadeOut(tween(io.zer0.muse.ui.theme.MuseAnimation.SLOW_MS, easing = io.zer0.muse.ui.theme.MuseAnimation.EaseOutCubic))
                    },
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

        // v0.33: PIN 锁界面(Splash 后、主界面之上覆盖,直到输入正确 PIN)
        if (needPin) {
            PinLockScreen(
                expectedPin = appPin,
                settings = settings,
                onUnlocked = { pinUnlocked = true },
            )
        }
        } // 关闭 P2-13 内层 onKeyEvent Box
    }
}

/**
 * v0.33: PIN 锁拦截界面。
 *
 * 在 Splash 之后、主界面之上覆盖,直到用户输入正确 PIN 才解除。
 * 设计:
 *  - 居中圆形锁图标 + "请输入 PIN" 提示
 *  - 4-8 位数字密码框(PasswordVisualTransformation 隐藏)
 *  - 输入正确 → onUnlocked 回调,UI 自动消失
 *  - 输入错误 → 抖动 + 错误提示
 *  - 右上角 "退出应用" 按钮(避免忘记 PIN 卡死)
 */
@Composable
private fun PinLockScreen(
    expectedPin: String,
    /** v1.104: 注入 SettingsRepository,失败计数持久化到 DataStore,跨冷启动保留。 */
    settings: io.zer0.muse.data.SettingsRepository,
    onUnlocked: () -> Unit,
) {
    // L2: pinDraft 用 rememberSaveable,旋转时不丢失
    var pinDraft by rememberSaveable { mutableStateOf("") }
    var errorShown by remember { mutableStateOf(false) }
    // v1.104: PIN 失败计数 + 锁定时间从 DataStore 加载初始值(之前 rememberSaveable 杀进程即重置)
    val scope = rememberCoroutineScope()
    var pinFailCount by remember {
        mutableIntStateOf(0)
    }
    var pinLockUntil by remember {
        mutableLongStateOf(0L)
    }
    // 冷启动时从 DataStore 读一次持久化的失败计数和锁定时间
    LaunchedEffect(Unit) {
        pinFailCount = settings.pinFailCountFlow.first()
        pinLockUntil = settings.pinLockUntilFlow.first()
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val isLocked = nowMs < pinLockUntil
    val remainingSeconds = ((pinLockUntil - nowMs) / 1000).coerceAtLeast(0)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // M6: 锁定期间每秒刷新倒计时
    LaunchedEffect(pinLockUntil) {
        if (pinLockUntil > 0) {
            while (System.currentTimeMillis() < pinLockUntil) {
                nowMs = System.currentTimeMillis()
                delay(1000)
            }
            nowMs = System.currentTimeMillis()
        }
    }

    // L1: 根 Surface 加系统 inset padding,避免内容顶到状态栏/导航栏
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 锁图标(圆形背景 + 品牌 primary)
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.main_activity_locked_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.main_activity_pin_prompt),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = pinDraft,
                onValueChange = { v ->
                    // M6: 锁定期间禁用输入
                    if (isLocked) return@OutlinedTextField
                    // 只允许数字,长度限制为 expectedPin 长度
                    val filtered = v.filter { it.isDigit() }.take(expectedPin.length.coerceAtLeast(8))
                    pinDraft = filtered
                    errorShown = false
                    // 输入完整长度后自动校验
                    if (filtered.length == expectedPin.length) {
                        if (filtered == expectedPin) {
                            // M6: 输入正确时重置失败计数
                            pinFailCount = 0
                            pinLockUntil = 0L
                            // v1.104: 持久化重置(清除锁定状态)
                            scope.launch { settings.savePinFailState(0, 0L) }
                            keyboard?.hide()
                            onUnlocked()
                        } else {
                            // M6: 失败计数 + 递增延时锁定(5 次 30s,之后每次翻倍)
                            errorShown = true
                            pinDraft = ""
                            pinFailCount++
                            if (pinFailCount >= 5) {
                                val shift = (pinFailCount - 5).coerceAtMost(20)
                                val delayMs = 30000L * (1L shl shift)
                                pinLockUntil = System.currentTimeMillis() + delayMs
                            }
                            // v1.104: 持久化失败计数 + 锁定时间,杀进程重启后保留
                            scope.launch { settings.savePinFailState(pinFailCount, pinLockUntil) }
                        }
                    }
                },
                label = { Text("PIN") },
                singleLine = true,
                enabled = !isLocked,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                ),
                isError = errorShown,
                supportingText = {
                    when {
                        isLocked -> {
                            Text(
                                "PIN 错误次数过多,请等待 ${remainingSeconds}s 后重试",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        errorShown -> {
                            Text(
                                "PIN 不正确,请重试",
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))

            // 退出应用按钮(避免用户卡死)
            TextButton(
                onClick = {
                    // L6: 与 SafeModeScreen 一致,用 finishAffinity 退到桌面
                    (context as? android.app.Activity)?.finishAffinity()
                },
            ) {
                Text(stringResource(R.string.main_activity_exit_app), color = MaterialTheme.colorScheme.outline)
            }
        }
    }
}
