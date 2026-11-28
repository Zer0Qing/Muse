package io.zer0.muse.data.agentbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V13「不覆盖用户改动」必须有真实的 baseHash。

 * 独立审查发现的 P0：两处 `recordStep` 都写 `baseHash = ""`，于是整条链上
 * 所有 baseHash 都是空，`StagedArtifactStore.apply` 的冲突检查被
 * `isNotBlank()` 短路跳过 —— **无条件覆盖用户文件**。
 *
 * 那正是 `StagedArtifactStore` 自己的 KDoc 声称要防的事。
 */
class BaseHashRecordedTest {

    private val scheduler: String by lazy {
        File("src/main/java/io/zer0/muse/data/agentbot/BotTaskScheduler.kt").readText()
    }

    @Test
    fun `no recordStep call hardcodes an empty base hash`() {
        assertFalse(
            "recordStep 不能写死空 baseHash —— 那会让冲突检查完全失效",
            scheduler.contains("baseHash = \"\","),
        )
    }

    @Test
    fun `both recordStep calls read the baseline from the target`() {
        val calls = Regex("baselineHashFor\\(").findAll(scheduler).count()
        assertTrue(
            "工具执行与审批都要按实际目标读取基线，实际 " + calls + " 处",
            calls >= 2 && scheduler.contains("baseHash = baselineHashFor(") &&
                scheduler.contains("toolTargetPath(toolName, argsJson"),
        )
    }

    @Test
    fun `baseline helper uses session scoped workspace snapshots`() {
        val at = scheduler.indexOf("private suspend fun baselineHashFor(")
        assertTrue("辅助函数必须存在", at > 0)
        val body = scheduler.substring(at, minOf(at + 1600, scheduler.length))
        assertTrue("必须按会话挂载读取目标", body.contains("manager.readSnapshotForWrite(targetRef, mountScope)"))
        assertTrue("读取失败不能伪装成空文件", body.contains("UNREADABLE_TARGET_HASH"))
        assertTrue("未注入挂载管理器时走工作区读口", body.contains("io.read(targetRef)"))
    }
}
