package io.zer0.muse.tools

import io.zer0.common.Logger
import io.zer0.muse.computenode.ComputeTargetRouter
import io.zer0.muse.computenode.EnvEcosystem
import io.zer0.muse.computenode.EnvEnsureRequest
import io.zer0.muse.computenode.EnvEnsureResult
import io.zer0.muse.computenode.MuseNodeClient
import io.zer0.muse.computenode.MuseNodeException
import io.zer0.muse.data.agentbot.LiveMissionProgressBus
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 云端电脑依赖安装工具（remote_ensure_env）。
 *
 * pip / npm 安装落在节点自己的 venv 与 tools 目录，属于隔离环境内的可逆操作；
 * apt 会改动宿主机系统，因此不直接执行，而是返回带 APPROVAL_REQUIRED 的结构化结果，
 * 由宿主渲染流内审批卡，用户批准后再以 allowSystem=true 重试。
 */
class RemoteEnvEnsureTool(
    private val router: ComputeTargetRouter,
    private val client: MuseNodeClient,
    private val progressBus: LiveMissionProgressBus? = null,
) {

    fun toolDef(): ToolRegistry.ToolDef = ToolRegistry.ToolDef(
        name = NAME,
        description = "在云端电脑安装依赖：pip/npm 装到节点隔离环境，apt 为系统级安装需用户审批。" +
            "返回 JSON(ok/ecosystem/packages/exitCode/outputTail/error)。",
        parameters = mapOf(
            "ecosystem" to "依赖生态：pip、npm 或 apt",
            "packages" to "包名列表，用逗号或空格分隔（如 pandas,numpy）",
            "node_id" to "可选，指定云端节点 id；留空使用默认在线节点",
        ),
        required = setOf("ecosystem", "packages"),
        // 远程扩展工具不进入内置工具分类目录，避免与既有分类断言耦合。
        category = "local",
        riskLevel = ToolRiskLevel.HIGH,
    )

    suspend fun execute(args: Map<String, String>): ToolOutcome {
        val ecosystem = when (val raw = args["ecosystem"].orEmpty().trim().lowercase()) {
            "pip" -> EnvEcosystem.PIP
            "npm" -> EnvEcosystem.NPM
            "apt" -> EnvEcosystem.APT
            else -> return ToolOutcome.error("参数 ecosystem 必须是 pip、npm 或 apt（实际为 '$raw'）")
        }
        val packages = parsePackages(args["packages"].orEmpty())
        if (packages.isEmpty()) {
            return ToolOutcome.error("参数 packages 缺失或为空")
        }
        val node = try {
            resolveNode(args["node_id"].orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "resolve compute node failed: " + e.message, e)
            null
        } ?: return ToolOutcome.error("没有可用的在线云端电脑，请先在工作台接入并启动节点")

        if (ecosystem == EnvEcosystem.APT) {
            return approvalRequired(node, packages)
        }
        val result = try {
            client.envEnsure(
                node = node,
                request = EnvEnsureRequest(
                    ecosystem = ecosystem,
                    packages = packages,
                    allowSystem = false,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: MuseNodeException) {
            Logger.w(TAG, "env ensure failed: " + e.error.name + " " + e.message, e)
            return ToolOutcome.error(
                "云端依赖安装失败: " + e.message,
                mapOf("errorType" to "remote_env_failed", "code" to e.serverCode, "nodeId" to node.nodeId),
            )
        } catch (e: Exception) {
            Logger.w(TAG, "env ensure failed: " + e.message, e)
            return ToolOutcome.error(
                "云端依赖安装失败: " + (e.message ?: e.javaClass.simpleName),
                mapOf("errorType" to "remote_env_failed", "nodeId" to node.nodeId),
            )
        }
        return when (result) {
            is EnvEnsureResult.Installed -> {
                appendOutput(node, result.outputTail)
                ToolOutcome.ok(
                    buildJsonObject {
                        put("ok", true)
                        put("ecosystem", result.ecosystem.wireValue)
                        put("packages", buildJsonArray { packages.forEach { add(JsonPrimitive(it)) } })
                        put("exitCode", result.exitCode)
                        put("outputTail", result.outputTail)
                        put("nodeId", node.nodeId)
                    }.toString(),
                )
            }
            is EnvEnsureResult.ApprovalRequired -> approvalRequired(node, packages, result.message, ecosystem)
            is EnvEnsureResult.Failed -> ToolOutcome.error(
                buildJsonObject {
                    put("ok", false)
                    put("code", result.code)
                    put("message", result.message)
                    put("exitCode", result.exitCode)
                    put("outputTail", result.outputTail)
                    put("nodeId", node.nodeId)
                }.toString(),
                mapOf("errorType" to "remote_env_failed", "code" to result.code, "nodeId" to node.nodeId),
            )
        }
    }

    private suspend fun resolveNode(nodeId: String): RemoteComputeNodeEntity? {
        if (nodeId.isNotBlank()) {
            val node = router.nodeById(nodeId) ?: return null
            return node.takeIf { candidate -> router.onlineNodes().any { it.nodeId == candidate.nodeId } }
        }
        val target = router.resolve(ComputeTargetRouter.TARGET_CLOUD, io.zer0.muse.computenode.TaskCategory.GENERAL)
        val remote = target as? io.zer0.muse.computenode.ComputeTarget.RemoteNode
        return remote?.let { router.nodeFor(it) }
    }

    private fun approvalRequired(
        node: RemoteComputeNodeEntity,
        packages: List<String>,
        message: String = "需要用户审批系统级安装",
        ecosystem: EnvEcosystem = EnvEcosystem.APT,
    ): ToolOutcome = ToolOutcome.error(
        content = "ApprovalRequired: " + message + "（node=" + node.name.ifBlank { node.nodeId } +
            "，packages=" + packages.joinToString(",") + "）。用户批准后将以 allowSystem=true 重试。",
        details = mapOf(
            "errorType" to "approval_required",
            "code" to "APPROVAL_REQUIRED",
            "ecosystem" to ecosystem.wireValue,
            "packages" to packages,
            "nodeId" to node.nodeId,
            "allowSystem" to false,
        ),
    )

    private fun appendOutput(node: RemoteComputeNodeEntity, outputTail: String) {
        val bus = progressBus ?: return
        val source = "remote-env:" + node.name.ifBlank { node.nodeId }
        outputTail.split('\n').forEach { line ->
            val cleaned = line.trimEnd()
            if (cleaned.isNotBlank()) {
                bus.appendSandboxLog(taskId = "", source = source, text = cleaned)
            }
        }
    }

    private fun parsePackages(raw: String): List<String> =
        raw.split(PACKAGE_SPLIT_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    companion object {
        const val NAME = "remote_ensure_env"
        private const val TAG = "RemoteEnvEnsureTool"
        private val PACKAGE_SPLIT_REGEX = Regex("[,;\\s]+")
    }
}

/** remote_ensure_env 注册器。 */
class RemoteEnvEnsureToolRegistrar(
    private val toolRegistry: ToolRegistry,
    private val tool: RemoteEnvEnsureTool,
) {
    init {
        registerAll()
    }

    fun registerAll() {
        toolRegistry.registerOutcome(tool.toolDef()) { args ->
            tool.execute(args)
        }
    }
}
