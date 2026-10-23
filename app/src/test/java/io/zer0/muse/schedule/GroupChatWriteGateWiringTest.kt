package io.zer0.muse.schedule

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class GroupChatWriteGateWiringTest {

    @Test
    fun `group chat generation checks restore gate before persisting user message`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/schedule/GroupChatScheduler.kt"),
                Path.of("app/src/main/java/io/zer0/muse/schedule/GroupChatScheduler.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("GroupChatScheduler.kt not found")

        val launchBody = source.substringAfter("fun launchRoundRobin")
            .substringBefore("suspend fun triggerAgentRoundRobin")
        assertTrue(launchBody.contains("ProcessWriteGate.restoring"))
        val triggerBody = source.substringAfter("suspend fun triggerAgentRoundRobin")
        assertTrue(triggerBody.contains("ProcessWriteGate.restoring"))
        assertTrue(
            source.windowed("ProcessWriteGate.restoring".length)
                .count { it == "ProcessWriteGate.restoring" } >= 6,
        )
    }

    @Test
    fun `internal workflow and agent write points recheck restore gate after model work`() {
        val source =
            listOf(
                Path.of("src/main/java/io/zer0/muse/schedule/GroupChatScheduler.kt"),
                Path.of("app/src/main/java/io/zer0/muse/schedule/GroupChatScheduler.kt"),
            )
                .firstOrNull(Files::exists)
                ?.let { Files.newBufferedReader(it).use { reader -> reader.readText() } }
                ?: error("GroupChatScheduler.kt not found")

        fun bodyOf(name: String, nextMarker: String): String = source.substringAfter(name).substringBefore(nextMarker)

        val workflowBody = bodyOf("private suspend fun executeWithWorkflow", "private suspend fun executeAutoDiscussion")
        val agentBody = bodyOf("private suspend fun invokeAgent(", "private suspend fun buildDebateMessages")
        val debateBody = bodyOf("private suspend fun invokeAgentForDebate(", "private suspend fun buildDebateMessages")
        val ledgerBody = bodyOf("private suspend fun saveLedger(", "private fun parseLedgerMemberIds")

        assertTrue(workflowBody.contains("restoreBlocksWrites"))
        assertTrue(agentBody.contains("restoreBlocksWrites"))
        assertTrue(debateBody.contains("restoreBlocksWrites"))
        assertTrue(ledgerBody.contains("restoreBlocksWrites"))
    }
}
