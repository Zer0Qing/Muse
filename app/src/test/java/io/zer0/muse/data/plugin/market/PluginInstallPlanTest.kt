package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginSecurityGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PluginInstallPlanTest {

    @Test
    fun preparePlanAcceptsMatchingArtifactAndContentDigest() {
        val fixture = signedCatalog()
        val staged = File.createTempFile("plugin", ".muse-plugin").apply {
            writeText("staged bytes")
            deleteOnExit()
        }
        val result = coordinator(fixture.publicKey).preparePlan(
            signedCatalog = fixture.signed,
            entryId = "safe-plugin",
            stagedFile = staged,
            artifactSha256 = "b".repeat(64),
            reviewedPackage = review(version = "1.0.0", contentSha = "c".repeat(64)),
            nowEpochMs = 1_000L,
        )

        assertTrue(result is InstallPlanResult.Ready)
        val plan = (result as InstallPlanResult.Ready).plan
        assertEquals("safe-plugin", plan.preview.id)
        assertEquals(staged, plan.stagedFile)
    }

    @Test
    fun preparePlanRejectsArtifactContentAndVersionMismatch() {
        val fixture = signedCatalog()
        val staged = File.createTempFile("plugin", ".muse-plugin").apply {
            writeText("staged bytes")
            deleteOnExit()
        }
        val coordinator = coordinator(fixture.publicKey)

        val wrongArtifact = coordinator.preparePlan(
            fixture.signed, "safe-plugin", staged,
            artifactSha256 = "d".repeat(64),
            reviewedPackage = review("1.0.0", "c".repeat(64)),
            nowEpochMs = 1_000L,
        )
        val wrongContent = coordinator.preparePlan(
            fixture.signed, "safe-plugin", staged,
            artifactSha256 = "b".repeat(64),
            reviewedPackage = review("1.0.0", "e".repeat(64)),
            nowEpochMs = 1_000L,
        )
        val wrongVersion = coordinator.preparePlan(
            fixture.signed, "safe-plugin", staged,
            artifactSha256 = "b".repeat(64),
            reviewedPackage = review("2.0.0", "c".repeat(64)),
            nowEpochMs = 1_000L,
        )

        assertTrue(wrongArtifact is InstallPlanResult.Rejected)
        assertTrue(wrongContent is InstallPlanResult.Rejected)
        assertTrue(wrongVersion is InstallPlanResult.Rejected)
    }

    @Test
    fun expiredOrRolledBackCatalogIsRejected() {
        val fixture = signedCatalog(sequence = 5L)
        val staged = File.createTempFile("plugin", ".muse-plugin").apply {
            writeText("staged bytes")
            deleteOnExit()
        }

        val rolledBack = PluginInstallCoordinator(
            trustRootKeys = mapOf("root-1" to fixture.publicKey),
            lastAcceptedSequence = { _ -> 6L },
        ).preparePlan(            fixture.signed, "safe-plugin", staged,
            artifactSha256 = "b".repeat(64),
            reviewedPackage = review("1.0.0", "c".repeat(64)),
            nowEpochMs = 1_000L,
        )

        assertTrue(rolledBack is InstallPlanResult.Rejected)
    }

    private fun coordinator(publicKey: String) = PluginInstallCoordinator(
        trustRootKeys = mapOf("root-1" to publicKey),
        lastAcceptedSequence = { _ -> null },
    )

    private fun review(version: String, contentSha: String) = PluginInstallCoordinator.PluginPackageReview(
        preview = PluginSecurityGate.InstallPreview(
            id = "safe-plugin",
            name = "Safe Plugin",
            version = version,
            author = "tester",
            description = "",
            entry = "main.js",
            trust = "sandboxed",
            capabilities = listOf("resource.read"),
            permissions = emptyList(),
            tools = emptyList(),
            entrySha256 = "f".repeat(64),
            contentSha256 = contentSha,
            entryBytes = 1,
            extraFileCount = 0,
            totalCodeBytes = 1,
            signatureStatus = PluginSecurityGate.SignatureStatus.VALID_TRUSTED,
            publisherId = "publisher.test",
            publisherKeyFingerprint = "a".repeat(64),
            requiresPublisherTrust = false,
        ),
        contentSha256 = contentSha,
    )

    private data class Fixture(val signed: SignedPluginCatalog, val publicKey: String)

    private fun signedCatalog(sequence: Long = 1L): Fixture {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val payload = PluginCatalog(
            catalogId = "official",
            sequence = sequence,
            generatedAtEpochMs = 0L,
            expiresAtEpochMs = 10_000L,
            entries = listOf(
                PluginCatalogEntry(
                    id = "safe-plugin",
                    version = "1.0.0",
                    name = "Safe Plugin",
                    publisherId = "publisher.test",
                    publisherKeyFingerprint = "a".repeat(64),
                    artifactUrl = "https://example.com/safe.muse-plugin",
                    artifactBytes = 1024L,
                    artifactSha256 = "b".repeat(64),
                    manifestSha256 = "c".repeat(64),
                    capabilities = listOf("resource.read"),
                ),
            ),
        )
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).apply {
            initSign(pair.private)
            update(PluginCatalogVerifier.canonicalPayload(payload))
        }.sign()
        return Fixture(
            SignedPluginCatalog(payload, "root-1", Base64.getEncoder().encodeToString(signature)),
            publicKey,
        )
    }
}
