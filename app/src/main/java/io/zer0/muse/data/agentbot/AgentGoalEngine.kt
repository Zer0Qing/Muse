package io.zer0.muse.data.agentbot

import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID
import kotlin.math.roundToInt

/** 目标里程碑的单条结构；[id] 在目标内唯一。 */
@Serializable
data class AgentGoalMilestone(
    val id: String = "",
    val title: String = "",
    val done: Boolean = false,
)

/**
 * 长期目标（Goal）引擎。
 *
 * 目标不绑定单次执行：它维护标题、说明与里程碑清单，进度由里程碑完成度算出。
 * 前台工具 [AgentStreamCoordinator.TOOL_UPSERT_GOAL] 与
 * [AgentStreamCoordinator.TOOL_UPDATE_GOAL_MILESTONE] 的落库都在这里收口，
 * 保证「聊着改」与里程碑勾选走同一套归一化、进度与状态规则。
 *
 * 状态流转规则：
 * - 新建目标默认 ACTIVE；
 * - 里程碑全部完成时自动转 ACHIEVED；
 * - 已达成目标被取消勾选时回到 ACTIVE；
 * - ABANDONED 是终态，不再被里程碑变化自动改写；
 * - 显式状态优先于自动推导，非法状态拒绝写入。
 */
class AgentGoalEngine(
    private val dao: AgentGoalDao,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun observeAll(): Flow<List<AgentGoalEntity>> = dao.observeAll()

    fun observeByBot(botId: String): Flow<List<AgentGoalEntity>> = dao.observeByBot(botId)

    fun observeBySession(sessionId: String): Flow<List<AgentGoalEntity>> = dao.observeBySession(sessionId)

    suspend fun getGoal(goalId: String): AgentGoalEntity? = dao.getById(goalId.trim())

    /**
     * 创建或更新一条目标。
     *
     * [milestones] 为 null 表示沿用旧清单（新建时为空清单）；传空列表表示清空。
     * [description] 为 null 表示沿用旧说明。[dueAt] 为 null 表示沿用旧期限。
     * 返回落库后的实体；标题为空或更新目标不存在时返回 null。
     */
    suspend fun upsertGoal(
        goalId: String = "",
        botId: String = "",
        originSessionId: String = "",
        title: String,
        description: String? = null,
        milestones: List<AgentGoalMilestone>? = null,
        progress: Int? = null,
        status: String = "",
        dueAt: Long? = null,
    ): AgentGoalEntity? {
        val safeGoalId = goalId.trim()
        val existing = if (safeGoalId.isBlank()) null else dao.getById(safeGoalId)
        if (safeGoalId.isNotBlank() && existing == null) {
            Logger.w(TAG, "upsert goal ignored: goal not found: " + safeGoalId)
            return null
        }
        val finalTitle = title.trim().ifBlank { existing?.title.orEmpty() }
        if (finalTitle.isBlank()) {
            Logger.w(TAG, "upsert goal rejected: blank title")
            return null
        }
        val ts = clock()
        val normalizedMilestones = milestones?.let { normalizeMilestones(it) }
        val finalMilestones = normalizedMilestones
            ?: existing?.let { parseMilestones(it.milestonesJson) }
            ?: emptyList()
        val computedProgress = when {
            progress != null -> progress
            normalizedMilestones != null -> computeProgress(finalMilestones)
            existing != null -> existing.progress
            else -> 0
        }
        val requestedStatus = status.trim().uppercase()
        val explicitStatus = AgentGoalStatus.isValid(requestedStatus)
        val baseStatus = when {
            explicitStatus -> requestedStatus
            existing != null -> existing.status
            else -> AgentGoalStatus.ACTIVE
        }
        val finalStatus = when {
            explicitStatus -> baseStatus
            milestones != null -> statusAfterMilestoneChange(baseStatus, finalMilestones)
            else -> baseStatus
        }
        val entity = AgentGoalEntity(
            goalId = existing?.goalId ?: safeGoalId.ifBlank { UUID.randomUUID().toString() },
            botId = botId.trim().ifBlank { existing?.botId.orEmpty() },
            originSessionId = originSessionId.trim().ifBlank { existing?.originSessionId.orEmpty() },
            title = finalTitle,
            description = description?.trim() ?: existing?.description.orEmpty(),
            milestonesJson = encodeMilestones(finalMilestones),
            progress = computedProgress.coerceIn(0, 100),
            status = finalStatus,
            dueAt = dueAt ?: existing?.dueAt ?: 0L,
            createdAt = existing?.createdAt ?: ts,
            updatedAt = ts,
        )
        dao.upsert(entity)
        return dao.getById(entity.goalId) ?: entity
    }

    /**
     * 更新一条里程碑：按 id 勾选/改名、追加新项或删除旧项。
     *
     * 只传 id 与 done 时会保留原标题；id 查不到但给了标题时按追加处理。
     * 修改后自动重算进度，并按 [statusAfterMilestoneChange] 推导目标状态。
     */
    suspend fun updateMilestone(
        goalId: String,
        milestoneId: String = "",
        title: String = "",
        done: Boolean? = null,
        remove: Boolean = false,
    ): AgentGoalEntity? {
        val safeGoalId = goalId.trim()
        if (safeGoalId.isBlank()) return null
        val goal = dao.getById(safeGoalId) ?: return null
        val milestones = parseMilestones(goal.milestonesJson).toMutableList()
        val safeMilestoneId = milestoneId.trim()
        val safeTitle = title.trim()
        val index = if (safeMilestoneId.isBlank()) {
            -1
        } else {
            milestones.indexOfFirst { it.id == safeMilestoneId }
        }
        when {
            remove && index >= 0 -> milestones.removeAt(index)
            remove && safeMilestoneId.isBlank() && safeTitle.isNotBlank() -> {
                milestones.removeAll { it.title == safeTitle }
            }
            index >= 0 -> {
                val current = milestones[index]
                milestones[index] = current.copy(
                    title = safeTitle.ifBlank { current.title },
                    done = done ?: current.done,
                )
            }
            safeTitle.isNotBlank() -> milestones += AgentGoalMilestone(title = safeTitle, done = done ?: false)
            else -> {
                Logger.w(TAG, "update milestone ignored: no matching milestone and blank title")
                return goal
            }
        }
        val normalized = normalizeMilestones(milestones)
        val ts = clock()
        val progress = computeProgress(normalized)
        val nextStatus = statusAfterMilestoneChange(goal.status, normalized)
        val changed = dao.updateMilestones(goal.goalId, encodeMilestones(normalized), progress, ts)
        if (changed <= 0) return null
        if (nextStatus != goal.status) {
            dao.setStatus(goal.goalId, nextStatus, ts)
        }
        return dao.getById(goal.goalId)
    }

    /** 显式设置状态；非法值或目标不存在时返回 false。 */
    suspend fun setStatus(goalId: String, status: String): Boolean {
        val safeGoalId = goalId.trim()
        val safeStatus = status.trim().uppercase()
        if (safeGoalId.isBlank() || !AgentGoalStatus.isValid(safeStatus)) {
            Logger.w(TAG, "set goal status rejected: goal=" + safeGoalId + " status=" + status)
            return false
        }
        if (dao.getById(safeGoalId) == null) return false
        return dao.setStatus(safeGoalId, safeStatus, clock()) > 0
    }

    suspend fun pause(goalId: String): Boolean = setStatus(goalId, AgentGoalStatus.PAUSED)

    suspend fun resume(goalId: String): Boolean = setStatus(goalId, AgentGoalStatus.ACTIVE)

    suspend fun markAchieved(goalId: String): Boolean = setStatus(goalId, AgentGoalStatus.ACHIEVED)

    suspend fun abandon(goalId: String): Boolean = setStatus(goalId, AgentGoalStatus.ABANDONED)

    suspend fun delete(goalId: String) {
        val safeGoalId = goalId.trim()
        if (safeGoalId.isNotBlank()) dao.deleteById(safeGoalId)
    }

    /** 读取目标当前快照，供「聊着改」注入模型上下文；目标不存在返回 null。 */
    suspend fun buildRefineContext(goalId: String): String? {
        val goal = dao.getById(goalId.trim()) ?: return null
        return buildRefineContext(goal)
    }

    /** 把目标完整 JSON 与局部更新约束组织成一段模型可读上下文。 */
    fun buildRefineContext(goal: AgentGoalEntity): String = buildString {
        appendLine("entity_kind: goal")
        appendLine("entity_id: " + goal.goalId)
        appendLine("current_json: " + json.encodeToString(AgentGoalEntity.serializer(), goal))
        appendLine(
            "update_rule: change only the parts the user mentions; keep every other field, " +
                "status, progress and milestone state unchanged; write the complete updated goal back with upsert_goal.",
        )
    }.trim()

    /** 解析里程碑 JSON；兼容对象数组、字符串数组与空值。 */
    fun parseMilestones(raw: String): List<AgentGoalMilestone> {
        val text = raw.trim()
        if (text.isBlank()) return emptyList()
        val decoded = runCatching { json.decodeFromString(milestoneListSerializer, text) }
            .onFailure { Logger.w(TAG, "parse milestones failed: " + it.message) }
            .getOrNull()
        if (decoded != null) return normalizeMilestones(decoded)
        val titles = runCatching { json.decodeFromString(stringListSerializer, text) }
            .onFailure { Logger.w(TAG, "parse milestone titles failed: " + it.message) }
            .getOrNull()
        return normalizeMilestones(titles.orEmpty().map { title -> AgentGoalMilestone(title = title) })
    }

    /** 去掉空标题、补齐缺失 id、消除重复 id；保持原顺序。 */
    fun normalizeMilestones(raw: List<AgentGoalMilestone>): List<AgentGoalMilestone> {
        val usedIds = mutableSetOf<String>()
        return raw.mapNotNull { milestone ->
            val safeTitle = milestone.title.trim()
            if (safeTitle.isBlank()) {
                null
            } else {
                var safeId = milestone.id.trim()
                if (safeId.isBlank() || !usedIds.add(safeId)) {
                    safeId = UUID.randomUUID().toString()
                    usedIds.add(safeId)
                }
                AgentGoalMilestone(id = safeId, title = safeTitle, done = milestone.done)
            }
        }
    }

    /** 进度 = 已完成里程碑数 / 总里程碑数；空清单为 0。 */
    fun computeProgress(milestones: List<AgentGoalMilestone>): Int {
        if (milestones.isEmpty()) return 0
        val done = milestones.count { it.done }
        return ((done * 100.0) / milestones.size).roundToInt().coerceIn(0, 100)
    }

    fun encodeMilestones(milestones: List<AgentGoalMilestone>): String =
        json.encodeToString(milestoneListSerializer, normalizeMilestones(milestones))

    private fun statusAfterMilestoneChange(
        currentStatus: String,
        milestones: List<AgentGoalMilestone>,
    ): String {
        if (currentStatus == AgentGoalStatus.ABANDONED) return AgentGoalStatus.ABANDONED
        val baseline = if (AgentGoalStatus.isValid(currentStatus)) currentStatus else AgentGoalStatus.ACTIVE
        if (milestones.isNotEmpty() && milestones.all { it.done }) return AgentGoalStatus.ACHIEVED
        if (baseline == AgentGoalStatus.ACHIEVED) return AgentGoalStatus.ACTIVE
        return baseline
    }

    private companion object {
        private const val TAG = "AgentGoalEngine"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
        private val milestoneListSerializer = ListSerializer(AgentGoalMilestone.serializer())
        private val stringListSerializer = ListSerializer(String.serializer())
    }
}
