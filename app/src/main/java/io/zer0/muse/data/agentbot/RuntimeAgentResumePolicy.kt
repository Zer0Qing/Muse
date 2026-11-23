package io.zer0.muse.data.agentbot

import io.zer0.muse.runtime.RuntimeMissing
import io.zer0.muse.tools.SubagentRunner

internal object RuntimeAgentResumePolicy {
    fun shouldPark(result: SubagentRunner.Result): Boolean = !result.success && result.error == RuntimeMissing.CODE

    fun shouldResume(task: BotTaskEntity, run: TaskRunEntity, runtimeReady: Boolean): Boolean =
        runtimeReady && task.status == BotTaskStatus.WAITING_RESOURCE &&
            run.status == TaskRunStatus.WAITING_RESOURCE && run.errorCode == RuntimeMissing.CODE
}
