package io.zer0.muse

import io.zer0.common.resultOf
import io.zer0.ai.aiModule
import io.zer0.ai.ProviderConfigStore
import io.zer0.memory.memoryModule
import io.zer0.memory.llm.MemoryLlmClient
import io.zer0.memory.ticker.MemoryTicker
import io.zer0.muse.backup.BackupService
import io.zer0.muse.data.MemoryLlmClientImpl
import io.zer0.muse.data.ProxyConfig
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantDao
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.audit.AuditLogger
import io.zer0.muse.data.preset.PresetProviders
import io.zer0.muse.data.lorebook.LorebookDao
import io.zer0.muse.data.lorebook.LorebookRepository
import io.zer0.muse.data.promptinjection.PromptInjectionDao
import io.zer0.muse.data.promptinjection.PromptInjectionRepository
import io.zer0.muse.data.quickmsg.QuickMessageDao
import io.zer0.muse.data.quickmsg.QuickMessageRepository
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.doc.DocumentParser
import io.zer0.muse.tools.SessionPermissionStore
import io.zer0.muse.tools.ToolConfigStore
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.ui.ChatViewModel
import io.zer0.muse.ui.MemoryViewModel
import io.zer0.muse.ui.groupchat.GroupChatViewModel
import io.zer0.muse.ui.stats.StatsViewModel
import io.zer0.muse.web.CompositeWebSearchService
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchService
import io.zer0.muse.web.createWebSearchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * app 模块�?Koin 装配�?
 *
 * 注册顺序约定:
 *  1. appModule: SettingsRepository / ProviderConfigStore / MemoryLlmClient / AppScope /
 *                MuseDb / SessionRepository / OkHttpClient / DocumentParser / ToolRegistry / BackupService 等基础组件
 *  2. aiModule: ChatService + ImageService(依赖 ProviderConfigStore + OkHttpClient)
 *  3. memoryModule: Room + 核心服务 + MemoryTicker(依赖 MemoryLlmClient + AppScope)
 *
 * [SettingsRepository] 同时注册为自身和 [ProviderConfigStore] 实现,
 * 这样 ai 模块�?ChatService / ImageService 能通过接口注入�?
 * [ChatViewModel] �?viewModel DSL 注册,UI �?koinViewModel() 取�?
 */
val appModule = module {
    single { SettingsRepository(androidContext(), get()) }
    single<ProviderConfigStore> { get<SettingsRepository>() }
    single<MemoryLlmClient> { MemoryLlmClientImpl(get(), get(), androidContext()) }
    // v12: LLM 事实去重判定器 — 算法层无法确定的同实体模糊候选交给大模型判断
    single<io.zer0.memory.fact.FactDedupJudge> { io.zer0.muse.data.LlmFactDedupJudge(get()) }

    // 应用�?CoroutineScope: memory ticker 等后台任务用
    single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.IO) }

    // Phase 5: 统一 OkHttpClient(ai 模块�?ChatService/ImageService 复用)
    // Phase 8.5 修复:�?qualifier �?Web 搜索 client 区分,避免后者覆盖前者导致图片生成超�?
    // v1.39: �?@Volatile 缓存而非 runBlocking,消除主线�?ANR
    single(named("chat")) {
        val settings = get<SettingsRepository>()
        val proxyConfig = settings.proxyConfigCache
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            // v1.114 修复: 思考模型(如 Claude 3.5 thinking)首字延迟可能超过 2 分钟,
            //   readTimeout 120s 会导致思考阶段未输出即超时;此处为 300s(5分钟),足够长思考。
            // C-02: 代码与注释统一(此前注释写 300s 但代码是 120s)。
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .applyProxy(proxyConfig)
            .build()
    }

    // Phase 5: app �?Room 数据�?会话 + 消息持久�?+ Assistant)
    single { MuseDb.get(androidContext()) }
    single { get<MuseDb>().sessionDao() }
    single { get<MuseDb>().messageDao() }
    single { get<MuseDb>().artifactDao() }  // v1.43: 会话产物
    single { get<MuseDb>().assistantDao() }  // Phase 8.2
    single { get<MuseDb>().lorebookDao() }  // Phase 8.5
    single { get<MuseDb>().worldBookDao() }  // P1-2: Worldbook 动态世界书
    single { get<MuseDb>().quickMessageDao() }  // Phase 8.5
    single { get<MuseDb>().promptInjectionDao() }  // Phase 8.5
    single { get<MuseDb>().skillDao() }  // Phase 8.8
    single { get<MuseDb>().folderDao() }  // Phase 9.1 (M13)
    single { get<MuseDb>().scheduledTaskDao() }  // 定时任务
    single { get<MuseDb>().knowledgeDocDao() }  // 知识�?
    single { get<MuseDb>().knowledgeChunkDao() }  // v1.54: 知识库分�?RAG)
    single { get<MuseDb>().scheduledTaskExecutionDao() }  // P1-7: 定时任务执行历史
    single { get<MuseDb>().groupChatDao() }  // v1.30: 群聊
    single { get<MuseDb>().groupChatMessageDao() }  // v1.30: 群聊消息
    single { get<MuseDb>().groupChatMemoryDao() }  // v2.x: 群聊记忆隔离(独立 fact store)
    single { get<MuseDb>().experienceDao() }  // v1.98
    single { get<MuseDb>().milestoneDao() }  // Phase 2 2B: milestone
    single { get<MuseDb>().agentMessageDao() }  // 既有工具系统 port: agent DM
    single { get<MuseDb>().auditLogDao() }  // P2-4: 审计日志
    single { get<MuseDb>().quickNoteDao() }  // v1.0.17: 快速记录
    // v1.134 P1-1/P1-2: 孤儿组件接入所需的 DAO(AutoBackupHelper / StatsCacheManager 依赖)
    single { get<MuseDb>().autoBackupLogDao() }  // 自动备份日志
    single { get<MuseDb>().statsCacheDao() }  // 统计缓存
    single { get<MuseDb>().integrityLogDao() }  // P3-3: 数据库完整性日志
    single { get<MuseDb>().translateHistoryDao() }  // v1.0.17: 翻译历史
    single { get<MuseDb>().momentDao() }
    // v1.0.74: AI 日记本
    single { get<MuseDb>().diaryDao() }
    single { get<MuseDb>().patrolLogDao() }
    single { io.zer0.muse.data.diary.DiaryRepository(get()) }
    single { io.zer0.muse.data.diary.DiaryGenerator(get(), get(), get()) }  // v1.0.72: AI 朋友圈
    single { AuditLogger(get()) }  // P2-4: 审计日志记录器
    // P3-3: 数据库完整性校验器(供 DebugScreen 触发检查 + 展示最近一次结果)
    single {
        io.zer0.muse.data.stats.IntegrityChecker(
            integrityLogDao = get(),
            db = get<MuseDb>().openHelper.writableDatabase,
        )
    }
    single { io.zer0.muse.data.milestone.MilestoneChecker(get(), get(), get()) }  // Phase 2 2B: milestone checker
    single { io.zer0.muse.data.experience.ExperienceRepository(get()) }  // v1.98
    single { io.zer0.muse.data.agentdm.AgentDmRepository(get()) }  // 既有工具系统 port: agent DM
    // v1.134 P1-2: 消息图片存储服务,负责 base64 ↔ 文件路径转换,
    // 让大图片落盘到 filesDir/muse_images/,DB 只存路径,避免 messages 表行体积膨胀
    single {
        io.zer0.muse.data.session.MessageImageStore(
            storageDir = java.io.File(androidContext().filesDir, "muse_images"),
        )
    }
    single { SessionRepository(get(), get(), get(), androidContext(), get(), get(), get(), get()) }  // +MuseDb: 跨表事务(H-SESS1)
    single { io.zer0.muse.data.artifact.ArtifactRepository(get()) }  // v1.43: 会话产物仓库
    single { AssistantRepository(get(), androidContext(), get()) }  // Phase 8.2 + v1.0.51: 注入 SettingsRepository 用于 locale
    single { LorebookRepository(get()) }  // Phase 8.5
    single { io.zer0.muse.worldbook.WorldBookRepository(get()) }  // P1-2: Worldbook 动态世界书
    single { QuickMessageRepository(get()) }  // Phase 8.5
    single { PromptInjectionRepository(get(), androidContext()) }  // Phase 8.5
    single { io.zer0.muse.data.skill.SkillRepository(get()) }  // Phase 8.8
    single { io.zer0.muse.data.session.FolderRepository(get(), get(), get(), androidContext()) }  // Phase 9.1 (M13) +MuseDb: deleteFolder 事务(M-SESS8)
    single { io.zer0.muse.data.groupchat.GroupChatRepository(get(), get(), get(), get()) }  // v1.30: 群聊仓库(�?MuseDb 用于跨表事务)
    // v2.x: 群聊记忆隔离仓库(独立 fact store,不污染主记忆)
    single { io.zer0.muse.data.groupchat.GroupChatMemoryRepository(get()) }
    // v1.0.72: AI 朋友圈
    single { io.zer0.muse.data.moment.MomentRepository(get(), get()) }
    single { io.zer0.muse.data.moment.MomentGenerator(get(), get(), get()) }
    single { io.zer0.muse.schedule.MomentScheduler(get(), get(), get(), get(), get()) }
    // v1.95: 表情包库仓库(文件存储,不碰 MuseDb)
    single { io.zer0.muse.data.sticker.StickerLibraryRepository(androidContext()) }

    // v1.120: 开源许可数据加载器(�?assets/licenses/manifest.json 读取依赖清单)
    single { io.zer0.muse.license.LicenseRepository(androidContext()) }

    // PresetProviders 预设供应商
    single { io.zer0.muse.data.preset.ModelCatalogStore(androidContext()) }
    single { PresetProviders(androidContext(), get()) }

    // P2-11: OAuth 凭证隔离 — 独立加密 SP(Keystore AES-256-GCM),
    // 与普通 API Key(SettingsRepository.providers Flow)物理隔离,
    // 仅 OAuthManager + UI(撤销访问)访问
    single { io.zer0.muse.auth.SecureCredentialStore(androidContext()) }

    // P2-10: Provider 插件注册中心(JSON 配置驱动的自定义供应商)
    // v1.134 P1-3: 注入 filesDir/muse_plugins/ 作为持久化目录,App 重启后自动恢复
    single {
        io.zer0.ai.plugin.ProviderPluginRegistry(
            storageDir = java.io.File(androidContext().filesDir, "muse_plugins"),
        )
    }

    // v0.23: 定时任务执行�?后台轮询,�?60s 检查到期任务并执行:�?AI + 写会�?+ 通知)
    // H-SC1: executionDao 已移�?执行历史+next_run_at 通过 ScheduledTaskDao.@Transaction 原子写入
    // 真正执行改�?注入 ChatService / SessionRepository / AssistantRepository(�?ProactiveMessageRunner)
    single { io.zer0.muse.schedule.ScheduledTaskRunner(get(), get(), get(), get(), androidContext(), get(), get(), get(), get()) }

    // 主动消息调度(陪伴助手定时主动给用户发消息 + 弹通知)
    // 依赖顺序:SettingsRepository / ChatService(ai 模块) / SessionRepository / AssistantRepository /
    //         MuseNotificationManager / ProactiveScoreEngine / ExperienceRepository / MilestoneDao /
    //         LorebookRepository / FactStore(memory 模块) / UserActivityProfile / Context / AppScope 等运行时依赖
    // v2.0 重构:接入 ExperienceRepository / MilestoneDao / LorebookRepository / FactStore,
    //           用于巡检上下文构造(5.3/5.10)与新记忆/里程碑/经验差量检测
    // v2.1: 接入 UserActivityProfile,用活跃度/对话连续性/情绪三因子自适应调度替换随机偏移
    single { io.zer0.muse.schedule.UserActivityProfile(androidContext()) }
    single { io.zer0.muse.schedule.ProactiveMessageRunner(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), androidContext(), get(), get(), get(), get()) }

    // v1.x: 问候语个性化提醒生成器(LLM 生成,失败回退规则版)
    single { io.zer0.muse.schedule.GreetingHintGenerator(get()) }

    // v1.30: 群聊调度(用户发消息后串行触发各 Agent 轮转发言)
    // v1.111: 接 appScope/appContext/chatGenerationManager,群聊轮转运行于 appScope,切页/后台不中断
    // 改造 1: 接 SkillExecutor,群聊关联团队且有 workflow 时委托 TeamWorkflowExecutor
    // ActivityHub: 接 GroupChatActivityHub,轮转各阶段 upsert agent 状态(UI 实时展示活动 chip)
    // v1.202: 接 DelegationChainTracker,invokeAgent 中同步链路状态(主会话 UI 可见群聊执行过程)
    single { io.zer0.muse.ui.groupchat.GroupChatActivityHub() }
    // v2.x: 末尾追加 GroupChatMemoryRepository,用于群聊记忆隔离(agent 回复摘要写入独立 fact store)
    // v1.0.53: 追加 SystemPromptAssembler,用于在群聊 system prompt 中注入长期记忆和群聊记忆
    single { io.zer0.muse.schedule.GroupChatScheduler(get(), get(), get(), get(), get(), androidContext(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }

    // v1.43: 应用级聊天生成管理器(切页/后台保持生成不中断)
    single { io.zer0.muse.schedule.ChatGenerationManager(get(), androidContext()) }

    // v1.x: 会话级资源管理器(引用计数 + idle 清理),依赖应用级 appScope(Koin 注册的 CoroutineScope)
    single { io.zer0.muse.session.ConversationSessionManager(get()) }

    // v1.0.15: 网络状态监听器(StreamInterrupted 自动重连 + UI 网络状态显示依赖)
    single { io.zer0.muse.network.NetworkMonitor(androidContext()) }

    // 对话压缩器(分块并行 LLM 摘要 + 独立便宜模型,供 ContextCompressTransformer 使用)
    // 依赖 ChatService + SettingsRepository,通过 compressModelId 配置独立便宜模型
    single { io.zer0.muse.transformer.ConversationCompressor(get(), get()) }

    // v1.98: 云备份自动定时上传调度器(�?10 分钟检查是否到�?
    single { io.zer0.muse.schedule.CloudBackupScheduler(get(), get(), get(), get()) }

    // Phase 3 3E: 定时消息管理器
    single { io.zer0.muse.data.schedule.PendingMessageManager(androidContext()) }

    // Phase 4 4A: 账户系统管理器
    // v1.134 P1-3: 移除 CloudSyncManager(原 Phase 4 4B 孤儿组件,TODO 空实现,
    // 真正的云备份由 CloudBackupScheduler + BackupService.exportToCloud 承担)
    // Phase 4 4C: API 配额管理器

    // v1.134 P1-1: 自动备份助手(原 v1.107 孤儿组件,本次接入 Koin + WorkManager 调度)
    // 依赖 AutoBackupLogDao + Context + MessageDao,由 AutoBackupWorker 每日拉起
    single {
        io.zer0.muse.data.stats.AutoBackupHelper(
            autoBackupLogDao = get(),
            context = androidContext(),
            messageDao = get(),
        )
    }
    // v1.134 P1-2: 统计缓存管理器(原 v1.107 孤儿组件,本次接入 Koin + WorkManager 调度)
    // 依赖 StatsCacheDao + MessageDao + SessionDao,由 StatsCacheWorker 每日拉起
    single {
        io.zer0.muse.data.stats.StatsCacheManager(
            statsCacheDao = get(),
            messageDao = get(),
            sessionDao = get(),
        )
    }
    // Phase 4 4D: 主动消息评分引擎
    single { io.zer0.muse.data.proactive.ProactiveScoreEngine() }

    // v1.0.47 P9: 移除 McpExtensionRegistry(原 Phase 5 5E 孤儿组件,三个扩展均为 stub,
    // isAvailable=false 永不启用,execute 永远返回 Error,无任何外部调用方)

    // Phase 6 6E: 本地分析追踪器
    single { io.zer0.muse.data.analytics.LocalAnalyticsTracker(androidContext()) }

    // 既有工具系统实现:会话文件管理器
    single { io.zer0.muse.data.session.SessionFileManager(androidContext()) }
    // 既有工具系统实现:基于文件的体验存储
    single { io.zer0.muse.data.experience.ExperienceStore(androidContext()) }
    // 既有工具系统实现:工具注册器(注册 pin/experience/search_memory/todo/card/notify/status 工具)
    // v1.202: 注入 SkillExecutor / SubagentThreadStore / DeferredResultStore / appScope,
    //         供 SubagentTool(launch/reply/close 三件套)使用
    single {
        io.zer0.muse.tools.AgentToolsRegistrar(
            toolRegistry = get(),
            pinnedMemoryStore = get(),
            experienceRepository = get(),
            factStore = get(),
            settings = get(),
            notificationManager = get(),
            context = androidContext(),
            skillExecutor = get(),
            subagentThreadStore = get(),
            deferredResultStore = get(),
            appScope = get(),
            subagentRunner = get(),
        )
    }
    // v1.0.52 P2-1: Passive Subagent 运行器(同步阻塞式独立子 agent,完整工具循环)
    // B2-04: 子代理审批路由(主会话注册 delegate,子代理复用同一审批链路)
    single { io.zer0.muse.tools.ToolApprovalRouter() }

    // v1.0.53 Phase 1: 加 threadStore(持久化续接)+ concurrencyLimiter(全局并发限流)
    // v1.0.53 Phase 4: 加 toolConfigStore(deny_on_prompt 审批策略)
    single {
        io.zer0.muse.tools.SubagentRunner(
            chatService = get(),
            toolRegistry = get(),
            threadStore = get(),
            concurrencyLimiter = get(),
            toolConfigStore = get(),
            toolApprovalRouter = get(),
        )
    }

    // P2-7: 工作区目录管理器(根目录 /data/data/io.zer0.muse/files/workspace/,
    // 提供路径安全校验的 listDir/readFile/writeFile/delete/mkdir/move/copy)
    single { io.zer0.muse.workspace.WorkspaceManager(androidContext()) }
    // P2-7: 工作区工具注册器(把 workspace_list/read/write/delete/mkdir/move 注册到 ToolRegistry)
    // 依赖 ToolRegistry + WorkspaceManager,init 块自动完成注册
    single { io.zer0.muse.tools.WorkspaceToolsRegistrar(get(), get()) }
    // v1.0.47 P2: 文件与链接工具注册器(read_file/create_download/parse_link)
    single {
        io.zer0.muse.tools.FileToolsRegistrar(
            get(),
            androidContext(),
            io.zer0.muse.workspace.WorkspaceManager(androidContext()).rootDir,
        )
    }
    // v1.0.52 P2-4: PDF 视觉解析(PdfRenderer + 4 路并发 + 视觉模型 OCR)
    // 依赖 ChatService + ProviderConfigStore(检查视觉模型可用性)+ Context
    single<io.zer0.muse.tools.VisionOcrClient> {
        io.zer0.muse.tools.DefaultVisionOcrClient(
            chatService = get(),
            configStore = get(),
        )
    }
    single {
        io.zer0.muse.tools.PdfVisionParser(
            context = androidContext(),
            ocrClient = get(),
        )
    }
    // P2-4: parse_pdf 工具注册器(init 块自动注册到 ToolRegistry)
    single {
        io.zer0.muse.tools.PdfVisionToolsRegistrar(
            toolRegistry = get(),
            parser = get(),
            context = androidContext(),
            workspaceRoot = io.zer0.muse.workspace.WorkspaceManager(androidContext()).rootDir,
        )
    }
    // v1.0.47 P2-6: Shell 沙箱工具注册器(execute_shell,仅 Agent Mode + 审批可用)
    single {
        io.zer0.muse.tools.ShellSandboxToolRegistrar(
            get(),
            androidContext().filesDir,
        )
    }

    // P3-3: 无障碍 + Shizuku + Root 三通道路由 — UI 自动化能力底座
    // AccessibilityClient: bindService 绑定无障碍服务,提供 UI 操作 AIDL 代理
    single { io.zer0.muse.tools.system.AccessibilityClient(androidContext()) }
    // ShizukuAuthorizer: Shizuku SDK 集成,以 shell 权限执行命令(无需 root)
    single { io.zer0.muse.tools.system.ShizukuAuthorizer(androidContext()) }
    // RootAuthorizer: root 检测 + su 执行(降级通道)
    single { io.zer0.muse.tools.system.RootAuthorizer() }
    // ShellExecutor: 三通道路由统一抽象(SHIZUKU 优先,ROOT 降级)
    single {
        io.zer0.muse.tools.system.ShellExecutor(
            shizukuAuthorizer = get(),
            rootAuthorizer = get(),
            accessibilityClient = get(),
        )
    }
    // 安装器(引导启用/安装)
    single { io.zer0.muse.tools.system.AccessibilityProviderInstaller(androidContext()) }
    single { io.zer0.muse.tools.system.ShizukuInstaller(androidContext()) }
    // P3-3: UI 工具注册器(init 块自动注册 10 个 ui_* 工具到 ToolRegistry,均为 HIGH 风险)
    single {
        io.zer0.muse.tools.defaultTool.UIToolsRegistrar(
            toolRegistry = get(),
            accessibilityClient = get(),
            context = androidContext(),
        )
    }

    // 工具注册器启动引导:强制实例化全部 lazy single,让 100+ 工具真正进入 ToolRegistry
    single {
        io.zer0.muse.tools.ToolRegistrarBootstrapper(
            toolRegistry = get(),
            encodingToolsRegistrar = get(),
            coreToolsRegistrar = get(),
            weatherToolsRegistrar = get(),
            clipboardToolsRegistrar = get(),
            networkTextToolsRegistrar = get(),
            reminderToolsRegistrar = get(),
            calendarToolsRegistrar = get(),
            phoneToolsRegistrar = get(),
            systemToolsRegistrar = get(),
            resourceToolsRegistrar = get(),
            quickNoteToolsRegistrar = get(),
            scheduledTaskToolsRegistrar = get(),
            translateToolsRegistrar = get(),
            ttsToolsRegistrar = get(),
            agentToolsRegistrar = get(),
            workspaceToolsRegistrar = get(),
            fileToolsRegistrar = get(),
            pdfVisionToolsRegistrar = get(),
            shellSandboxToolRegistrar = get(),
            uiToolsRegistrar = get(),
        )
    }

    // Phase 8.8: Skill 执行�?Kotlin 直实�?不用 QuickJS)
    // v0.24: 注入 WebSearchService / KnowledgeDocDao / SkillRepository 用于搜索�?+ install_skill
    // v0.46: 注入 ChatService / AssistantRepository 用于 delegate_agent(�?Agent 协作)
    // v1.30: 注入 GroupChatRepository 用于群聊工具(channel_reply / channel_pass / channel_read_context)
    // P1-3b 拆域: Skill 文件工具实现(被 SkillExecutor 委托调用)
    single { io.zer0.muse.tools.SkillFileToolsImpl(androidContext(), get(named("chat"))) }
    // P1-3b 拆域: Skill 媒体/JS/插件工具实现(被 SkillExecutor 委托调用)
    single { io.zer0.muse.tools.SkillMediaToolsImpl(
        androidContext(),
        get(),
        get(),
        { get<io.zer0.muse.data.SettingsRepository>().imageGenConfigFlow.first().let { cfg -> (if (cfg.providerId.isBlank()) null else runCatching { kotlinx.coroutines.runBlocking { get<io.zer0.muse.data.SettingsRepository>().getProviderById(cfg.providerId) } }.getOrNull()) to cfg.modelId } },
        get(),
    ) }
    // P1-3b 拆域: Skill 管理工具实现(被 SkillExecutor 委托调用)
    single { io.zer0.muse.tools.SkillManagementToolsImpl(androidContext(), get()) }
    // P1-3b 拆域: Skill 搜索/HTTP 工具实现(被 SkillExecutor 委托调用)
    single { io.zer0.muse.web.WebSearchCoordinator(get<WebSearchService>()) }
    single { io.zer0.muse.tools.SkillSearchToolsImpl(
        androidContext(),
        get(named("chat")),
        get<WebSearchService>(),
        get(),
        get(),
        { get<io.zer0.muse.data.SettingsRepository>().getRagConfig() },
        get<io.zer0.muse.web.WebSearchCoordinator>(),
        { get<io.zer0.muse.data.SettingsRepository>().webSearchConfigFlow.first().let { cfg ->
            io.zer0.muse.web.WebSearchPolicy(
                maxSearchesPerTurn = cfg.maxSearchesPerTurn.coerceIn(1, 5),
                maxResults = cfg.maxResults.coerceIn(1, 10),
            )
        } },
    ) }
    // P1-3e 拆域: Skill Agent 工作流/群聊工具实现(被 SkillExecutor 委托调用)
    single {
        io.zer0.muse.tools.SkillAgentToolsImpl(
            androidContext(),
            get(),
            get(),
            { runCatching { get<io.zer0.muse.schedule.GroupChatScheduler>() }.getOrNull() },
        )
    }

    // P1-3e 拆域: 翻译工具实现(被 SkillExecutor 委托调用)
    single { io.zer0.muse.tools.TranslateToolsImpl() }

    // P1-3e 拆域: delegateAgent 实现(被 SkillExecutor 委托调用)
    single {
        io.zer0.muse.tools.SkillDelegateAgentImpl(
            androidContext(),
            get(),
            get(),
            { get<io.zer0.muse.data.SettingsRepository>().multiAgentConfigCache },
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }

    single {
        io.zer0.muse.tools.SkillExecutor(
            androidContext(),
            get(named("chat")),
            webSearchService = get<WebSearchService>(),
            knowledgeDocDao = get(),
            skillRepository = get(),
            chatService = get(),
            assistantRepository = get(),
            groupChatRepository = get(),
            ragService = get(),
            ragConfigProvider = { get<io.zer0.muse.data.SettingsRepository>().getRagConfig() },
            stickerLibraryRepository = get(),
            imageService = get(),
            imageDrawConfigProvider = suspend {
                val settings = get<io.zer0.muse.data.SettingsRepository>()
                val cfg = settings.imageGenConfigFlow.first()
                val provider = if (cfg.providerId.isNotBlank()) {
                    kotlin.runCatching { settings.getProviderById(cfg.providerId) }.getOrNull()
                } else null
                val modelId = if (provider != null && cfg.modelId.isNotBlank()) cfg.modelId else null
                provider to modelId
            },
            multiAgentConfigProvider = { get<io.zer0.muse.data.SettingsRepository>().multiAgentConfigCache },
            llmAggregator = get(),
            pauseManager = get(),
            delegationChainTracker = get(),
            agentDmRepository = get(),
            // v1.202 改造 2: 非阻塞委派所需基础设施(已在下方注册为单例)
            deferredResultStore = get(),
            subagentThreadStore = get(),
            // v1.0.53: 子 agent 全局并发限流器(所有委派入口共享)
            agentConcurrencyLimiter = get(),
            // v1.0.53 Phase 2: 工作流断点恢复日志
            journal = get(),
            // v1.0.53 Phase 5: GroupChatScheduler 懒加载 provider(agent_phone 工具用)。
            // 用 lambda 延迟解析:SkillExecutor 在此注册(下方 single 块),GroupChatScheduler 在
            // line 192 注册(已先于 SkillExecutor 注册),但 lambda 体内 get() 在工具实际执行时才解析,
            // 此时两者均已初始化完成,避免循环依赖。
            // 用 runCatching 兜底:测试环境或 Koin 未启动时返回 null,agent_phone 工具降级为"未配置"。
            pluginManager = get(),
            fileTools = get(),
            searchTools = get(),
            managementTools = get(),
            mediaTools = get(),
            translateTools = get(),
            agentTools = get(),
            delegateTools = get(),
            groupChatSchedulerProvider = {
                runCatching { get<io.zer0.muse.schedule.GroupChatScheduler>() }.getOrNull()
            },
        )
    }


    // B6-01: 外部插件管理器(导入/卸载/启停/工具注册)
    single { io.zer0.muse.data.plugin.PluginManager(androidContext(), get()) }
    // v1.201: 委派暂停管理器(全局单例,ChatViewModel 与 SkillExecutor 共享)
    single { io.zer0.muse.tools.DelegationPauseManager() }
    // v1.201: 委派链路追踪器(全局单例,ChatViewModel 与 SkillExecutor 共享)
    single { io.zer0.muse.tools.DelegationChainTracker() }
    // v1.201: LLM 综合评审聚合器(用于 TeamWorkflow LLM_REVIEW 策略)
    single { io.zer0.muse.tools.LlmAggregator(get(), get()) }
    // v1.200: Agent 自动路由(根据任务文本 + 能力标签推荐最佳助手/团队)
    // v2.x: 注入 ChatService 支持 LLM 语义路由(开关默认关闭,见 MultiAgentConfig.llmRoutingEnabled)
    single { io.zer0.muse.tools.AgentRouter(get(), get(), get()) }
    // v1.0.53 Phase 1: 子 agent 线程账本(持久化版,替代旧 tools.SubagentThreadStore 内存版)
    //  - Room 表 subagent_threads(MIGRATION_58_59 创建):线程元数据 + 状态 + runCount
    //  - JSONL 子会话历史(filesDir/subagent_sessions/<threadId>.jsonl):每轮 LLM + 工具结果增量追加
    //  - 两条 subagent 路径共享:路径 A(SubagentTool + delegateAgent nonBlocking)+ 路径 B(SubagentRunner)
    single {
        io.zer0.muse.data.subagent.SubagentSessionStore(
            sessionsDir = java.io.File(androidContext().filesDir, "subagent_sessions").apply { mkdirs() },
            tokenEstimator = io.zer0.muse.util.TokenEstimator,
        )
    }
    single {
        io.zer0.muse.data.subagent.SubagentThreadStore(
            dao = get<io.zer0.muse.data.session.MuseDb>().subagentThreadDao(),
            sessionStore = get(),
        )
    }
    // v1.0.53: 子 agent 全局并发限流器(既有实现 workflow createLimiter;所有委派入口共享同一配额)
    single { io.zer0.muse.tools.AgentConcurrencyLimiter() }
    // v1.0.53 Phase 2: 工作流断点恢复日志(既有实现 lib/workflow/journal.ts)
    //  - 文件: filesDir/workflow_journals/<runId>.jsonl
    //  - TeamWorkflowExecutor resume 时命中缓存的节点秒回,首个未缓存节点起重跑
    single {
        io.zer0.muse.tools.WorkflowJournal(
            journalDir = java.io.File(androidContext().filesDir, "workflow_journals").apply { mkdirs() },
        )
    }
    // v1.202: 异步委派任务结果回灌(非阻塞委派核心基础设施,主 agent 立即返回 taskId)
    single { io.zer0.muse.tools.DeferredResultStore() }

    // Phase 5-E: 文档解析�?
    single { DocumentParser(get(named("chat"))) }




    // v0.30-a: 系统提示组装�?6 步工作流�?1 �?9 �?section 集中拼装)
    // v0.32 实验�?透传 getExperiments 闭包,�?设置 �?实验�?页的开�?
    //         (forceMoodBlock / selfReflection)真正影响 system prompt
    //         闭包每次都读 settings.experimentsCache(@Volatile,零阻�?,
    //         而不是在构造时缓存,保证用户改完设置页立即生�?按 memoryConfigCache 写法)�?
    // v1.25: 同时透传 getMultiAgentConfig,�?Agent 协作提示读取 settings.multiAgentConfigCache�?
    // v1.97: 透传 assistantRepository,�?delegate_agent 提示注入可用助手 id 清单�?




}

/**
 * 根据 [ProxyConfig] �?OkHttpClient.Builder 设置代理与代理认证�?
 *
 * 仅当启用开关打开�?host/port 有效时才生效;
 * HTTP 类型�?[Proxy.Type.HTTP],SOCKS/SOCKS5 �?[Proxy.Type.SOCKS]�?
 */
private fun OkHttpClient.Builder.applyProxy(config: ProxyConfig): OkHttpClient.Builder {
    if (!config.enabled || config.host.isBlank() || config.port <= 0) return this
    val address = InetSocketAddress.createUnresolved(config.host, config.port)
    val proxy = when (config.type.uppercase()) {
        "SOCKS", "SOCKS5" -> Proxy(Proxy.Type.SOCKS, address)
        else -> Proxy(Proxy.Type.HTTP, address)
    }
    proxy(proxy)
    if (config.username.isNotBlank() && config.password.isNotBlank()) {
        val credential = okhttp3.Credentials.basic(config.username, config.password)
        proxyAuthenticator { _, response ->
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
    }
    return this
}

/**
 * 应用启动时加载的全部 Koin 模块�?
 */
val allKoinModules = listOf(
    appModule,
    appChatModule,
    appInfraModule,
    appPromptModule,
    appToolModule,
    appMediaModule,
    appRagModule,
    appViewModelModule,
    aiModule,
    memoryModule,
)
