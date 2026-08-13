package io.zer0.muse.ui.groupchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 群聊成员活动状态(按 既有实现 lib/activity-hub.ts 的五态模型)。
 *
 * 用于 UI 实时展示每个 Agent 在群聊轮转中的当前阶段,让用户感知"谁在看消息/谁在回复/谁跳过了"。
 * 状态由 [GroupChatActivityHub] 统一管理,GroupChatScheduler 在轮转各阶段 upsert,
 * UI 通过 [GroupChatActivityHub.activities] StateFlow 响应式订阅。
 */
enum class AgentActivityStatus {
    /** 空闲(默认/最终回退状态,UI 不展示)。 */
    IDLE,

    /** 正在看消息(invokeAgent 开始,尚未流式输出)。 */
    VIEWING,

    /** 正在回复(LLM 流式输出中)。 */
    REPLYING,

    /** 选择不回复(返回 [PASS_MARKER] 标记,详见 GroupChatScheduler)。 */
    NO_REPLY,

    /** 出错(LLM 调用失败/超时)。 */
    ERROR,
}

/**
 * 单个 Agent 的活动状态记录。
 *
 * @property assistantId 助手 id
 * @property assistantName 助手显示名(UI chip 展示)
 * @property status 当前活动状态
 * @property updatedAt 状态更新时间戳(UI 可用于过期判断)
 */
data class AgentActivity(
    val assistantId: String,
    val assistantName: String,
    val status: AgentActivityStatus,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 群聊活动状态管理器(按 既有实现 lib/activity-hub.ts)。
 *
 * 内存广播层 — GroupChatScheduler 在轮转各阶段 upsert 状态,
 * UI 通过 [activities] StateFlow 响应式订阅展示。
 *
 * 与 既有实现 ActivityHub 的差异:
 *  - 此处只管理群聊维度(groupChatId → List<AgentActivity>),无 sessionPath 概念
 *  - 不做持久化背书(进程重启即清空,历史由消息表保留)
 *  - 状态机更简单:仅五态枚举,无 kind/summary/parentTaskId 等工作流字段
 *
 * 线程安全:[MutableStateFlow] 的 value 写入是原子的,updateStatus 可在任意线程调用。
 */
class GroupChatActivityHub {

    private val _activities = MutableStateFlow<Map<String, List<AgentActivity>>>(emptyMap())
    val activities: StateFlow<Map<String, List<AgentActivity>>> = _activities.asStateFlow()

    /**
     * 更新指定群聊中指定 Agent 的活动状态。
     *
     * 同一 agent 的旧状态会被覆盖;若 agent 不在列表中则追加。
     *
     * @param groupChatId 群聊 id
     * @param assistantId 助手 id
     * @param assistantName 助手显示名
     * @param status 新状态
     */
    fun updateStatus(
        groupChatId: String,
        assistantId: String,
        assistantName: String,
        status: AgentActivityStatus,
    ) {
        // 审计修复 (3.5): 用 MutableStateFlow.update 原子完成读-改-写,
        // 避免并发调用时各自基于旧快照 toMutableMap() 操作后互相覆盖。
        _activities.update { current ->
            val list = current[groupChatId]?.toMutableList() ?: mutableListOf()
            val idx = list.indexOfFirst { it.assistantId == assistantId }
            val activity = AgentActivity(assistantId, assistantName, status)
            if (idx >= 0) list[idx] = activity else list.add(activity)
            current + (groupChatId to list)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // v1.x: 群聊流式输出内容广播(生成中实时推给 UI,落库后清空)
    // ══════════════════════════════════════════════════════════════════

    private val _streamingContent = MutableStateFlow<Map<String, String>>(emptyMap())
    /** chatId → 正在流式生成的助手回复内容(空 map 表示无流式输出)。 */
    val streamingContent: StateFlow<Map<String, String>> = _streamingContent.asStateFlow()

    /** 更新某个群聊的流式输出内容。 */
    fun updateStreamingContent(chatId: String, content: String) {
        _streamingContent.update { it + (chatId to content) }
    }

    /** 群聊流式输出结束(落库后调用,清除 UI 临时内容)。 */
    fun clearStreamingContent(chatId: String) {
        _streamingContent.update { it - chatId }
    }

    /**
     * 取指定群聊的活动列表。
     *
     * 推荐 UI 直接订阅 [activities] 而非调用本方法(响应式优于查询式)。
     */
    fun getActivities(groupChatId: String): List<AgentActivity> {
        return _activities.value[groupChatId] ?: emptyList()
    }

    /**
     * 清空指定群聊的全部活动状态(群聊删除/退出时调用)。
     */
    fun clear(groupChatId: String) {
        // 审计修复 (3.5): update 原子移除,避免与 updateStatus 并发时丢失更新。
        _activities.update { it - groupChatId }
    }
}
