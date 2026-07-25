package io.zer0.muse.ui.groupchat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 群聊成员活动状态(参考 openhanako lib/activity-hub.ts 的五态模型)。
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
 * 群聊活动状态管理器(参考 openhanako lib/activity-hub.ts)。
 *
 * 内存广播层 — GroupChatScheduler 在轮转各阶段 upsert 状态,
 * UI 通过 [activities] StateFlow 响应式订阅展示。
 *
 * 与 openhanako ActivityHub 的差异:
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
        val current = _activities.value.toMutableMap()
        val list = current[groupChatId]?.toMutableList() ?: mutableListOf()
        val idx = list.indexOfFirst { it.assistantId == assistantId }
        val activity = AgentActivity(assistantId, assistantName, status)
        if (idx >= 0) list[idx] = activity else list.add(activity)
        current[groupChatId] = list
        _activities.value = current
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
        val current = _activities.value.toMutableMap()
        current.remove(groupChatId)
        _activities.value = current
    }
}
