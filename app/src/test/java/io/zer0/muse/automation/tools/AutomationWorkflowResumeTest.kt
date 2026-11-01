package io.zer0.muse.automation.tools

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.zer0.common.AppJson
import io.zer0.muse.automation.core.AutomationManager
import io.zer0.muse.automation.core.ScreenInfo
import io.zer0.muse.automation.core.UiNode
import io.zer0.muse.automation.executors.ShellExecutor
import io.zer0.muse.automation.vdisplay.VirtualDisplayClient
import io.zer0.muse.automation.vdisplay.VirtualDisplayServerManager
import io.zer0.muse.data.SecureKeyCipher
import io.zer0.muse.tools.NodeScriptTool
import io.zer0.muse.tools.WorkflowJournal
import io.zer0.muse.tools.script.SkillEngineResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class AutomationWorkflowResumeTest {
    @Test
    fun `completed deterministic step is reused on same run id`() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-resume").toFile()
        try {
            val journal = WorkflowJournal(directory)
            val workflow = AutomationWorkflow(manager = io.mockk.mockk(), journal = journal)
            val steps = listOf(AutomationWorkflowStep(action = "wait", durationMs = 50L))

            val first = workflow.run(steps, runId = "safe-run-1")
            val resumed = workflow.run(steps, runId = "safe-run-1")

            assertFalse(first.isError)
            assertFalse(resumed.isError)
            assertTrue(resumed.content.contains("已从工作流断点恢复"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `unknown in-flight action is not replayed without explicit retry`() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-unknown").toFile()
        try {
            val journal = WorkflowJournal(directory)
            val step = AutomationWorkflowStep(action = "wait", durationMs = 50L)
            val key = journal.computeKey(
                io.zer0.common.AppJson.encodeToString(AutomationWorkflowStep.serializer(), step),
                "automation_workflow:0",
            )
            journal.recordRequired(
                runId = "safe-run-2",
                nodeSeq = 0,
                key = key,
                result = "",
                status = "running",
                nodeKind = WorkflowJournal.NODE_KIND_TOOL_ONLY,
            )
            val workflow = AutomationWorkflow(manager = io.mockk.mockk(), journal = journal)

            val blocked = workflow.run(listOf(step), runId = "safe-run-2")
            val retried = workflow.run(listOf(step), runId = "safe-run-2", retryUnknown = true)

            assertTrue(blocked.isError)
            assertTrue(blocked.content.contains("结果可能未知"))
            assertFalse(retried.isError)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun concurrentCallsWithSameRunIdExecuteTheSideEffectOnlyOnce() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-concurrent").toFile()
        try {
            val manager = mockk<AutomationManager>()
            val secret = "same-run-input"
            var calls = 0
            coEvery { manager.inputText(secret) } coAnswers {
                calls += 1
                delay(100L)
                true
            }
            val workflow = AutomationWorkflow(manager, WorkflowJournal(directory))
            val steps = listOf(AutomationWorkflowStep(action = "input_text", text = secret))

            val results = listOf(
                async { workflow.run(steps, runId = "concurrent-run") },
                async { workflow.run(steps, runId = "concurrent-run") },
            ).awaitAll()

            assertTrue(results.all { !it.isError })
            assertEquals(1, calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun virtualScreenInputUsesValidatedDisplayScopedCommand() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-vdisplay").toFile()
        try {
            val manager = mockk<AutomationManager>(relaxed = true)
            val client = mockk<VirtualDisplayClient>(relaxed = true)
            val displayManager = mockk<VirtualDisplayServerManager>()
            every { displayManager.lastDisplayId } returns 42
            coEvery { displayManager.exec("input -d 42 tap 120 640") } returns ShellExecutor.ExecDetail(0, "")
            val workflow = AutomationWorkflow(manager, WorkflowJournal(directory), client, displayManager)

            val succeeded = workflow.run(
                listOf(AutomationWorkflowStep(action = "virtual_tap", x = 120, y = 640)),
                runId = "virtual-tap-run",
            )
            val unsafeTextStep = listOf(AutomationWorkflowStep(action = "virtual_text", text = "hello; reboot"))
            val rejected = workflow.run(unsafeTextStep, runId = "virtual-text-run")
            val rejectedRetry = workflow.run(unsafeTextStep, runId = "virtual-text-run")

            assertFalse(succeeded.isError)
            assertTrue(rejected.isError)
            assertTrue(rejectedRetry.isError)
            assertFalse(rejectedRetry.content.contains("结果可能未知"))
            coVerify(exactly = 1) { displayManager.exec("input -d 42 tap 120 640") }
            coVerify(exactly = 0) { displayManager.exec(match { it.contains("reboot") }) }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun virtualSemanticTapUsesDisplayScopedCoordinatesAndVerifiesWithoutPersistingScreenText() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-vdisplay-semantic").toFile()
        try {
            val journal = WorkflowJournal(directory)
            val manager = mockk<AutomationManager>()
            coEvery { manager.readScreenOnDisplay(42) } returnsMany listOf(
                ScreenInfo(
                    nodes = listOf(
                        UiNode(
                            text = "Delete account",
                            boundsLeft = 80,
                            boundsTop = 160,
                            boundsRight = 120,
                            boundsBottom = 240,
                            isClickable = true,
                        ),
                    ),
                    screenWidth = 720,
                    screenHeight = 1280,
                ),
                ScreenInfo(nodes = listOf(UiNode(text = "Account deleted")), screenWidth = 720, screenHeight = 1280),
            )
            val client = mockk<VirtualDisplayClient>(relaxed = true)
            val displayManager = mockk<VirtualDisplayServerManager>()
            every { displayManager.lastDisplayId } returns 42
            coEvery { displayManager.exec("input -d 42 tap 100 200") } returns ShellExecutor.ExecDetail(0, "")
            val workflow = AutomationWorkflow(manager, journal, client, displayManager)

            val outcome = workflow.run(
                listOf(AutomationWorkflowStep(action = "virtual_tap_text", text = "Delete account", verifyText = "Account deleted")),
                runId = "virtual-semantic-tap-run",
            )

            assertFalse(outcome.isError)
            assertTrue(outcome.content.contains("验证=true"))
            val persistedResult = journal.load("virtual-semantic-tap-run")[0]?.result.orEmpty()
            assertFalse(persistedResult.contains("Delete account"))
            assertFalse(persistedResult.contains("Account deleted"))
            coVerify(exactly = 1) { displayManager.exec("input -d 42 tap 100 200") }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun virtualSemanticTapScrollsWithinBoundAndUnknownVerificationDoesNotReplay() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-vdisplay-scroll").toFile()
        try {
            val journal = WorkflowJournal(directory)
            val manager = mockk<AutomationManager>()
            coEvery { manager.readScreenOnDisplay(42) } returnsMany listOf(
                ScreenInfo(screenWidth = 720, screenHeight = 1280),
                ScreenInfo(
                    nodes = listOf(UiNode(text = "Continue", boundsLeft = 100, boundsTop = 200, boundsRight = 180, boundsBottom = 260)),
                    screenWidth = 720,
                    screenHeight = 1280,
                ),
                ScreenInfo(nodes = listOf(UiNode(text = "Still loading")), screenWidth = 720, screenHeight = 1280),
            )
            val client = mockk<VirtualDisplayClient>(relaxed = true)
            val displayManager = mockk<VirtualDisplayServerManager>()
            every { displayManager.lastDisplayId } returns 42
            coEvery { displayManager.exec("input -d 42 swipe 360 1049 360 358 450") } returns ShellExecutor.ExecDetail(0, "")
            coEvery { displayManager.exec("input -d 42 tap 140 230") } returns ShellExecutor.ExecDetail(0, "")
            val workflow = AutomationWorkflow(manager, journal, client, displayManager)
            val steps = listOf(
                AutomationWorkflowStep(action = "virtual_tap_text", text = "Continue", verifyText = "Welcome", maxSwipes = 1),
            )

            val first = workflow.run(steps, runId = "virtual-semantic-scroll-run")
            val resume = workflow.run(steps, runId = "virtual-semantic-scroll-run")

            assertTrue(first.isError)
            assertTrue(first.isError)
            assertTrue(resume.isError)
            assertTrue(resume.content.contains("结果可能未知"))
            coVerify(exactly = 1) { displayManager.exec("input -d 42 swipe 360 1049 360 358 450") }
            coVerify(exactly = 1) { displayManager.exec("input -d 42 tap 140 230") }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nodeScriptStepReturnsCachedOutputButEncryptsItAtRest() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-node-script").toFile()
        try {
            val outputSecret = "private-script-output-42".repeat(4_000)
            val codeSecret = "console.log('private-script-code')"
            val cipher = TestWorkflowResultCipher()
            val journal = WorkflowJournal(directory, cipher)
            var calls = 0
            val workflow = AutomationWorkflow(
                manager = mockk(),
                journal = journal,
                nodeScriptExecutor = { code, timeoutMs ->
                    calls += 1
                    assertEquals(codeSecret, code)
                    assertEquals(30_000L, timeoutMs)
                    SkillEngineResult.Success(valueJson = "\"$outputSecret\"", consoleLogs = listOf("log-$outputSecret"))
                },
            )
            val steps = listOf(AutomationWorkflowStep(action = "node_script", code = codeSecret))

            val first = workflow.run(steps, runId = "node-script-run")
            val persisted = journal.pathOf("node-script-run").readText()
            val appendedSteps = steps + AutomationWorkflowStep(action = "wait", durationMs = 1L)
            val resumed = workflow.run(appendedSteps, runId = "node-script-run")

            assertFalse(first.isError)
            assertTrue(first.content.contains(outputSecret))
            assertFalse(persisted.contains(outputSecret))
            assertFalse(persisted.contains(codeSecret))
            assertTrue(persisted.contains("test_enc:"))
            assertTrue(resumed.content.contains(outputSecret))
            assertTrue(resumed.content.contains("安全断点恢复"))
            assertTrue(resumed.content.contains("wait"))
            assertEquals(1, calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun nodeScriptWorkflowResultKeepsTheCompleteValidJson() {
        val output = "x".repeat(100_000)
        val formatted = NodeScriptTool.formatResultJson(
            SkillEngineResult.Success(valueJson = "\"$output\"", consoleLogs = listOf("large output")),
        )

        assertTrue(AppJson.parseToJsonElement(formatted).toString().contains(output))
        assertFalse(formatted.contains("truncated"))
    }

    @Test
    fun unreadableEncryptedScriptResultBlocksReplay() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-node-script-corrupt").toFile()
        try {
            val writerJournal = WorkflowJournal(directory, TestWorkflowResultCipher())
            val step = AutomationWorkflowStep(action = "node_script", code = "touch side-effect")
            val key = writerJournal.computeKey(
                io.zer0.common.AppJson.encodeToString(AutomationWorkflowStep.serializer(), step),
                "automation_workflow:0",
            )
            writerJournal.recordRequired(
                "node-script-corrupt-run",
                0,
                key,
                AppJson.encodeToString(
                    AutomationWorkflowStepResult.serializer(),
                    AutomationWorkflowStepResult(0, "node_script", true, "secret-output"),
                ),
                "done",
                WorkflowJournal.NODE_KIND_SENSITIVE_TOOL,
            )
            val readerJournal = WorkflowJournal(directory, TestWorkflowResultCipher(rejectDecrypt = true))
            var calls = 0
            val workflow = AutomationWorkflow(
                manager = mockk(),
                journal = readerJournal,
                nodeScriptExecutor = { _, _ ->
                    calls += 1
                    SkillEngineResult.Success("null", emptyList())
                },
            )

            val resumed = workflow.run(listOf(step), runId = "node-script-corrupt-run")

            assertTrue(resumed.isError)
            assertTrue(resumed.content.contains("结果可能未知"))
            assertEquals(0, calls)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun screenReadIsNotPersistedAndIsRefreshedOnResume() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-read").toFile()
        try {
            val manager = mockk<AutomationManager>()
            coEvery { manager.readScreen() } returnsMany listOf(
                ScreenInfo(nodes = listOf(UiNode(text = "private-screen-one"))),
                ScreenInfo(nodes = listOf(UiNode(text = "fresh-screen-two"))),
            )
            val journal = WorkflowJournal(directory)
            val workflow = AutomationWorkflow(manager, journal)
            val step = AutomationWorkflowStep(action = "read")

            val first = workflow.run(listOf(step), runId = "read-run")
            val firstJournalResult = journal.load("read-run")[0]?.result.orEmpty()
            val resumed = workflow.run(listOf(step), runId = "read-run")
            val latestJournalResult = journal.load("read-run")[0]?.result.orEmpty()

            assertTrue(first.content.contains("private-screen-one"))
            assertTrue(resumed.content.contains("fresh-screen-two"))
            assertFalse(firstJournalResult.contains("private-screen-one"))
            assertFalse(latestJournalResult.contains("fresh-screen-two"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun journalGapInvalidatesAllDownstreamCachedSteps() = runBlocking {
        val directory = Files.createTempDirectory("workflow-journal-gap").toFile()
        try {
            val journal = WorkflowJournal(directory)
            journal.recordRequired("gap-run", 0, "key-0", "zero", "done", WorkflowJournal.NODE_KIND_TOOL_ONLY)
            journal.recordRequired("gap-run", 2, "key-2", "two", "done", WorkflowJournal.NODE_KIND_TOOL_ONLY)

            val resumed = journal.resume("gap-run", mapOf(0 to "key-0", 1 to "key-1", 2 to "key-2"))

            assertEquals(1, resumed.resumeFromSeq)
            assertEquals(setOf(0), resumed.cached.keys)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun inputTextIsNotEchoedIntoOutcomeOrJournal() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-input").toFile()
        try {
            val secret = "sensitive-user-input-987"
            val manager = mockk<AutomationManager>()
            coEvery { manager.inputText(secret) } returns true
            val journal = WorkflowJournal(directory)
            val workflow = AutomationWorkflow(manager, journal)

            val outcome = workflow.run(
                listOf(AutomationWorkflowStep(action = "input_text", text = secret)),
                runId = "input-run",
            )
            val persistedResult = journal.load("input-run")[0]?.result.orEmpty()

            assertFalse(outcome.content.contains(secret))
            assertFalse(persistedResult.contains(secret))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun cancellationLeavesUncertainCheckpointAndDoesNotReplayAutomatically() = runBlocking {
        val directory = Files.createTempDirectory("automation-workflow-cancel").toFile()
        try {
            val journal = WorkflowJournal(directory)
            val step = AutomationWorkflowStep(action = "wait", durationMs = 10_000L)
            val workflow = AutomationWorkflow(manager = io.mockk.mockk(), journal = journal)
            val job = launch { workflow.run(listOf(step), runId = "cancel-run") }

            withTimeout(2_000L) {
                while (journal.load("cancel-run")[0]?.status != "running") delay(10L)
            }
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertTrue(journal.load("cancel-run")[0]?.status == "running")
            val resumed = workflow.run(listOf(step), runId = "cancel-run")
            assertTrue(resumed.isError)
            assertTrue(resumed.content.contains("结果可能未知"))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `workflow journal rejects path traversal run ids`() {
        val directory = Files.createTempDirectory("workflow-run-id").toFile()
        try {
            val journal = WorkflowJournal(directory)
            try {
                journal.pathOf("../../outside")
                throw AssertionError("unsafe run id must be rejected")
            } catch (_: IllegalArgumentException) {
                assertTrue(true)
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private class TestWorkflowResultCipher(private val rejectDecrypt: Boolean = false) : SecureKeyCipher {
        override suspend fun encrypt(plain: String): String =
            "test_enc:" + java.util.Base64.getEncoder().encodeToString(plain.toByteArray())

        override suspend fun decrypt(stored: String): String = decryptOrNull(stored).orEmpty()

        override suspend fun decryptOrNull(stored: String): String? {
            if (rejectDecrypt) return null
            if (!stored.startsWith("test_enc:")) return stored
            return runCatching {
                String(java.util.Base64.getDecoder().decode(stored.removePrefix("test_enc:")))
            }.getOrNull()
        }
    }

    @Test
    fun `workflow reports per-step progress to the caller`() = runBlocking {
        // 20 步的 workflow 中途没有任何反馈时，用户看到的就是一个转圈。
        // 这条钉住「第 x/y 步」确实会报到调用方（编排器再把它写进任务卡）。
        val directory = Files.createTempDirectory("automation-workflow-progress").toFile()
        try {
            val workflow = AutomationWorkflow(manager = io.mockk.mockk(), journal = WorkflowJournal(directory))
            val steps = listOf(
                AutomationWorkflowStep(action = "wait", durationMs = 10L),
                AutomationWorkflowStep(action = "wait", durationMs = 10L),
            )
            val reported = mutableListOf<String>()

            val result = workflow.run(steps, runId = "progress-run", onProgress = { reported += it })

            assertFalse(result.isError)
            assertEquals(listOf("第 1/2 步：wait", "第 2/2 步：wait"), reported)
        } finally {
            directory.deleteRecursively()
        }
    }
}
