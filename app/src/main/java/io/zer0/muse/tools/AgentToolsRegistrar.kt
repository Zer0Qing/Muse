package io.zer0.muse.tools

import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.notification.MuseNotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Agent 工具注册器（实现自既有工具系统）。
 *
 * 将所有从 既有工具系统实现的工具注册到现有 ToolRegistry：
 *  - pin_memory / unpin_memory（Phase 1C）
 *  - recall_experience / record_experience（Phase 3B）
 *  - search_memory（Phase 1 v6）
 *  - todo_write（Phase 4A）
 *  - show_card（Phase 4B）
 *  - notify（Phase 4C）
 *  - current_status（Phase 4D）
 *  - subagent_task（Phase 5A / v1.202: launch+reply+close 三件套）
 *  - subagent_run（v1.0.52 P2-1: 同步阻塞式独立子 agent,完整工具循环 + XML 协议）
 */
class AgentToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val pinnedMemoryStore: PinnedMemoryStore,
    private val experienceRepository: ExperienceRepository,
    private val factStore: FactStore,
    private val notificationManager: MuseNotificationManager,
    private val context: Context,
    // v1.202: SubagentTool 所需依赖 — 委派执行 / 线程管理 / 异步结果回灌 / 后台 Scope
    private val skillExecutor: SkillExecutor,
    private val subagentThreadStore: SubagentThreadStore,
    private val deferredResultStore: DeferredResultStore,
    private val appScope: CoroutineScope,
    // v1.0.52 P2-1: SubagentRunSkill 所需 — 同步阻塞式独立子 agent 运行器
    private val subagentRunner: SubagentRunner,
) {
    init { registerAll() }

    fun registerAll() {
        // Phase 1C：置顶记忆
        toolRegistry.register(PinMemoryTool.toolDef()) { args ->
            PinMemoryTool.execute(args, pinnedMemoryStore)
        }
        toolRegistry.register(UnpinMemoryTool.toolDef()) { args ->
            UnpinMemoryTool.execute(args, pinnedMemoryStore)
        }

        // Phase 3B：经验工具
        toolRegistry.register(RecallExperienceTool.toolDef()) { args ->
            RecallExperienceTool.execute(args, experienceRepository)
        }
        toolRegistry.register(RecordExperienceTool.toolDef()) { args ->
            RecordExperienceTool.execute(args, experienceRepository)
        }

        // Phase 1 v6：长期记忆搜索
        toolRegistry.registerWithContext(SearchMemoryTool.toolDef()) { args, executionContext ->
            SearchMemoryTool.execute(args, factStore, executionContext)
        }

        // Phase 4A：待办
        toolRegistry.register(TodoTool.toolDef()) { args ->
            TodoTool.execute(args)
        }

        // Phase 4B：展示卡片
        toolRegistry.register(ShowCardTool.toolDef()) { args ->
            ShowCardTool.execute(args)
        }

        // Phase 4C：通知
        toolRegistry.register(NotifyTool.toolDef()) { args ->
            NotifyTool.execute(args, notificationManager)
        }

        // Phase 4D：当前状态
        toolRegistry.register(CurrentStatusTool.toolDef()) { args ->
            CurrentStatusTool.execute(args, context)
        }

        // v1.202: 子 agent 任务工具(launch / reply / close 三件套 + status / cancel / list)
        // 之前漏注册导致 subagent_task 工具永远不可用,此处补齐
        toolRegistry.register(SubagentTool.toolDef()) { args ->
            SubagentTool.execute(
                args = args,
                skillExecutor = skillExecutor,
                subagentThreadStore = subagentThreadStore,
                deferredResultStore = deferredResultStore,
                appScope = appScope,
            )
        }

        // v1.0.52 P2-1: 同步阻塞式独立子 agent(完整工具循环 + maxToolCalls 限制 + XML 协议)
        // 与 subagent_task(非阻塞异步)互补:适合短调研任务,主 agent 等待并收到结构化 XML
        toolRegistry.register(SubagentRunSkill.toolDef()) { args ->
            SubagentRunSkill.execute(args, subagentRunner)
        }

        // v1.0.53 Phase 1: subagent_close — 主动关闭子 agent 线程(既有实现 subagent_close)
        // 主 agent 用此工具释放不再需要的线程;子 agent 内部禁止调用(SubagentRunner RECURSIVE_FORBIDDEN_TOOLS)
        toolRegistry.register(SubagentCloseTool.toolDef()) { args ->
            SubagentCloseTool.execute(args, subagentThreadStore)
        }

    }
}
