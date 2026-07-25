package io.zer0.muse.tools

import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.notification.MuseNotificationManager
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * HanaAgent 工具注册器（移植自 openhanako 工具系统）。
 *
 * 将所有从 HanaAgent 移植的工具注册到现有 ToolRegistry：
 *  - pin_memory / unpin_memory（Phase 1C）
 *  - recall_experience / record_experience（Phase 3B）
 *  - search_memory（Phase 1 v6）
 *  - todo_write（Phase 4A）
 *  - show_card（Phase 4B）
 *  - notify（Phase 4C）
 *  - current_status（Phase 4D）
 *  - subagent_task（Phase 5A / v1.202: launch+reply+close 三件套）
 */
class HanaAgentToolsRegistrar(
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
        toolRegistry.register(SearchMemoryTool.toolDef()) { args ->
            SearchMemoryTool.execute(args, factStore)
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

    }
}
