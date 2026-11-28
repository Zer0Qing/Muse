package io.zer0.muse.data.agentbot

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 暂存区必须真的被调度器使用，否则 V04/V13 只是纸面约束。
 *
 * 这类"组件写好了但没人调"的缺陷上一轮已经踩过一次（tick/sweepOverdue），
 * 这里用接线断言提前拦住。
 */
class StagingWiringTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    private val koin: String by lazy {
        File("src/main/java/io/zer0/muse/AppKoinModule.kt").readText()
    }

    @Test
    fun `scheduler accepts a staging store`() {
        assertTrue(
            "调度器必须能拿到暂存区，否则候选只能直接写目标",
            scheduler.contains("private val staging: StagedArtifactStore?"),
        )
    }

    @Test
    fun `workspace tool proposals stage structured content before reporting`() {
        val stageAt = scheduler.indexOf("stageWorkspaceArtifact = {")
        assertTrue("候选必须来自工具产生的结构化内容", stageAt > 0)
        assertTrue("摘要不能作为文件内容", !scheduler.contains("content = result.summary"))
        val eventAt = scheduler.indexOf("repository.finishExecution(", stageAt)
        assertTrue(
            "暂存要早于结果事件，否则审核阶段拿不到候选",
            stageAt < eventAt,
        )
        val repository = File("src/main/java/io/zer0/muse/data/agentbot/AgentBotRepository.kt").readText()
        assertTrue(repository.contains("kind = BotEventKind.TASK_RESULT_READY"))
    }

    @Test
    fun `staged id travels with the result event`() {
        assertTrue(
            "审核阶段要靠事件里的 staged id 找到候选（用常量而不是字面量，避免两处不一致）",
            scheduler.contains("BotResultApplier.STAGED_MARKER"),
        )
        assertTrue("结果事件应保留同一回合的全部候选", scheduler.contains("stagedIds.joinToString(\",\")"))
        assertTrue("后续写入也必须尝试暂存", !scheduler.contains("stagedArtifactRefs.isNotEmpty()"))
    }

    @Test
    fun `staged artifact keeps its actual content and base hashes`() {
        assertTrue(scheduler.contains("baseHash = StagedArtifactStore.hashSnapshot(baseContent)"))
        assertTrue(scheduler.contains("sourceRunId = run.runId"))
        assertTrue("模型摘要不再作为文件正文", !scheduler.contains("content = result.summary"))
    }

    @Test
    fun `staging is optional so pure JVM tests still run`() {
        assertTrue(scheduler.contains("staging == null"))
    }

    @Test
    fun `koin provides a real staging directory`() {
        assertTrue(
            "暂存区要落在 cacheDir 下，不能是内存态（进程一死候选全丢）",
            koin.contains("agentbot-staging"),
        )
        assertTrue(koin.contains("staging = get()"))
    }
}
