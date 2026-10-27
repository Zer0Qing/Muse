package io.zer0.muse.tools

import android.content.Context
import io.zer0.memory.fact.FactDbProvider
import io.zer0.memory.fact.FactStore
import io.zer0.memory.pin.PinnedMemoryStore
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.experience.ExperienceRepository
import io.zer0.muse.data.subagent.SubagentThreadStore
import io.zer0.muse.notification.MuseNotificationManager
import kotlinx.coroutines.CoroutineScope

/**
 * Agent 工具注册器（实现自既有工具系统）。
 *
 * 将所有从 既有工具系统实现的工具注册到现有 ToolRegistry：
 *  - pin_memory / unpin_memory（Phase 1C）
 *  - memory_config_get / memory_config_set（记忆系统参数读写）
 *  - recall_experience / record_experience（Phase 3B）
 *  - search_memory（Phase 1 v6）
 *  - save_memory / delete_memory（v2.x: 记忆库自管 — 显式写入/删除）
 *  - todo_write（Phase 4A）
 *  - show_card（Phase 4B）
 *  - notify（Phase 4C）
 *  - current_status（Phase 4D）
 *  - subagent_task（Phase 5A / v1.202: launch+reply+close 三件套）
 *  - subagent_run（v1.0.52 P2-1: 同步阻塞式独立子 agent,完整工具循环 + XML 协议）
 *  - find_tools（v2.x: 工具库按需检索 — 分层收窄的补全通道,命中即装载后续轮次可见）
 */
// 该注册器聚合多个工具域依赖，拆分需同步调整工具注册生命周期，暂保留局部豁免。
@Suppress("LongParameterList")
class AgentToolsRegistrar(
    private val toolRegistry: ToolRegistry,
    private val pinnedMemoryStore: PinnedMemoryStore,
    private val experienceRepository: ExperienceRepository,
    private val factStore: FactStore,
    /** 按执行上下文的 assistantId 选择对应 facts.db；为空时回退主 facts.db。 */
    private val factDbProvider: FactDbProvider? = null,
    private val settings: SettingsRepository,
    private val notificationManager: MuseNotificationManager,
    private val context: Context,
    // v1.202: SubagentTool 所需依赖 — 委派执行 / 线程管理 / 异步结果回灌 / 后台 Scope
    private val skillExecutor: SkillExecutor,
    private val subagentThreadStore: SubagentThreadStore,
    private val deferredResultStore: DeferredResultStore,
    private val appScope: CoroutineScope,
    // v1.0.52 P2-1: SubagentRunSkill 所需 — 同步阻塞式独立子 agent 运行器
    private val subagentRunner: SubagentRunner,
    // v2.0: 卡片数据绑定 — show_card 数据写入 + update_card_data 工具
    private val cardDataStore: io.zer0.muse.data.card.CardDataStore? = null,
    /**
     * v2.6.7: tool_status 需要读三层通道就绪状态。
     *
     * 为 null 时不注册该工具（测试环境或未装配自动化模块的宿主）——
     * 宁可不提供，也不能提供一个拿不到真实状态的假货。
     */
    private val automationManager: io.zer0.muse.automation.core.AutomationManager? = null,
    /** v2.6.9: 牵挂账本 —— note_thought 工具写入，主动消息调度的燃料。 */
    private val proactiveThoughtStore: io.zer0.muse.data.proactive.ProactiveThoughtRepository? = null,
) {
    init {
        registerAll()
    }

    /**
     * 注册牵挂记账工具。
     *
     * 账本未就绪时也给模型一个真实答复，而不是抛异常：
     * 工具列表是全局共享的，某个工具在特定宿主下不可用属于正常情况。
     * 单独抽成函数是因为 registerAll 的圈复杂度已经贴着上限，
     * 再往里塞一个分支就会爆。
     */
    private fun registerNoteThought() {
        toolRegistry.registerWithContext(NoteThoughtTool.toolDef()) { args, executionContext ->
            proactiveThoughtStore?.let { store ->
                NoteThoughtTool.execute(args, store, executionContext)
            } ?: "Error: 牵挂账本未就绪"
        }
    }

    fun registerAll() {
        // v2.6.7: 工具可用性总览 —— 一次告诉模型「现在真正能用什么」。
        // 助手自审：「206 个工具，而且没有地图」。它要的不是更多工具，是边界。
        automationManager?.let { manager ->
            toolRegistry.register(ToolStatusTool.toolDef()) { args ->
                ToolStatusTool.execute(args, toolRegistry, manager)
            }
        }

        // Phase 1C：置顶记忆
        toolRegistry.registerWithContext(PinMemoryTool.toolDef()) { args, executionContext ->
            PinMemoryTool.execute(args, pinnedMemoryStore, executionContext.assistantId)
        }
        // 记忆系统参数：此前只有内容读写，参数只能在设置页手拖。
        toolRegistry.registerWithContext(MemoryConfigTool.getDef()) { _, _ ->
            MemoryConfigTool.get(settings)
        }
        toolRegistry.registerWithContext(MemoryConfigTool.setDef()) { args, _ ->
            MemoryConfigTool.set(args, settings)
        }
        toolRegistry.registerWithContext(UnpinMemoryTool.toolDef()) { args, executionContext ->
            UnpinMemoryTool.execute(args, pinnedMemoryStore, executionContext.assistantId)
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
            val scopedStore = factDbProvider?.getFactStore(executionContext.assistantId ?: "default") ?: factStore
            SearchMemoryTool.execute(args, scopedStore, executionContext)
        }

        // v2.x: 记忆库显式写入/删除 — 助手自管记忆能力(作用域/空间走执行上下文,不可伪造)
        toolRegistry.registerWithContext(SaveMemoryTool.toolDef()) { args, executionContext ->
            val scopedStore = factDbProvider?.getFactStore(executionContext.assistantId ?: "default") ?: factStore
            SaveMemoryTool.execute(args, scopedStore, executionContext)
        }
        toolRegistry.registerWithContext(DeleteMemoryTool.toolDef()) { args, executionContext ->
            val scopedStore = factDbProvider?.getFactStore(executionContext.assistantId ?: "default") ?: factStore
            DeleteMemoryTool.execute(args, scopedStore, executionContext)
        }

        // v2.x 阶段3:工具库按需检索(命中即装载,后续轮次可见;装载 ≠ 放行)
        toolRegistry.registerWithContext(FindToolsTool.toolDef()) { args, executionContext ->
            FindToolsTool.execute(args, toolRegistry, executionContext)
        }

        // v2.x 终端一期:应用沙盒终端命令(工作目录 = 应用工作区)
        toolRegistry.register(TerminalExecTool.toolDef()) { args ->
            TerminalExecTool.execute(
                args,
                java.io.File(context.filesDir, "workspace").apply { mkdirs() },
                java.io.File(context.filesDir, TOOL_OUTPUTS_DIR),
            )
        }
        // v2.2.1 Termux 通道:完整 Linux 环境命令执行(需用户安装 Termux 并授权;未就绪时返回配置引导)
        toolRegistry.register(TermuxExecTool.toolDef()) { args ->
            TermuxExecTool.execute(args, context)
        }

        // 普通聊天里的“许愿”：把当前助手绑定到主动消息调度器。
        toolRegistry.registerWithContext(ProactiveWishTool.toolDef()) { args, executionContext ->
            ProactiveWishTool.execute(args, settings, executionContext)
        }

        // v2.6.9: 牵挂记账 —— 主动消息新机制的主要燃料。
        // 日常对话里顺手记下「隔几天再问会显得有人味」的事，
        // 没有它账本就是空的，主动消息退回成定时问候。
        // 单独抽成函数是因为 registerAll 已经贴着圈复杂度上限，
        // 再往里塞一个分支就会爆。
        registerNoteThought()

        // Phase 4A：待办
        // v2.6.7: 走持久化入口 —— 此前只写进程内存，切会话即丢。
        // v2.6.7 fix (AUDIT F5): 会话 id 改由宿主上下文注入 ——
        // 旧实现要模型自己传 session_id，而模型无从得知当前会话 UUID。
        toolRegistry.registerWithContext(TodoTool.toolDef()) { args, executionContext ->
            val sessionId = executionContext.sessionId?.trim().orEmpty()
            if (sessionId.isEmpty()) {
                "Error: current session is unavailable."
            } else {
                TodoTool.executePersistedForSession(sessionId, args)
            }
        }

        // v2.6.7：读用户设备上的文件（SAF，需用户点选）
        toolRegistry.register(ReadUserFileTool.toolDef()) { args ->
            ReadUserFileTool.execute(args)
        }

        // v2.6.7：用户偏好学习 —— 用户说「以后别…」时存成硬约束
        toolRegistry.register(UserPreferenceTool.toolDef()) { args ->
            val store =
                org.koin.core.context.GlobalContext.get()
                    .get<io.zer0.muse.data.PreferenceStore>()
            UserPreferenceTool.execute(args, store)
        }

        // Phase 4B：展示卡片（v2.0: 支持绑定结构化数据）
        toolRegistry.register(ShowCardTool.toolDef()) { args ->
            ShowCardTool.execute(args)
        }
        // v2.x: bounded chart/table output rendered directly in the tool result card.
        toolRegistry.register(RenderDataTool.toolDef()) { args ->
            RenderDataTool.execute(args)
        }

        // v2.0: 卡片数据绑定 — 更新已展示卡片的数据
        //
        // v2.6.13: 键改为**消息 id**。
        //
        // 旧参数说明写的是“show_card 返回的 card_id（形如 c_xxx）”，但渲染层注入给卡片的
        // window.muse.cardId 是消息 id —— 两个不同的 id 域。按旧说明传 c_xxx 时，写入落在
        // 一个渲染侧永远不会去查的 key 上，于是“数据更新了但卡片没变”，也从不报错。
        // 现在统一到消息 id：卡片自己读 window.muse.cardId 拿到的就是它，
        // 模型只需要知道“哪条消息上的卡片”，而不是一个它无法从 show_card 拿到的一次性 id。
        toolRegistry.register(
            ToolRegistry.ToolDef(
                name = "update_card_data",
                description = "更新已展示卡片所绑定的结构化数据。" +
                    "卡片会通过 window.muse.getData() 与 window.muse.data.onChange 重新读取。" +
                    "用卡片所在的消息定位它,不要用 show_card 的返回值。",
                parameters = mapOf(
                    "card_id" to "Required. The message id carrying the card (window.muse.cardId on the card side).",
                    "data" to "Required. JSON text with the new data.",
                ),
                required = setOf("card_id", "data"),
                category = "built-in",
                riskLevel = ToolRiskLevel.NORMAL,
            ),
        ) { args ->
            val cardId = args["card_id"]?.trim().orEmpty()
            val data = args["data"].orEmpty()
            when {
                cardId.isBlank() -> "Error: card_id is required."
                data.isBlank() -> "Error: data is required."
                cardDataStore == null -> "Error: card data store unavailable."
                else -> {
                    cardDataStore.put(cardId, data)
                    "Card '$cardId' data updated (${data.length} chars)."
                }
            }
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
