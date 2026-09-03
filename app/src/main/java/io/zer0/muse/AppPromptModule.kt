package io.zer0.muse

import io.zer0.muse.data.SettingsRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * P2-3 拆域：提示词模板、封面生成、Hook 注册表、SystemPromptAssembler 注册独立模块。
 */
val appPromptModule = module {

    // Phase 12: PromptTemplateLoader �?�?assets/prompt_templates/ 加载提示词模�?
    single { io.zer0.muse.transformer.PromptTemplateLoader(androidContext()) }

    // v1.0.53: 封面库 + AI 封面生成(Beautify 封面工作流)
    single { io.zer0.muse.data.cover.CoverLibraryRepository(androidContext()) }
    single {
        io.zer0.muse.tools.CoverGenerator(
            context = androidContext(),
            templateLoader = get(),
            chatService = get(),
            imageService = getOrNull(),
            coverLibraryRepository = get(),
            okHttpClient = get(named("chat")),
        )
    }

    // P1-1: Hook 注册表(全局单例,所有 Hook 通过此注册)
    single { io.zer0.muse.hook.HookRegistry() }

    single {
        val settings = get<SettingsRepository>()
        io.zer0.muse.transformer.SystemPromptAssembler(
            promptLoader = get(),
            context = androidContext(),
            settings = settings,
            memoryTicker = get(),
            toolRegistry = get(),
            skillRepository = get(),
            getExperiments = { settings.experimentsCache },
            getMultiAgentConfig = { settings.multiAgentConfigCache },
            assistantRepository = get(),
            // v1.98: 透传 experienceRepository,经验库开关开启时注入经验条目到 system prompt
            experienceRepository = get(),
            // v1.202: 透传 agentDmRepository,主助手构建 system prompt 时注入收件箱摘要
            agentDmRepository = get(),
            // v2.x: 透传 groupChatMemoryRepository,主助手构建 system prompt 时注入群聊记忆摘要
            // (用 <group_chat_memory> 标签与主记忆 <long_term_memory> 区分,不污染主记忆)
            groupChatMemoryRepository = get(),
            // v1.0.52: 透传 sessionRepository,主助手构建 system prompt 时注入 Recent Chats Reference
            // (用 <recent_chats> 标签包裹最近会话标题+预览,提供对话连续性上下文)
            sessionRepository = get(),
            // v12 (T2-2): 透传 factStore,主助手构建 system prompt 时按当前问题 FTS 召回相关记忆
            factStore = get(),
            // v1.0.86: 相关记忆按当前 Assistant 选择独立 facts.db
            factDbProvider = get(),
            // 审计修复 (S-03): 透传 pinnedMemoryStore,统一置顶记忆数据源
            // (此前注入侧读无人写入的 pinned_memories.json,置顶内容永不注入)
            pinnedMemoryStore = get(),
            // P1-1: 透传 hookRegistry,SystemPromptComposeHook 在 build 末尾调用
            hookRegistry = get(),
        )
    }
}