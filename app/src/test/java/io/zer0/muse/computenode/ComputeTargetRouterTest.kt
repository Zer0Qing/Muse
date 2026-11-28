package io.zer0.muse.computenode

import io.zer0.muse.data.computenode.ComputeNodeStatus
import io.zer0.muse.data.computenode.RemoteComputeNodeEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * ComputeTargetRouter 的路由契约。
 *
 * 覆盖三种显式目标、auto 的任务类别分流、心跳快照优先于数据库旧状态，
 * 以及云端摘要只包含在线节点。全部节点均为本地假数据。
 */
class ComputeTargetRouterTest {

    @Test
    fun localTargetStaysOnDeviceEvenWhenCloudIsOnline() = runBlocking {
        val router = routerWith(listOf(node("node-a", isDefault = true)))
        assertEquals(ComputeTarget.LocalProot, router.resolve("local", TaskCategory.SHELL))
    }

    @Test
    fun explicitCloudChoosesDefaultOnlineNode() = runBlocking {
        val router = routerWith(
            listOf(
                node("node-a", updatedAt = 10),
                node("node-b", isDefault = true, updatedAt = 20),
            ),
        )
        assertEquals(ComputeTarget.RemoteNode("node-b"), router.resolve("cloud", TaskCategory.PYTHON))
    }

    @Test
    fun explicitCloudFailsWithoutOnlineNode() {
        val router = routerWith(listOf(node("node-a", status = ComputeNodeStatus.OFFLINE)))
        val error = assertThrows(MuseNodeException::class.java) {
            runBlocking { router.resolve("cloud", TaskCategory.SHELL) }
        }
        assertEquals(MuseNodeError.NODE_NOT_CONFIGURED, error.error)
    }

    @Test
    fun autoUsesCloudForScriptCategories() = runBlocking {
        val router = routerWith(listOf(node("node-a", isDefault = true)))
        assertEquals(ComputeTarget.RemoteNode("node-a"), router.resolve("auto", TaskCategory.PYTHON))
        assertEquals(ComputeTarget.RemoteNode("node-a"), router.resolve("", TaskCategory.NODE))
        assertEquals(ComputeTarget.RemoteNode("node-a"), router.resolve("unknown-value", TaskCategory.PYTHON))
    }

    @Test
    fun autoKeepsShellOnDevice() = runBlocking {
        val router = routerWith(listOf(node("node-a", isDefault = true)))
        assertEquals(ComputeTarget.LocalProot, router.resolve("auto", TaskCategory.SHELL))
    }

    @Test
    fun autoFallsBackToLocalWhenNoCloudOnline() = runBlocking {
        val router = routerWith(listOf(node("node-a", status = ComputeNodeStatus.OFFLINE)))
        assertEquals(ComputeTarget.LocalProot, router.resolve("auto", TaskCategory.PYTHON))
    }

    @Test
    fun autoKeepsInteractiveTasksLocal() = runBlocking {
        val router = routerWith(listOf(node("node-a", isDefault = true)))
        assertEquals(ComputeTarget.LocalProot, router.resolve("auto", TaskCategory.INTERACTIVE))
        assertEquals(ComputeTarget.LocalProot, router.resolve("auto", TaskCategory.GENERAL))
    }

    @Test
    fun snapshotOfflineOverridesDatabaseOnline() = runBlocking {
        val router = ComputeTargetRouter(
            nodeProvider = { listOf(node("node-a", status = ComputeNodeStatus.ONLINE)) },
            snapshotProvider = { mapOf("node-a" to snapshot("node-a", ComputeNodeStatus.OFFLINE)) },
        )
        assertEquals(ComputeTarget.LocalProot, router.resolve("auto", TaskCategory.PYTHON))
    }

    @Test
    fun summariesOnlyContainOnlineNodesWithHealth() = runBlocking {
        val health = NodeHealth(
            nodeVersion = "v22.0.0",
            pythonVersion = "3.12.0",
            cpuPercent = 12.5,
            memUsedMb = 420.0,
            memTotalMb = 2048,
            packs = listOf("core", "browser"),
        )
        val router = ComputeTargetRouter(
            nodeProvider = {
                listOf(
                    node("node-a", name = "build-box", osInfoJson = """{"prettyName":"Ubuntu 24.04"}"""),
                    node("node-b", status = ComputeNodeStatus.OFFLINE, osInfoJson = """{"os":"debian-12"}"""),
                )
            },
            snapshotProvider = {
                mapOf(
                    "node-a" to ComputeNodeSnapshot("node-a", ComputeNodeStatus.ONLINE, health),
                    "node-b" to ComputeNodeSnapshot("node-b", ComputeNodeStatus.OFFLINE),
                )
            },
        )
        val summaries = router.summaries()
        assertEquals(1, summaries.size)
        val summary = summaries.first()
        assertEquals("build-box", summary.name)
        assertEquals("Ubuntu 24.04", summary.os)
        assertEquals(12.5, summary.cpuPercent, 0.001)
        assertEquals(420.0, summary.memUsedMb, 0.001)
        assertEquals(2048L, summary.memTotalMb)
        assertEquals(listOf("core", "browser"), summary.packs)
    }

    @Test
    fun nodeForResolvesRequestedNode() = runBlocking {
        val router = routerWith(listOf(node("node-a"), node("node-b")))
        assertEquals("node-b", router.nodeFor(ComputeTarget.RemoteNode("node-b")).nodeId)
    }

    @Test
    fun nodeForMissingNodeFails() {
        val router = routerWith(listOf(node("node-a")))
        val error = assertThrows(MuseNodeException::class.java) {
            runBlocking { router.nodeFor(ComputeTarget.RemoteNode("node-missing")) }
        }
        assertEquals(MuseNodeError.NODE_NOT_CONFIGURED, error.error)
    }

    private fun routerWith(nodes: List<RemoteComputeNodeEntity>): ComputeTargetRouter = ComputeTargetRouter(nodeProvider = { nodes })

    private fun node(
        id: String,
        status: ComputeNodeStatus = ComputeNodeStatus.ONLINE,
        isDefault: Boolean = false,
        name: String = id,
        osInfoJson: String = "{}",
        updatedAt: Long = 0L,
    ): RemoteComputeNodeEntity = RemoteComputeNodeEntity(
        nodeId = id,
        name = name,
        status = status.wireValue,
        isDefault = isDefault,
        osInfoJson = osInfoJson,
        updatedAt = updatedAt,
        createdAt = 0L,
    )

    private fun snapshot(id: String, status: ComputeNodeStatus): ComputeNodeSnapshot = ComputeNodeSnapshot(nodeId = id, status = status)
}
