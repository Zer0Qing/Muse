package io.zer0.muse

import io.zer0.muse.ui.ChatViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

/**
 * P2-3 拆域：ToolOrchestrator / 对话树快照 / ChatViewModel 注册独立模块。
 */
val appChatModule = module {

    // Phase 8.2 / 8.4 / 8.5 / 8.6 / 8.7 / 8.8 / 9.1: ChatViewModel 注入 20 个依�?
    // v0.30-a: 新增 systemPromptAssembler
    // v1.43: 新增 chatGenerationManager / artifactRepository / appContext
    // (chat/settings/ticker/session/image/doc/tool/assistant/webSearch/lorebook/quickMsg/promptInj/ocr/tts/skillRepo/skillExec/folder/notification/assembler/generation/artifacts/context/audit/sessionPermission)
    // v1.92: 改为 single �?应用级单�?切页/切路由不销�?生成不中断�?
    // �?viewModel{} 绑定�?NavBackStackEntry,�?CHAT_DETAIL 返回�?onCleared �?
    // 流式内容 update 到已销�?ViewModel �?_state,新实例看不到 �?感知"中断"�?
    // 改为 single{} + koinInject() 后所有页面共享同一实例,生成继续更新同一 _state�?
    // B2-04: 统一 ToolOrchestrator 单例(accessor/taskCardCoordinator 由 runLoop 调用方传入)
    single {
        io.zer0.muse.tools.ToolOrchestrator(
            toolRegistry = get(),
            skillRepository = get(),
            skillExecutor = get(),
            assistantRepository = get(),
            sessionRepository = get(),
            context = androidContext(),
            hookRegistry = get(),
            auditLogger = get(),
            // F-08: Agent Run 收据内存账本
            agentRunTracker = get(),
            // v1.x: 会话级浏览器注册表(与 UI 胶囊共享同一实例)
            browserManagerRegistry = get(),
        )
    }

    // F-08: Agent Run 收据内存账本(每次工具执行写入,供调试/审计展示)
    single { io.zer0.muse.data.audit.AgentRunTracker() }

    // P0 对话树选择快照存储(分支选择跨重启恢复)
    single { io.zer0.muse.data.chat.ConversationTreeSnapshotStore(androidContext()) }

    single {
        ChatViewModel(
            get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(), get(),
            get(), get(), get(), get(), get(), get(), get(), get(), get(), get(),
            get(), get(),
            // v1.202: deferredResultStore + subagentThreadStore
            get(), get(),
            // v1.x: ConversationSessionManager(会话级引用计数 + idle 清理)
            get(),
            // P1-1: HookRegistry(注入 ToolOrchestrator + 消息处理 Hook)
            get(),
            // v1.0.52 P2-3: MemoryAutoSaveScheduler(AI 记忆自动保存)
            get(),
            // B0-08: MilestoneChecker(里程碑触发)
            get(),
            // B2-04: ToolOrchestrator(Koin 单例)
            get(),
            // B2-04: ToolApprovalRouter(子代理审批桥接)
            get(),
            // P0 对话树选择快照存储
            treeSnapshotStore = get(),
            // v1.x: 会话级浏览器实例注册表
            browserManagerRegistry = get(),
            // v1.x: 工具审批策略存储(与 AppToolModule 单例一致,避免 DataStore 双实例)
            toolConfigStore = get(),
            // MCP 注册表:首条消息前等待助手绑定的 server 完成 tools/list
            mcpRegistry = get(),
        )
    }
}
