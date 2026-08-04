package io.zer0.muse

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.os.StrictMode
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.SvgDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.boot.BootReceiver
import io.zer0.muse.crash.MuseCrashHandler
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.ThemeScheduleConfig
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.knowledge.KnowledgeDocDao
import io.zer0.muse.data.knowledge.KnowledgeDocEntity
import io.zer0.muse.data.quicknote.QuickNoteDao
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.notification.MuseNotificationManager
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.quicknote.QuickNoteStore
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.speech.TtsManager
import io.zer0.muse.util.GlobalCoroutineExceptionHandler
import io.zer0.muse.web.CompositeWebSearchService
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchService
import io.zer0.muse.web.WebServer
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.util.concurrent.TimeUnit

/**
 * 应用入口。初始化 Koin,装载全部模块,启动 memory ticker。
 *
 * Phase 8.2: 启动时确保默认 Assistant 存在(首次安装/老用户升级迁移)。
 * Phase 8.10: 安装全局 CrashHandler + 创建通知渠道。
 * Phase 8.11: 若 Web 服务器配置为 enabled,自动启动。
 * Phase 11.1.6: 实现 [ImageLoaderFactory] 注册 SVG + GIF 解码器
 *   (coil-svg / coil-gif 依赖需配合 ImageLoader.components 注册才生效)。
 */
class MuseApp : Application(), ImageLoaderFactory {

    private val memoryTicker: MemoryTicker by inject()
    private val memoryBackfillMigration: io.zer0.muse.data.MemoryBackfillMigration by inject()
    // v1.0.52 P2-2: 记忆空间仓库,启动时确保默认 Space 存在
    private val memorySpaceRepository: io.zer0.memory.space.MemorySpaceRepository by inject()
    private val assistantRepository: AssistantRepository by inject()
    private val skillRepository: SkillRepository by inject()
    private val knowledgeDocDao: KnowledgeDocDao by inject()
    private val notificationManager: MuseNotificationManager by inject()
    private val webServer: WebServer by inject()
    private val settings: SettingsRepository by inject()
    private val scheduledTaskRunner: io.zer0.muse.schedule.ScheduledTaskRunner by inject()
    private val proactiveMessageRunner: io.zer0.muse.schedule.ProactiveMessageRunner by inject()
    // v1.98: 云备份自动定时上传调度器
    private val cloudBackupScheduler: io.zer0.muse.schedule.CloudBackupScheduler by inject()
    private val ttsManager: TtsManager by inject()
    private val webSearchService: WebSearchService by inject()
    /** v1.133: GitHub Release 更新通知器(应用启动后异步检查)。 */
    private val updateNotifier: io.zer0.muse.update.UpdateNotifier by inject()
    /** P2-4: 审计日志记录器(启动时清理过期日志)。 */
    private val auditLogger: io.zer0.muse.data.audit.AuditLogger by inject()
    /** v1.92: ChatViewModel 为 single 单例,onCleared 永不调用,需在 ON_STOP 时手动释放资源。 */
    private val chatViewModel: ChatViewModel by inject()
    /** P1-1: Hook 注册表 — 在应用生命周期事件中调用 AppLifecycleHook。 */
    private val hookRegistry: io.zer0.muse.hook.HookRegistry by inject()
    /** P1-2: Worldbook 仓库 — 启动时注册 WorldBookHook。 */
    private val worldBookRepository: io.zer0.muse.worldbook.WorldBookRepository by inject()
    /** v1.0.12: RAG 服务 — 启动时异步加载持久化的 HNSW 索引(若已落盘)。 */
    private val ragService: io.zer0.muse.rag.RagService by inject()
    /**
     * v1.0.47: 系统提示组装器 — 启动时异步预热 buildStaticSnapshot,
     * 让 DataStore(chatPreferences/userProfile) + buildToolManifestSection 子缓存提前加载,
     * 把用户首次进会话的 system prompt 构建从 ~690ms 降到 ~200ms。
     */
    private val systemPromptAssembler: io.zer0.muse.transformer.SystemPromptAssembler by inject()
    /** v1.0.17: 快速记录 Room 迁移 — 注入旧 JSON 存储 + Room DAO,启动时一次性迁移。 */
    private val quickNoteStore: QuickNoteStore by inject()
    private val quickNoteDao: QuickNoteDao by inject()

    // B5-01: 启动时恢复被强杀的中断生成(生成检查点)
    private val sessionRepository: io.zer0.muse.data.session.SessionRepository by inject()

    // B5-02: 启动时恢复被强杀中断的群聊生成账本
    private val groupChatScheduler: io.zer0.muse.schedule.GroupChatScheduler by inject()
    /** 应用级 scope:启动一次性任务用,独立于 Koin 注册的 IO scope。 */
    // v0.53: 加 GlobalCoroutineExceptionHandler,防止协程内未捕获异常导致应用崩溃(企业级容错)
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + GlobalCoroutineExceptionHandler)
    /** v0.32: keepAwake 设置开启时持有的 PARTIAL_WAKE_LOCK,null 表示未持有。 */
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        // Phase 8.10: CrashHandler 必须最先安装(在 startKoin 之前,避免 Koin 初始化崩溃漏捕获)
        MuseCrashHandler.install(this)
        // Phase 11.3: 文件日志(便于真机验证后回捞,cacheDir 卸载自动清理)
        Logger.initFileLog(this)
        // 调试日志内存存储 + Logger sink 注册(DebugScreen 通过此 sink 接收所有日志)
        io.zer0.muse.debug.DebugLogStore.init(this)
        Logger.sink = { level, tag, msg, t ->
            io.zer0.muse.debug.DebugLogStore.log(level, tag, msg, t)
        }
        Logger.i("MuseApp", "onCreate: muse 启动")
        // H1: Safe Mode 检查 — 上次崩溃后跳过全部服务启动,仅保留 CrashHandler + Logger。
        // MainActivity.onCreate 会再次检查并展示 SafeModeScreen,这里提前 return 防止
        // Koin/后台 Runner 再次触发同一崩溃,导致用户无法抵达 SafeModeScreen
        if (MuseCrashHandler.checkSafeMode(this)) {
            Logger.w("MuseApp", "Safe mode active — 跳过服务初始化")
            super.onCreate()
            return
        }
        super.onCreate()
        // StrictMode:仅 debug 构建,检测主线程磁盘读写/网络访问违规并 penaltyLog。
        // 必须在服务初始化前启用,以便捕获 Koin/Runner 初始化期间的违规;release 不启用避免性能开销。
        if (BuildConfig.DEBUG) {
            configureStrictMode()
        }
        // v1.91-hotfix: startKoin 包裹 resultOf,失败时标记 Safe Mode 并跳过服务初始化。
        // 若 startKoin 抛异常(模块加载失败/循环依赖等)且不被捕获,会直接崩溃;
        // 更糟的是 GlobalContext 可能处于未注册状态,后续 MainActivity by inject() 会
        // 崩溃 "KoinApplication has not been started" — 那是二次崩溃,真正原因被掩盖。
        // 这里捕获后标记 Safe Mode,下次启动 MainActivity 会走 SafeModeScreen 路径。
        val koinResult = resultOf {
            startKoin {
                androidContext(this@MuseApp)
                modules(allKoinModules)
            }
        }
        if (koinResult.getOrNull() == null) {
            koinResult.onError { msg, t ->
                Logger.e("MuseApp", "startKoin 失败 — 标记 Safe Mode 并跳过服务初始化: $msg", t)
            }
            MuseCrashHandler.markSafeMode(this)
            return
        }
        // ANR 检测 + 性能监控(在 startKoin 之后、服务初始化之前启动)
        // AnrWatcher:独立守护线程检测主线程无响应(5s+),ANR 时采集线程堆栈/内存/Perf 记录写入 crash 目录。
        //   开关 settings.enableAnrDetection(默认 true);自身不阻塞主线程(独立线程 + 弱引用 Handler)。
        io.zer0.muse.crash.AnrWatcher(this, settings, auditLogger).start()
        // 断点续传(工具中断恢复):初始化 pending tool calls 持久化文件路径。
        // 必须 Early-init,确保 ChatViewModel 启动时 fileRef 已就绪。
        io.zer0.muse.chat.PendingToolCallStore.init(this)
        // P2-11: 注入 SecureCredentialStore 到 OAuthManager 单例,
        // 登录成功后 OAuth token 会加密写入独立 SP(与普通 API Key 物理隔离)
        io.zer0.muse.auth.OAuthManager.init(io.zer0.muse.auth.SecureCredentialStore(this))
        // Phase 8.10: 创建通知渠道(幂等,Android 8.0+ 必需)
        notificationManager.ensureChannels()
        // 启动 memory ticker(每小时 daily check,主触发仍是 ChatViewModel.notifyTurn)
        memoryTicker.start()
        // v1.0.51: 存量记忆迁移 — 升级后首次启动补跑历史 session 的 rollingSummary
        // 三道守卫保证幂等:DataStore 标志位 + AtomicBoolean + 记忆开关
        // 通过 backfillProgressFlow 实时报告进度,MemoryScreen 顶部显示进度条
        appScope.launch {
            resultOf { memoryBackfillMigration.migrateIfNeeded() }
                .onError { msg, t -> Logger.w("MuseApp", "memory backfill 迁移失败: $msg", t) }
                .onSuccess { ran -> if (ran) Logger.i("MuseApp", "memory backfill 迁移已执行") }
        }
        // v1.0.52 P2-2: 确保默认记忆 Space 存在(防止数据库迁移异常导致默认 Space 缺失)
        appScope.launch {
            resultOf { memorySpaceRepository.ensureDefaultSpaceExists() }
                .onError { msg, t -> Logger.w("MuseApp", "ensureDefaultSpaceExists 失败: $msg", t) }
        }
        // B5-01: 启动时恢复被强杀的中断生成(已产出内容 + [已中断] 标记)
        appScope.launch {
            resultOf { sessionRepository.recoverInterruptedGenerations() }
                .onError { msg, t -> Logger.w("MuseApp", "恢复中断生成失败: $msg", t) }
        }

        // B5-02: 启动时恢复被强杀中断的群聊生成账本(从断点续跑)
        appScope.launch {
            resultOf { groupChatScheduler.recoverInterruptedGenerations() }
                .onError { msg, t -> Logger.w("MuseApp", "恢复群聊账本失败: $msg", t) }
        }        // v1.92: ChatViewModel 为 single 单例,onCleared 永不调用。
        // 注册 ProcessLifecycleOwner 观察者,在 ON_STOP 时释放 TTS/ASR 资源并停止 memory ticker,
        // 在 ON_START 时重启 memory ticker。
        ProcessLifecycleOwner.get().lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    Logger.i("MuseApp", "Process ON_STOP: 释放 ChatViewModel 资源 + 停止 MemoryTicker")
                    // v1.0.29: 切后台时如果正在生成,启动前台服务保活。必须同步执行。
                    resultOf { chatViewModel.onAppBackground() }
                        .onError { msg, t -> Logger.w("MuseApp", "chatViewModel.onAppBackground 失败: $msg", t) }
                    resultOf { chatViewModel.release() }
                        .onError { msg, t -> Logger.w("MuseApp", "chatViewModel.release 失败: $msg", t) }
                    // v1.0.30: memoryTicker.stop 含 30s 超时等待，移入协程
                    appScope.launch {
                        resultOf { memoryTicker.stop() }
                            .onError { msg, t -> Logger.w("MuseApp", "memoryTicker.stop 失败: $msg", t) }
                        // P1-1: AppLifecycleHook.onAppBackground
                        resultOf { hookRegistry.executeNoResult(io.zer0.muse.hook.AppLifecycleHook::class) { it.onAppBackground() } }
                            .onError { msg, t -> Logger.w("MuseApp", "AppLifecycleHook.onAppBackground 失败: $msg", t) }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    // v1.0.29: 切回前台时停止前台服务通知。必须同步执行。
                    resultOf { chatViewModel.onAppForeground() }
                        .onError { msg, t -> Logger.w("MuseApp", "chatViewModel.onAppForeground 失败: $msg", t) }
                    // 回前台时重启 memory ticker
                    resultOf { memoryTicker.start() }
                        .onError { msg, t -> Logger.w("MuseApp", "memoryTicker.start 失败: $msg", t) }
                    // v1.0.16: 回前台清理 OkHttp 空闲连接，移入协程
                    appScope.launch {
                        resultOf { io.zer0.ai.core.ProviderHttpSupport.evictIdleConnections() }
                            .onError { msg, t -> Logger.w("MuseApp", "evictIdleConnections 失败: $msg", t) }
                        // P1-1: AppLifecycleHook.onAppForeground
                        resultOf { hookRegistry.executeNoResult(io.zer0.muse.hook.AppLifecycleHook::class) { it.onAppForeground() } }
                            .onError { msg, t -> Logger.w("MuseApp", "AppLifecycleHook.onAppForeground 失败: $msg", t) }
                    }
                }
                else -> {}
            }
        })
        // Phase 8.2: 确保默认 Assistant 存在(fire-and-forget,失败不阻塞启动)
        appScope.launch {
            resultOf { assistantRepository.ensureDefaultExists() }
                .onError { msg, t -> Logger.w("MuseApp", "ensureDefaultExists 失败", t) }
            // P1-4: 注册楼层式上下文限制 Hook
            resultOf {
                hookRegistry.register(io.zer0.muse.hook.FloorContextLimiterHook(settings))
            }.onError { msg, t -> Logger.w("MuseApp", "FloorContextLimiterHook 注册失败: $msg", t) }
            // P1-2: 注册 Worldbook 动态提示注入 Hook(常驻 + 关键词触发 + 深度注入)
            resultOf {
                hookRegistry.register(io.zer0.muse.worldbook.WorldBookHook(worldBookRepository))
            }.onError { msg, t -> Logger.w("MuseApp", "WorldBookHook 注册失败: $msg", t) }
        }
        // Phase 8.8: 初始化内置 Skills(幂等 upsert,REPLACE 策略)
        appScope.launch {
            resultOf {
                SkillExecutor.BUILT_IN_SKILLS.forEach { skillRepository.upsert(it) }
            }.onError { msg, t -> Logger.w("MuseApp", "内置 Skills 初始化失败", t) }
        }
        // v0.43: seed 内置开发文档到知识库(用稳定 id,升级时内容更新但不重复;fileType="devdoc" 用于 UI 过滤)
        appScope.launch {
            resultOf { seedDevDocs() }
                .onError { msg, t -> Logger.w("MuseApp", "seedDevDocs 失败", t) }
        }
        // v1.0.17: 快速记录 JSON → Room 一次性迁移
        // 通过 SharedPreferences 标志 quick_notes_migrated 保证仅执行一次:
        //  - 首次升级用户:标志 false → 迁移 → 置 true
        //  - 已迁移/新装用户:标志 true 或 JSON 无数据 → 空跑后置 true
        // fire-and-forget,失败不阻塞启动(下次启动重试,upsert REPLACE 幂等)
        appScope.launch {
            resultOf { migrateQuickNotesIfNeeded() }
                .onError { msg, t -> Logger.w("MuseApp", "快速记录迁移失败: ${t?.message ?: msg}", t) }
        }
        // v1.0.12: 启动时从 filesDir/rag/hnsw_index.bin 异步加载 HNSW 索引
        // (startKoin 之后、RagService 首次使用之前)。加载失败时首次检索走暴力遍历
        // (向后兼容,<5000 chunk 的库本就走暴力遍历),索引会在后续 indexDocument 累计
        // SAVE_INTERVAL 个 chunk 后自动重建并保存。fire-and-forget,不阻塞启动。
        appScope.launch {
            resultOf { ragService.loadVectorIndexIfNeeded() }
                .onError { msg, t -> Logger.w("MuseApp", "HNSW 索引加载失败: $msg", t) }
        }
        // v1.0.14 P0-1: 订阅 ChatPreferences.hapticFeedback,同步到 MuseHaptics 总开关。
        // 用户在「设置 → 聊天行为 → 触感反馈」切换后,所有 MuseHaptics.light/medium/heavy/soft
        // 调用立即生效(无需重启)。MuseHaptics.enabled 是 @Volatile,UI 线程读取无并发风险。
        appScope.launch {
            settings.chatPreferencesFlow.collect { prefs ->
                io.zer0.muse.ui.theme.MuseHaptics.setEnabled(prefs.hapticFeedback)
            }
        }
        // Phase 8.11: 若 Web 服务器已启用,自动启动(fire-and-forget,失败不阻塞启动)
        appScope.launch {
            resultOf {
                if (settings.webServerConfigFlow.first().enabled) {
                    webServer.start()
                }
            }.onError { msg, t -> Logger.w("MuseApp", "WebServer 启动失败", t) }
        }
        // v0.23: 启动定时任务轮询(后台协程,每 60s 检查到期任务并通知)
        // 真正崩溃已在 v0.22 修复(SettingsRepository init 块顺序),可安全恢复 Runner
        // M1: 统一用 resultOf{} 替代 runCatching{}(项目 Result 约定)
        resultOf { scheduledTaskRunner.start() }
            .onError { msg, t -> Logger.w("MuseApp", "ScheduledTaskRunner 启动失败", t) }
        // v1.104 P3: WorkManager 兜底 — App 被杀后由系统每 15 分钟拉起一次执行到期定时任务
        // KEEP 策略:已存在则保留旧 schedule(避免重复注册)
        // 不设 setExpedited / 网络约束:符合"省电"目标,无网时 executeTask 内部已记录 failed
        resultOf {
            val request = PeriodicWorkRequestBuilder<io.zer0.muse.schedule.ScheduledTaskWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(Constraints.Builder().build()).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                io.zer0.muse.schedule.ScheduledTaskWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onError { msg, t -> Logger.w("MuseApp", "ScheduledTaskWorker 注册失败", t) }
        // 主动消息轮询(陪伴助手定时主动给用户发消息 + 弹通知,每 60s 检查一次是否到期)
        resultOf { proactiveMessageRunner.start() }
            .onError { msg, t -> Logger.w("MuseApp", "ProactiveMessageRunner 启动失败", t) }
        // v1.134 P0-1: 主动消息 WorkManager 兜底 — App 被杀后由系统每 15 分钟拉起一次检查
        // KEEP 策略:已存在则保留旧 schedule(避免重复注册);与 ScheduledTaskWorker 兜底对齐
        // P2-2: Worker 路径带冷启动防打扰(长时间未触发时仅更新 lastTriggeredAt,不立即发送)
        resultOf {
            val request = PeriodicWorkRequestBuilder<io.zer0.muse.schedule.ProactiveMessageWorker>(
                15, TimeUnit.MINUTES,
            ).setConstraints(Constraints.Builder().build()).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                io.zer0.muse.schedule.ProactiveMessageWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onError { msg, t -> Logger.w("MuseApp", "ProactiveMessageWorker 注册失败", t) }
        // v1.98: 云备份自动定时上传(每 10 分钟检查是否到期)
        resultOf { cloudBackupScheduler.start() }
            .onError { msg, t -> Logger.w("MuseApp", "CloudBackupScheduler 启动失败", t) }
        // v1.132: 云备份 WorkManager 兜底 — App 被杀后由系统每 15 分钟拉起一次检查到期备份
        // KEEP 策略:已存在则保留旧 schedule(避免重复注册);与 ScheduledTaskWorker 兜底对齐
        resultOf { cloudBackupScheduler.registerWorkManagerFallback(this) }
            .onError { msg, t -> Logger.w("MuseApp", "CloudBackupWorker 注册失败", t) }
        // v1.134 P1-1: 自动本地备份 Worker — 每日 1 次 WAL checkpoint + 复制 muse.db 到 backups/
        // 接入原 v1.107 孤儿组件 AutoBackupHelper,App 被杀后由 WorkManager 拉起
        resultOf {
            val request = PeriodicWorkRequestBuilder<io.zer0.muse.schedule.AutoBackupWorker>(
                1, TimeUnit.DAYS,
            ).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                io.zer0.muse.schedule.AutoBackupWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onError { msg, t -> Logger.w("MuseApp", "AutoBackupWorker 注册失败", t) }
        // v1.134 P1-2: 统计缓存刷新 Worker — 每日 1 次全量刷新 stats_cache 表
        // 接入原 v1.107 孤儿组件 StatsCacheManager,避免统计页每次打开都全表 GROUP BY
        resultOf {
            val request = PeriodicWorkRequestBuilder<io.zer0.muse.schedule.StatsCacheWorker>(
                1, TimeUnit.DAYS,
            ).build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                io.zer0.muse.schedule.StatsCacheWorker.UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }.onError { msg, t -> Logger.w("MuseApp", "StatsCacheWorker 注册失败", t) }
        // v0.32: keepAwake — 订阅保持唤醒开关,开启时申请 PARTIAL_WAKE_LOCK
        appScope.launch {
            settings.keepAwakeFlow.collect { keepAwake -> updateWakeLock(keepAwake) }
        }
        // 主题定时切换(每 30 秒检查一次)
        appScope.launch {
            startThemeScheduler()
        }
        // v0.32: autoLaunch — 订阅开机自启开关,启用/禁用 BootReceiver 组件
        // (manifest 默认 enabled=false,这里根据用户设置切换)
        appScope.launch {
            settings.autoLaunchFlow.collect { autoLaunch -> updateBootReceiverEnabled(autoLaunch) }
        }
        // v0.33: 媒体配置 — 订阅 MediaConfig,把 TTS 语速/音高/语言 + 音频输出方式应用到实际服务
        appScope.launch {
            settings.mediaConfigFlow.collect { cfg ->
                ttsManager.applyConfig(cfg)
                applyAudioOutput(cfg.audioOutput)
            }
        }
        // v0.33: 默认搜索引擎 — 订阅 defaultSearchEngine,把值映射到 WebSearchConfig.providerName
        // 同步到 CompositeWebSearchService
        // "auto" → 不强制切换,保留用户在「模型与服务」里配的 WebSearchConfig
        // "searxng"/"tavily"/"bing" → 覆盖 providerName,立即生效
        appScope.launch {
            settings.defaultSearchEngineFlow.collect { engine ->
                applySearchEngine(engine)
            }
        }
        // v1.133: 应用启动后异步检查 GitHub Release 更新(24h 间隔,fire-and-forget)
        // 复用 appScope(IO + SupervisorJob + GlobalCoroutineExceptionHandler),
        // 任何异常都不会影响应用启动
        appScope.launch {
            resultOf { updateNotifier.checkAndNotify(this@MuseApp, forceCheck = false) }
                .onError { msg, t -> Logger.w("MuseApp", "UpdateNotifier 启动检查失败: ${t?.message ?: msg}", t) }
        }
        // P2-4: 启动时清理 30 天前的审计日志(fire-and-forget,失败不影响应用启动)
        appScope.launch {
            resultOf { auditLogger.cleanupOldLogs() }
                .onError { msg, t -> Logger.w("MuseApp", "审计日志清理失败: ${t?.message ?: msg}", t) }
        }
        // v1.x: 启动时清理超过 24 小时的工具输出文件(fire-and-forget,失败不影响应用启动)
        // 工具输出超长截断时完整内容会落盘到 filesDir/tool_outputs/,App 启动时清理过期文件避免累积。
        appScope.launch {
            resultOf { io.zer0.muse.tools.cleanupOldToolOutputs(this@MuseApp) }
                .onError { msg, t -> Logger.w("MuseApp", "工具输出文件清理失败: ${t?.message ?: msg}", t) }
        }
        // v1.0.47: 预热 system prompt 静态快照 — 异步触发一次 buildStaticSnapshot(default 助手),
        // 让 DataStore(chatPreferences/userProfile 首次读取约 50-100ms) + buildToolManifestSection
        // (工具清单构建约 100-200ms) 的子缓存提前加载。用户首次进会话时 refreshContextInfo 调用
        // buildStaticSnapshot 会命中这些子缓存,把首次构建从 ~690ms 降到 ~200ms。
        // fire-and-forget:结果丢弃,仅利用副作用(子缓存填充);失败不影响启动。
        // v1.0.52: 延后 3 秒执行 — 避免与 ChatViewModel 初始化 + memoryBackfillMigration
        // 竞争 IO 线程池(日志显示预热 117s,疑似与 backfill LLM 调用竞争数据库/IO 资源)。
        // 延后让 ChatViewModel 的 refreshContextInfo 先跑完(用户更快看到界面),预热仅填充子缓存。
        appScope.launch {
            kotlinx.coroutines.delay(3000)
            resultOf {
                val defaultAssistant = assistantRepository.getById("default")
                systemPromptAssembler.buildStaticSnapshot(
                    assistant = defaultAssistant,
                    memoryEnabled = true,
                )
            }.onError { msg, t ->
                Logger.w("MuseApp", "system prompt 预热失败: ${t?.message ?: msg}", t)
            }.onSuccess {
                Logger.d("MuseApp", "system prompt 预热完成 (${it.length} chars)")
            }
            // P1-1: AppLifecycleHook.onAppCreate(延迟执行,避免阻塞启动)
            resultOf { hookRegistry.executeNoResult(io.zer0.muse.hook.AppLifecycleHook::class) { it.onAppCreate() } }
                .onError { msg, t -> Logger.w("MuseApp", "AppLifecycleHook.onAppCreate 失败: $msg", t) }
        }
    }

    /**
     * 主题定时切换协程 — 每 30 秒检查当前时间,到起床时间切浅色,到睡觉时间切深色。
     *
     * v1.134 P0-2: 改用 while(isActive) + GlobalCoroutineExceptionHandler,与其他 Runner
     * 风格对齐;原 while(true) 在协程被取消时不会退出,且内部异常会终止协程无兜底。
     */
    private suspend fun startThemeScheduler() {
        var currentSchedule = settings.themeScheduleFlow.first()
        // 并行收集 schedule 变化
        appScope.launch(io.zer0.muse.util.GlobalCoroutineExceptionHandler) {
            settings.themeScheduleFlow.collect { currentSchedule = it }
        }
        while (coroutineContext.isActive) {
            try {
                if (currentSchedule.enabled) {
                    val now = java.util.Calendar.getInstance()
                    val currentMinute = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
                    val wakeUpMin = currentSchedule.wakeUpHour * 60 + currentSchedule.wakeUpMinute
                    val sleepMin = currentSchedule.sleepHour * 60 + currentSchedule.sleepMinute

                    val desiredMode = if (sleepMin > wakeUpMin) {
                        if (currentMinute in wakeUpMin until sleepMin) "light" else "dark"
                    } else {
                        if (currentMinute >= sleepMin || currentMinute < wakeUpMin) "dark" else "light"
                    }

                    val currentMode = settings.themeModeFlow.first()
                    if (currentMode != desiredMode && (desiredMode == "light" || desiredMode == "dark")) {
                        settings.saveThemeMode(desiredMode)
                        Logger.i("MuseApp", "主题定时切换: → $desiredMode ($currentMinute 分)")
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e  // 协程取消,正常退出
            } catch (e: Exception) {
                Logger.w("MuseApp", "ThemeScheduler tick error: ${e.message}")
            }
            kotlinx.coroutines.delay(30_000)
        }
    }

    /**
     * v0.33: 把 [defaultSearchEngine] 设置映射到 [CompositeWebSearchService]。
     *
     * 映射规则:
     *  - "auto" → 不强制切换(保留 WebSearchConfig 已有 providerName)
     *  - "searxng" → providerName = "SearXNG"
     *  - "tavily" → providerName = "Tavily"
     *  - "bing" → providerName = "Bing"
     */
    private fun applySearchEngine(engine: String) {
        val composite = webSearchService as? CompositeWebSearchService ?: return
        val providerName = when (engine) {
            "bing" -> "Bing"
            "custom_api" -> "自定义 API"
            // v1.28: 兼容旧值
            "searxng" -> "自定义 API"
            "tavily" -> "自定义 API"
            "auto" -> return // 不强制覆盖
            else -> return
        }
        // 读当前 WebSearchConfig,只覆盖 providerName(保留 apiKey/endpoint)
        appScope.launch {
            val current = settings.webSearchConfigFlow.first()
            if (current.providerName != providerName) {
                val updated = current.copy(providerName = providerName)
                resultOf { settings.saveWebSearchConfig(updated) }
                    .onError { msg, t -> Logger.w("MuseApp", "saveWebSearchConfig 失败", t) }
                composite.updateConfig(updated)
                Logger.i("MuseApp", "searchEngine=$engine → providerName=$providerName applied")
            }
        }
    }

    /**
     * v0.33: 根据用户设置的音频输出方式切换 AudioManager 路由。
     *
     *  - "speaker": 扬声器外放(MODE_NORMAL + setSpeakerphoneOn(true))
     *  - "earpiece": 听筒(MODE_IN_COMMUNICATION + setSpeakerphoneOn(false))
     *  - "bluetooth": 蓝牙耳机(MODE_IN_COMMUNICATION + startBluetoothSco)
     *
     * 影响 TTS 播报和未来录音回放的路由。
     */
    private fun applyAudioOutput(output: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        // M1: 统一用 resultOf{} 替代 runCatching{}(项目 Result 约定)
        resultOf {
            when (output) {
                "earpiece" -> {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    am.isSpeakerphoneOn = false
                    // 停止蓝牙 SCO(若已开启)
                    if (am.isBluetoothScoOn) {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                    }
                }
                "bluetooth" -> {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                    // 启动蓝牙 SCO(可能需要 1-2s 才稳定连接)
                    if (!am.isBluetoothScoOn) {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    }
                    am.isSpeakerphoneOn = false
                }
                else -> {
                    // "speaker" 或未知值 → 扬声器外放
                    am.mode = AudioManager.MODE_NORMAL
                    am.isSpeakerphoneOn = true
                    if (am.isBluetoothScoOn) {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                    }
                }
            }
        }.onError { msg, t ->
            Logger.w("MuseApp", "applyAudioOutput($output) failed: ${t?.message ?: msg}")
        }
        Logger.d("MuseApp", "audioOutput=$output applied")
    }

    /**
     * 配置 StrictMode(仅 debug 构建,由 [onCreate] 在 [BuildConfig.DEBUG] 为 true 时调用)。
     *
     * 检测主线程磁盘读写与网络访问违规,通过 penaltyLog 输出到 logcat,
     * 帮助开发期发现主线程 IO/网络等阻塞操作(潜在 ANR 根因)。
     * release 构建不启用([BuildConfig.DEBUG] == false),避免性能开销与日志泄露。
     *
     * 同时配置 VmPolicy penaltyLog,捕获资源泄漏等 VM 级违规。
     */
    private fun configureStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .penaltyLog()
                .build(),
        )
        Logger.d("MuseApp", "StrictMode 已启用(debug): detectDiskReads/detectDiskWrites/detectNetwork + penaltyLog")
    }

    /**
     * v0.32: 根据 keepAwake 开关申请/释放 PARTIAL_WAKE_LOCK。
     *
     * 用于在长时间运行的后台任务(记忆编译、定时任务)中保持 CPU 唤醒,
     * 防止设备休眠打断。仅持 CPU 锁,不影响屏幕亮度。
     */
    private fun updateWakeLock(keepAwake: Boolean) {
        if (keepAwake) {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Muse:KeepAwake").also {
                it.setReferenceCounted(false)
                // 长任务（视频生成/RAG索引）可能超过10分钟，设30分钟兜底
                it.acquire(30 * 60 * 1000L)
            }
            Logger.i("MuseApp", "keepAwake: WAKE_LOCK acquired")
        } else {
            wakeLock?.let { if (it.isHeld) resultOf { it.release() } }
            wakeLock = null
            Logger.i("MuseApp", "keepAwake: WAKE_LOCK released")
        }
    }

    /**
     * v0.32: 根据 autoLaunch 开关启用/禁用 [BootReceiver] 组件。
     *
     * 用 [PackageManager.setComponentEnabledSetting] 切换:
     *  - ENABLED:系统能向 BootReceiver 投递 BOOT_COMPLETED
     *  - DISABLED:系统跳过该 receiver,开机不启动 muse
     *
     * manifest 默认 enabled=false,首次启动后由用户设置决定。
     */
    private fun updateBootReceiverEnabled(autoLaunch: Boolean) {
        val component = ComponentName(this, BootReceiver::class.java)
        val newState = if (autoLaunch) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        // M1: 统一用 resultOf{} 替代 runCatching{}(项目 Result 约定)
        resultOf {
            packageManager.setComponentEnabledSetting(
                component,
                newState,
                android.content.pm.PackageManager.DONT_KILL_APP,
            )
        }.onError { msg, t ->
            Logger.w("MuseApp", "setComponentEnabledSetting failed: ${t?.message ?: msg}")
        }
        Logger.i("MuseApp", "autoLaunch: BootReceiver enabled=$autoLaunch")
    }

    /**
     * v0.43: 把 assets/devdocs/ 下的 markdown 开发文档 seed 到知识库。
     *
     * 设计目的: 让 LLM 通过 knowledge_search 工具能查到项目自身的开发文档(功能说明/
     * Skill 系统/记忆系统/助手配置/聊天特性/主动消息/Web 搜索实现等),从而在用户问
     * "你能做什么""长期记忆怎么生效"等问题时能据实回答,而不是凭记忆编造。
     *
     * 这些文档不向用户展示: KnowledgeScreen 会过滤 fileType="devdoc" 的条目。
     *
     * 幂等性: 用稳定 id `devdoc-<filenameWithoutExt>`,REPLACE 策略保证升级时内容更新但不重复。
     *
     * 失败容忍: 目录不存在或读取异常时静默跳过(记一条 Logger.w),不阻塞启动。
     */
    private suspend fun seedDevDocs() {
        // M1: 统一用 resultOf{} 替代 runCatching{}(项目 Result 约定)
        val names = resultOf { assets.list("devdocs") }.getOrNull()
        if (names.isNullOrEmpty()) {
            Logger.w("MuseApp", "seedDevDocs: assets/devdocs/ 不存在或为空,跳过")
            return
        }
        val now = System.currentTimeMillis()
        var seeded = 0
        names.filter { it.endsWith(".md", ignoreCase = true) }.forEach { name ->
            resultOf {
                // H7: assets.open() 返回的 InputStream 必须用 use{} 包裹,及时释放资源
                val content = assets.open("devdocs/$name").use { it.bufferedReader().readText() }
                // title 取首个 "# 标题" 行,去掉 "# " 前缀;找不到则用文件名(第一行是 devdoc 注释,跳过)
                val title = content.lineSequence().firstOrNull { it.startsWith("#") }
                    ?.removePrefix("#")
                    ?.trim()
                    ?.ifBlank { name.substringBeforeLast(".") }
                    ?: name.substringBeforeLast(".")
                val id = "devdoc-" + name.substringBeforeLast(".")
                knowledgeDocDao.upsert(
                    KnowledgeDocEntity(
                        id = id,
                        title = title,
                        content = content,
                        filePath = "assets/devdocs/$name",
                        fileType = "devdoc",
                        // v1.133: 标记为内部文档,用户在「引用知识库」选择器与知识库管理页均不可见,
                        // 仅 LLM 通过 knowledge_search(include_internal=true) 查询时可见。
                        // 与 MIGRATION_38_39 对旧数据的 backfill 保持一致。
                        isInternal = true,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                seeded++
            }.onError { msg, t ->
                Logger.w("MuseApp", "seedDevDocs: 读取 $name 失败: ${t?.message ?: msg}")
            }
        }
        Logger.i("MuseApp", "seedDevDocs: 已 seed $seeded 份开发文档")
    }

    /**
     * v1.0.17: 快速记录 JSON → Room 一次性迁移。
     *
     * 通过 SharedPreferences 标志 `quick_notes_migrated` 保证仅执行一次:
     *  - 首次升级到 v1.0.17 的用户:标志为 false → 执行迁移 → 置 true
     *  - 已迁移过的用户:标志为 true → 跳过
     *  - 新装用户:JSON 文件不存在,[QuickNoteStore.migrateToRoom] 返回 0,仍置 true
     *
     * 幂等性:[QuickNoteDao.upsert] 用 OnConflictStrategy.REPLACE,即使标志丢失
     * 重复调用也不会产生重复记录。但为避免每次启动都遍历 JSON 文件,仍用标志保证只执行一次。
     *
     * 迁移完成后不删除 JSON 文件,作为本地备份保留(用户可手动清理)。
     */
    private suspend fun migrateQuickNotesIfNeeded() {
        val prefs = getSharedPreferences("muse_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_QUICK_NOTES_MIGRATED, false)) {
            return
        }
        val count = quickNoteStore.migrateToRoom(quickNoteDao)
        prefs.edit().putBoolean(KEY_QUICK_NOTES_MIGRATED, true).apply()
        Logger.i("MuseApp", "快速记录迁移完成: $count 条记录已导入 Room")
    }

    /**
     * Phase 11.1.6: 全局 Coil ImageLoader 工厂。
     *
     * 注册 [SvgDecoder] 和 [GifDecoder] 后,所有 [coil.compose.AsyncImage] 调用
     * 都能正确解码 SVG 矢量图与 GIF 动图(依赖已引入 coil-svg / coil-gif,
     * 但解码器必须通过 ImageLoader.components 注册才会生效)。
     *
     * - SVG: 用于 Markdown 内嵌矢量图 / 远程助手图标(若 future 支持)
     * - GIF: 用于动图表情 / 动态贴纸(minSdk 26 ≥ api19,GifDecoder 可用;
     *   api28+ 系统会自动走 ImageDecoderDecoder,性能更优)
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            add(SvgDecoder.Factory())
            // v1.112 (F3): GIF 动图解码器 — 根据API级别选择最优实现。
            // - API 28+ (Android 9+):用 ImageDecoderDecoder(基于 ImageDecoder API),
            //   性能更好,内存占用更低,且 Movie 在部分 OEM ROM(OPPO/MIUI 高版本)上渲染异常,
            //   导致 GIF 只显示第一帧变静态图。
            // - API < 28:用 GifDecoder(基于 Movie),兼容旧设备。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }
        // v0.36 性能优化:限制内存缓存为可用内存 25%,避免大图OOM;添加磁盘缓存减少重复下载。
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(this.cacheDir.resolve("image_cache"))
                .maxSizeBytes(256L * 1024 * 1024) // 256 MB
                .build()
        }
        .crossfade(true)
        .build()

    companion object {
        /** v1.0.17: 快速记录迁移标志的 SharedPreferences key(文件 muse_migration)。 */
        private const val KEY_QUICK_NOTES_MIGRATED = "quick_notes_migrated"
    }
}
