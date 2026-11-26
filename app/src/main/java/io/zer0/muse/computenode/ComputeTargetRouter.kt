package io.zer0.muse.computenode

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.data.computenode.ComputeNodeStatus
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 任务类别。
 *
 * [prefersCloud] 只影响 target=auto：脚本类任务在存在在线云端电脑时优先上云，
 * 需要本机交互的任务保持在本机 proot，避免无谓的跨端往返。
 */
enum class TaskCategory(val prefersCloud: Boolean) {
    SHELL(false),
    PYTHON(true),
    NODE(true),
    BROWSER(true),
    INTERACTIVE(false),
    GENERAL(false),
}

/** 一次工具执行实际落到的算力位置。 */
sealed interface ComputeTarget {
    data object LocalProot : ComputeTarget

    data class RemoteNode(val nodeId: String) : ComputeTarget
}

/** 云端电脑的展示摘要，只包含在线节点。 */
data class CloudComputerSummary(
    val name: String,
    val os: String,
    val cpuPercent: Double,
    val memUsedMb: Double,
    val memTotalMb: Long,
    val packs: List<String>,
)

/**
 * 统一算力路由器。
 *
 * 显式 local 永远走本机；显式 cloud 必须命中一台在线节点，否则抛出
 * [MuseNodeError.NODE_NOT_CONFIGURED]；auto 按 [TaskCategory.prefersCloud]
 * 决定是否优先在线节点，没有可用节点时回退本机。
 */
class ComputeTargetRouter(
    private val nodeProvider: suspend () -> List<RemoteComputeNodeEntity>,
    private val snapshotProvider: () -> Map<String, ComputeNodeSnapshot> = { emptyMap() },
) {

    /** 解析本次执行应使用的算力目标。 */
    suspend fun resolve(requested: String, taskCategory: TaskCategory): ComputeTarget {
        val normalized = requested.trim().lowercase()
        return when (normalized) {
            TARGET_LOCAL -> ComputeTarget.LocalProot
            TARGET_CLOUD -> {
                val node = bestOnlineNode(onlineNodes())
                    ?: throw MuseNodeException(
                        error = MuseNodeError.NODE_NOT_CONFIGURED,
                        message = "no online compute node is available",
                    )
                ComputeTarget.RemoteNode(node.nodeId)
            }
            else -> {
                if (!taskCategory.prefersCloud) {
                    ComputeTarget.LocalProot
                } else {
                    val node = bestOnlineNode(onlineNodes())
                    if (node == null) ComputeTarget.LocalProot else ComputeTarget.RemoteNode(node.nodeId)
                }
            }
        }
    }

    /** 当前在线节点；心跳快照优先于数据库里的旧状态。 */
    suspend fun onlineNodes(): List<RemoteComputeNodeEntity> {
        val snapshots = snapshotProvider()
        return nodeProvider().filter { node ->
            val snapshotStatus = snapshots[node.nodeId]?.status
            if (snapshotStatus == null) {
                node.status == ComputeNodeStatus.ONLINE.wireValue
            } else {
                snapshotStatus == ComputeNodeStatus.ONLINE
            }
        }
    }

    /** 按 nodeId 取节点实体，用于把路由结果交给传输层。 */
    suspend fun nodeFor(target: ComputeTarget.RemoteNode): RemoteComputeNodeEntity =
        nodeById(target.nodeId) ?: throw MuseNodeException(
            error = MuseNodeError.NODE_NOT_CONFIGURED,
            message = "compute node not found: " + target.nodeId,
        )

    /** 按 nodeId 查询节点；不存在时返回 null。 */
    suspend fun nodeById(nodeId: String): RemoteComputeNodeEntity? =
        nodeProvider().firstOrNull { it.nodeId == nodeId }

    /** 在线节点的展示摘要；无在线节点时返回空列表。 */
    suspend fun summaries(): List<CloudComputerSummary> {
        val snapshots = snapshotProvider()
        return nodeProvider().mapNotNull { node ->
            val snapshot = snapshots[node.nodeId]
            val online = if (snapshot == null) {
                node.status == ComputeNodeStatus.ONLINE.wireValue
            } else {
                snapshot.status == ComputeNodeStatus.ONLINE
            }
            if (!online) {
                null
            } else {
                val health = snapshot?.health
                CloudComputerSummary(
                    name = node.name.ifBlank { node.nodeId },
                    os = parseOsLabel(node.osInfoJson),
                    cpuPercent = health?.cpuPercent ?: 0.0,
                    memUsedMb = health?.memUsedMb ?: 0.0,
                    memTotalMb = health?.memTotalMb ?: 0L,
                    packs = health?.packs.orEmpty(),
                )
            }
        }
    }

    private fun bestOnlineNode(nodes: List<RemoteComputeNodeEntity>): RemoteComputeNodeEntity? =
        nodes.sortedWith(
            compareByDescending<RemoteComputeNodeEntity> { it.isDefault }
                .thenByDescending { it.updatedAt }
                .thenBy { it.createdAt },
        ).firstOrNull()

    private fun parseOsLabel(raw: String): String {
        if (raw.isBlank()) return ""
        val obj = try {
            AppJson.parseToJsonElement(raw) as? JsonObject
        } catch (e: Exception) {
            Logger.w(TAG, "osInfoJson 无法解析: " + e.javaClass.simpleName)
            null
        }
        if (obj == null) return ""
        for (key in OS_KEYS) {
            val value = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return ""
    }

    companion object {
        const val TARGET_AUTO = "auto"
        const val TARGET_LOCAL = "local"
        const val TARGET_CLOUD = "cloud"

        private const val TAG = "ComputeTargetRouter"
        private val OS_KEYS = listOf("os", "prettyName", "pretty_name", "distro", "name", "version")
    }
}

/** 一次远端 exec 的聚合结果。 */
data class RemoteExecOutcome(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
) {
    /** 退出码为 0 视为成功。 */
    val succeeded: Boolean get() = exitCode == 0

    /** stdout 与 stderr 按到达顺序拼接后的可读文本。 */
    fun combinedOutput(): String = when {
        stdout.isBlank() -> stderr
        stderr.isBlank() -> stdout
        else -> stdout.trimEnd() + "\n" + stderr
    }
}

/**
 * 远端脚本执行入口。
 *
 * 把 [MuseNodeClient.exec] 的 WebSocket 帧聚合成一次性结果，并把每一行输出同步写入
 * [LiveMissionProgressBus]，让工作台的沙盒终端在任务运行期间就能看到云端输出。
 */
class RemoteComputeRuntime(
    private val router: ComputeTargetRouter,
    private val client: MuseNodeClient,
    private val progressBus: LiveMissionProgressBus? = null,
) {

    /** 解析算力目标。 */
    suspend fun resolve(requested: String, taskCategory: TaskCategory): ComputeTarget =
        router.resolve(requested, taskCategory)

    /** 在指定远端节点执行命令并收集全部输出。 */
    suspend fun execute(
        target: ComputeTarget.RemoteNode,
        request: ExecRequest,
        taskId: String = "",
    ): RemoteExecOutcome {
        val node = router.nodeFor(target)
        val stdout = StringBuilder()
        val stderr = StringBuilder()
        var exitCode = -1
        client.exec(node, request).collect { event ->
            when (event) {
                is ExecEvent.Output -> {
                    if (event.stream == ExecStream.STDERR) {
                        stderr.append(event.text)
                    } else {
                        stdout.append(event.text)
                    }
                    reportProgress(node, event, taskId)
                }
                is ExecEvent.Exit -> exitCode = event.exitCode
            }
        }
        return RemoteExecOutcome(
            exitCode = exitCode,
            stdout = stdout.toString(),
            stderr = stderr.toString(),
        )
    }

    private fun reportProgress(node: RemoteComputeNodeEntity, event: ExecEvent.Output, taskId: String) {
        val bus = progressBus ?: return
        val source = "remote:" + node.name.ifBlank { node.nodeId }
        event.text.split('\n').forEach { line ->
            val cleaned = line.trimEnd()
            if (cleaned.isNotBlank()) {
                bus.appendSandboxLog(
                    taskId = taskId,
                    source = source,
                    text = cleaned,
                    isError = event.stream == ExecStream.STDERR,
                )
            }
        }
    }
}
