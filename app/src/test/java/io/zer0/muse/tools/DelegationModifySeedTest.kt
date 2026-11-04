package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「修改后继续」编辑框该预填什么（P4 协作语义）。
 *
 * 缺陷原样：编辑框从空串开始，而委派任务往往是一整段多行文本。
 * 用户想改一个词，得先把手底下那段重打一遍，这个选项实际上不好用。
 *
 * 两处暂停点语义不同：委派开始前要改的是任务本身，子 agent 跑完之后要改的是中间结果
 * （消费方拿它当最终结果文本）。预填取错一头，用户按下去改的是别的东西。
 */
class DelegationModifySeedTest {

    private fun request(
        taskDescription: String = "",
        intermediateResult: String? = null,
    ) = DelegationPauseManager.PauseRequest(
        requestId = "req-1",
        taskId = "task-1",
        taskTitle = "整理会议纪要",
        taskDescription = taskDescription,
        targetType = "assistant",
        targetName = "档案员",
        reason = "即将把任务交给子 agent",
        intermediateResult = intermediateResult,
    )

    @Test
    fun `委派开始前预填的是任务原文`() {
        val task = "把 10 月 27 日到 11 月 2 日的会议纪要按日期合并，重复段落只留一条。"

        assertEquals(task, delegationModifySeed(request(taskDescription = task)))
    }

    @Test
    fun `有中间结果时预填的是结果不是任务`() {
        val seed = delegationModifySeed(
            request(
                taskDescription = "整理会议纪要",
                intermediateResult = "已合并 6 份，第三段和第五段重复。",
            ),
        )

        assertEquals("已合并 6 份，第三段和第五段重复。", seed)
    }

    @Test
    fun `中间结果是空白时退回任务原文`() {
        val task = "整理会议纪要"

        assertEquals(task, delegationModifySeed(request(taskDescription = task, intermediateResult = "   ")))
    }

    @Test
    fun `中间结果是空串时退回任务原文 预填不会是空白`() {
        val task = "整理会议纪要"

        val seed = delegationModifySeed(request(taskDescription = task, intermediateResult = ""))

        assertEquals("中间结果是空串（不是缺失），该用任务原文", task, seed)
        assertFalse("预填成空白会让确认按钮一直按不动: '$seed'", seed.isBlank())
    }

    @Test
    fun `任务与中间结果都没有内容时给空串 由界面把按钮按住`() {
        // 这种暂停点本来就不该出现（无任务又无结果），但预填不能瞎造内容：
        // 给空串，界面据此把按钮按住、并且看得出来是按住的状态
        val seed = delegationModifySeed(request(taskDescription = "", intermediateResult = null))

        assertTrue("两处都空应给空串: '$seed'", seed.isEmpty())
    }
}
