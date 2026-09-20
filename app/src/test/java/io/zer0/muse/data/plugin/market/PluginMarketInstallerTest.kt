package io.zer0.muse.data.plugin.market

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginSecurityGate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class PluginMarketInstallerTest {

    private val fixtures = PluginMarketTestFixtures
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun expiredPlanIsRejectedWithoutCallingPluginManager() = runBlocking {
        val manager = mockk<PluginManager>(relaxed = true)
        val staged = stagedFile("package")
        val plan = plan(staged, sha256("package"), expiresAtEpochMs = 500L)

        val result = PluginMarketInstaller(manager, nowEpochMs = { 1_000L })
            .confirmAndInstall(plan, trustPublisher = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("过期"))
        coVerify(exactly = 0) { manager.installConfirmedFromFile(any(), any(), any()) }
    }

    @Test
    fun stagedFileChangedAfterPlanIsRejected() = runBlocking {
        val manager = mockk<PluginManager>(relaxed = true)
        val staged = stagedFile("original")
        val plan = plan(staged, sha256("original"), expiresAtEpochMs = 10_000L)
        staged.writeText("replaced")

        val result = PluginMarketInstaller(manager, nowEpochMs = { 1_000L })
            .confirmAndInstall(plan, trustPublisher = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("变化"))
        coVerify(exactly = 0) { manager.installConfirmedFromFile(any(), any(), any()) }
    }

    @Test
    fun missingStagedFileIsRejected() = runBlocking {
        val manager = mockk<PluginManager>(relaxed = true)
        val staged = File(tempDir(), "safe-plugin-1.0.0.muse-plugin")
        val plan = plan(staged, sha256("gone"), expiresAtEpochMs = 10_000L)

        val result = PluginMarketInstaller(manager, nowEpochMs = { 1_000L })
            .confirmAndInstall(plan, trustPublisher = false)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("不存在"))
    }

    @Test
    fun matchingPlanForwardsTrustDecisionAndManagerResult() = runBlocking {
        val manager = mockk<PluginManager>()
        val installed = PluginManager.InstalledPlugin(id = "safe-plugin", name = "Safe Plugin", version = "1.0.0")
        val staged = stagedFile("verified-bytes")
        val plan = plan(staged, sha256("verified-bytes"), expiresAtEpochMs = 10_000L)
        coEvery {
            manager.installConfirmedFromFile(staged, plan.preview, true)
        } returns Result.success(installed)

        val result = PluginMarketInstaller(manager, nowEpochMs = { 1_000L })
            .confirmAndInstall(plan, trustPublisher = true)

        assertEquals(installed, result.getOrNull())
        coVerify(exactly = 1) { manager.installConfirmedFromFile(staged, plan.preview, true) }
    }

    @Test
    fun discardDeletesStagedPackageAndIgnoresForeignPaths() {
        val installer = PluginMarketInstaller(mockk<PluginManager>(relaxed = true))
        val staged = stagedFile("to-delete")
        val plan = plan(staged, sha256("to-delete"), expiresAtEpochMs = 10_000L)

        installer.discard(plan)

        assertFalse(staged.exists())
        val foreign = File(tempDir(), "unrelated.bin").apply { writeText("keep") }
        installer.discard(plan.copy(stagedFile = foreign))
        assertTrue(foreign.exists())
    }

    private fun plan(staged: File, artifactSha: String, expiresAtEpochMs: Long) = PluginInstallPlan(
        entry = fixtures.entry(artifactSha256 = artifactSha),
        stagedFile = staged,
        artifactSha256 = artifactSha,
        preview = preview(artifactSha),
        createdAtEpochMs = 0L,
        expiresAtEpochMs = expiresAtEpochMs,
    )

    private fun preview(contentSha256: String) = PluginSecurityGate.InstallPreview(
        id = "safe-plugin",
        name = "Safe Plugin",
        version = "1.0.0",
        author = "tester",
        description = "",
        entry = "main.js",
        trust = "sandboxed",
        capabilities = listOf("resource.read"),
        permissions = emptyList(),
        tools = emptyList(),
        entrySha256 = "f".repeat(64),
        contentSha256 = contentSha256,
        entryBytes = 1,
        extraFileCount = 0,
        totalCodeBytes = 1,
        signatureStatus = PluginSecurityGate.SignatureStatus.VALID_TRUSTED,
        publisherId = "publisher.test",
        publisherKeyFingerprint = "a".repeat(64),
        requiresPublisherTrust = false,
    )

    private fun stagedFile(content: String): File {
        val dir = tempDir()
        return File(dir, "safe-plugin-1.0.0.muse-plugin").apply { writeText(content) }
    }

    private fun tempDir(): File =
        Files.createTempDirectory("plugin-market-installer").toFile().also { tempDirs += it }

    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray()).joinToString("") { "%02x".format(it) }
}
