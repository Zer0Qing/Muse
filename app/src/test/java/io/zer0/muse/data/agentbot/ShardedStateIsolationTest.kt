package io.zer0.muse.data.agentbot

import org.junit.Assert.assertSame
import org.junit.Test

/**
 * V28 的可测代理：分片状态必须真正隔离重组。
 *
 * 计划要求"单轮状态更新不触发整页重建；帧时间与消息总数无线性关系"。
 * 帧时间测不了（需要设备），但**"哪些状态变了"可以测** ——
 * 如果任务看板变化会导致输入区对象重建，那整页重建就必然发生。
 *
 * 这里用引用相等性作为代理指标：值语义 data class 未变时，
 * StateFlow 的 `.value` 应当返回同一个实例（Kotlin 的 `MutableStateFlow`
 * 在 `update` 后若值相等会短路，不发射新实例）。
 */
class ShardedStateIsolationTest {

    @Test
    fun `task board changes do not touch the composer instance`() {
        val ui = AgentBotUiState()
        ui.setComposerText("正在打的字")
        val composerBefore = ui.composer.value

        ui.setTaskBoard(
            AgentBotUiState.TaskBoard(
                tasks = listOf(
                    AgentBotUiState.TaskRow(taskId = "t1", goal = "改错字", status = BotTaskStatus.RUNNING),
                    AgentBotUiState.TaskRow(taskId = "t2", goal = "查资料", status = BotTaskStatus.QUEUED),
                ),
            ),
        )

        assertSame(
            "看板变化不该让输入区产生新实例 —— 那是整页重建的来源",
            composerBefore,
            ui.composer.value,
        )
    }

    @Test
    fun `typing does not touch the task board instance`() {
        val ui = AgentBotUiState()
        val board = AgentBotUiState.TaskBoard(
            tasks = listOf(AgentBotUiState.TaskRow("t1", "改错字", BotTaskStatus.RUNNING)),
        )
        ui.setTaskBoard(board)
        val boardBefore = ui.taskBoard.value

        ui.setComposerText("再打几个字")

        assertSame(
            "打字不该让看板重建",
            boardBefore,
            ui.taskBoard.value,
        )
    }

    @Test
    fun `typing does not touch the transcript instance`() {
        val ui = AgentBotUiState()
        ui.appendPublished(
            AgentBotUiState.PublishedMessage(
                messageId = "m1",
                role = AgentBotUiState.Role.BOT,
                text = "我来处理",
                createdAt = 1L,
            ),
        )
        val transcriptBefore = ui.transcript.value

        ui.setComposerText("补充一句")

        assertSame("打字不该让对话流重建", transcriptBefore, ui.transcript.value)
    }

    @Test
    fun `busy state is independent of the task board`() {
        val ui = AgentBotUiState()
        val busyBefore = ui.busy.value

        ui.setTaskBoard(
            AgentBotUiState.TaskBoard(
                tasks = (1..20).map {
                    AgentBotUiState.TaskRow("t$it", "任务 $it", BotTaskStatus.RUNNING)
                },
            ),
        )

        assertSame("20 条任务变化也不该改动 busy 状态", busyBefore, ui.busy.value)
    }

    @Test
    fun `task count does not affect composer identity`() {
        // 代理验证"帧时间与消息总数无线性关系"：
        // 若输入区实例与任务数无关，则它不会因任务多而重组。
        val ui = AgentBotUiState()
        ui.setComposerText("固定内容")
        val baseline = ui.composer.value

        (1..50).forEach { i ->
            ui.setTaskBoard(
                AgentBotUiState.TaskBoard(
                    tasks = (1..i).map {
                        AgentBotUiState.TaskRow("t$it", "任务 $it", BotTaskStatus.RUNNING)
                    },
                ),
            )
        }

        assertSame("50 次看板更新后输入区仍是同一个实例", baseline, ui.composer.value)
    }
}
