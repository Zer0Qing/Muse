package io.zer0.muse.data.subagent

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v1.0.53: 子 agent 线程持久化 Entity(参考开源实现 SubagentThreadStore)。
 *
 * 替代旧 tools/SubagentThreadStore.kt(内存版)。两条 subagent 路径共享:
 *  - 路径 A: SubagentTool + SkillExecutor.delegateAgent nonBlocking
 *  - 路径 B: SubagentRunSkill + SubagentRunner
 *
 * 线程生命周期:
 *  - 首次委派生成 threadId(与首次 run 的 requestId 相同)
 *  - 后续携带 threadId 续接(恢复子会话历史)
 *  - 主 agent 调用 subagent_close 关闭线程
 */
@Entity(
    tableName = "subagent_threads",
    indices = [
        Index(value = ["parentSessionId"]),
        Index(value = ["status"]),
    ],
)
data class SubagentThreadEntity(
    @PrimaryKey val threadId: String,
    /** 创建线程的主会话 id(路径 B 无父会话时用占位 "subagent_run")。 */
    val parentSessionId: String,
    /** 子会话 id(首次执行后回填,方案 B 中等于 threadId)。 */
    val childSessionId: String? = null,
    /** 子会话 JSONL 文件路径(用于续接恢复)。 */
    val childSessionPath: String,
    /** 子助手 id(续接时用它找模型配置)。 */
    val assistantId: String,
    /** 展示名,如 "research-1"。 */
    val label: String? = null,
    /** read|write(对齐参考实现 权限继承)。 */
    val access: String = "read",
    /** open|closed。 */
    val status: String = "open",
    /** 已执行次数。 */
    val runCount: Int = 0,
    /** 上次 run 状态: pending|resolved|failed|aborted。 */
    val lastRunStatus: String? = null,
    /** 上次执行总结(UI 展示用)。 */
    val lastSummary: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
