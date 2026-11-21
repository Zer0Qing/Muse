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
    fun `finished run stages its output before reporting`() {
        val stageAt = scheduler.indexOf("staging?.stage(");
        assertTrue("产出必须先落暂存区", stageAt > 0);
        val eventAt = scheduler.indexOf("BotEventKind.TASK_RESULT_READY", stageAt);
        assertTrue(
            "暂存要早于结果事件，否则审核阶段拿不到候选",
            stageAt < eventAt,
        )
    }

    @Test
    fun `staged id travels with the result event`() {
        assertTrue(
            "审核阶段要靠事件里的 staged id 找到候选（用常量而不是字面量，避免两处不一致）",
            scheduler.contains("BotResultApplier.STAGED_MARKER"),
        )
    }

    @Test
    fun `baseline hash comes from the step not the run`() {
        // 一个 run 可能碰多个文件，各步基线不同；run 上根本没有 baseHash 字段。
        assertTrue(scheduler.contains("repository.listSteps(run.runId)"));
    }

    @Test
    fun `staging is optional so pure JVM tests still run`() {
        assertTrue(scheduler.contains("staging?.stage("));
    }

    @Test
    fun `koin provides a real staging directory`() {
        assertTrue(
            "暂存区要落在 cacheDir 下，不能是内存态（进程一死候选全丢）",
            koin.contains("agentbot-staging"),
        )
        assertTrue(koin.contains("staging = get()"));
    }
}
