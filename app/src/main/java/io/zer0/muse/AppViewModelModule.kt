package io.zer0.muse

import android.app.Application
import io.zer0.muse.ui.MemoryViewModel
import io.zer0.muse.ui.groupchat.GroupChatViewModel
import io.zer0.muse.ui.memory.MemorySpaceViewModel
import io.zer0.muse.ui.stats.StatsViewModel
import io.zer0.muse.ui.translate.TranslateViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

/**
 * P2-3 拆域：ViewModel 注册独立模块，减少 AppKoinModule 单体体积。
 */
val appViewModelModule = module {

    // 阶段 6: MemoryViewModel 注入 memory 模块�?3 个核心服�?
    // v0.51: �?memoryTicker 用于读取 healthFlow 与裁剪后�?compiledMarkdown
    // v1.98: �?settings + experienceRepository 用于经验�?CRUD 与开关订�?
    viewModel {
        MemoryViewModel(
            application = androidContext() as Application,
            factStore = get(),
            summaryManager = get(),
            memoryCompiler = get(),
            memoryTicker = get(),
            settings = get(),
            experienceRepository = get(),
            assistantRepository = get(),
            spaceRepository = get(),
        )
    }

    // v1.0.52 P2-2: 记忆空间管理 ViewModel
    viewModel {
        io.zer0.muse.ui.memory.MemorySpaceViewModel(
            application = androidContext() as Application,
            spaceRepository = get(),
        )
    }

    // v0.46: 统计�?ViewModel(注入 MessageDao + SessionDao)
    // SessionDao.count() 用于总会话数(修复旧版 totalSessions 恒为 0 �?bug)
    // v0.47: 注入 SettingsRepository + AssistantRepository 用于反查模型/助手显示�?
    viewModel {
        StatsViewModel(
            application = androidContext() as Application,
            messageDao = get(),
            sessionDao = get(),
            settingsRepository = get(),
            assistantRepository = get(),
        )
    }

    // v1.97 gap8: 独立翻译 ViewModel(注入 ChatService + TtsManager,复用通用文本补全与朗读能力)
    // v1.0.17: 注入 TranslateHistoryDao,翻译历史持久化到 Room
    // v1.0.30 gap4.6: 注入 GlossaryStore,翻译时附加术语表指令
    viewModel {
        io.zer0.muse.ui.translate.TranslateViewModel(
            chatService = get(),
            ttsManager = get(),
            appContext = androidContext(),
            translateHistoryDao = get(),
            glossaryStore = get(),
        )
    }


    // Multi-Agent 工作流可视化编排已折叠进团队协作设置,WorkflowEditorViewModel 随不可达页面一并移除
    // v1.30: 群聊 ViewModel(注入 GroupChatRepository + Scheduler + AssistantRepo + Settings)
    // H-GC2 修复: 移除 appScope 参数,init 中 Flow 收集器改用 viewModelScope 自动取消
    // ActivityHub: 注入 GroupChatActivityHub,订阅其 activities 派生当前群聊活动列表到 UI
    viewModel {
        GroupChatViewModel(
            get(),
            get(),
            get(),
            get(),
            get(),
            androidContext(),
        )
    }

    // v1.0.17: 快速记录 ViewModel(注入 QuickNoteDao,Room 持久化 + 回收站)
    // v1.0.18: 增加 androidContext()(ReminderStore + AlarmManager 调度提醒)
    viewModel { io.zer0.muse.ui.quicknotes.QuickNotesViewModel(get(), androidContext()) }
    // P1-2: Worldbook 管理 ViewModel
    viewModel { io.zer0.muse.ui.worldbook.WorldBookViewModel(get(), androidContext()) }
}