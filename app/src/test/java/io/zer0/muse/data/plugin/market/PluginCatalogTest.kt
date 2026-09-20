package io.zer0.muse.data.plugin.market

import io.zer0.muse.data.plugin.PluginSecurityGate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PluginCatalogTest {

    @Test
    fun verifyAcceptsSignedFreshCatalog() {
        val fixture = signedCatalog()
        val result = PluginCatalogVerifier.verify(
            signed = fixture.signed,
            trustedKeys = mapOf("root-1" to fixture.publicKey),
            nowEpochMs = 1_000L,
        )

        assertTrue(result.valid)
    }

    @Test
    fun verifyRejectsUnknownKeyExpiredAndRollback() {
        val fixture = signedCatalog(expiresAt = 900L, sequence = 5L)
        assertFalse(
            PluginCatalogVerifier.verify(
                fixture.signed,
                trustedKeys = emptyMap(),
                nowEpochMs = 1L,
            ).valid,
        )
        assertFalse(
            PluginCatalogVerifier.verify(
                fixture.signed,
                trustedKeys = mapOf("root-1" to fixture.publicKey),
                nowEpochMs = 1_000L,
            ).valid,
        )

        val fresh = signedCatalog(sequence = 5L)
        assertFalse(
            PluginCatalogVerifier.verify(
                fresh.signed,
                trustedKeys = mapOf("root-1" to fresh.publicKey),
                nowEpochMs = 1_000L,
                lastAcceptedSequence = 6L,
            ).valid,
        )
    }

    @Test
    fun verifyRejectsCatalogEntriesWithHttpOrDisallowedCapabilities() {
        val fixture = signedCatalog(
            entry = baseEntry().copy(
                artifactUrl = "http://example.com/plugin.zip",
                capabilities = listOf("network"),
            ),
        )

        val result = PluginCatalogVerifier.verify(
            fixture.signed,
            trustedKeys = mapOf("root-1" to fixture.publicKey),
            nowEpochMs = 1_000L,
        )

        assertFalse(result.valid)
    }

    private data class Fixture(
        val signed: SignedPluginCatalog,
        val publicKey: String,
    )

    private fun signedCatalog(
        sequence: Long = 1L,
        expiresAt: Long = 10_000L,
        entry: PluginCatalogEntry = baseEntry(),
    ): Fixture {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        val pair = generator.generateKeyPair()
        val publicKey = Base64.getEncoder().encodeToString(pair.public.encoded)
        val payload = PluginCatalog(
            catalogId = "official",
            sequence = sequence,
            generatedAtEpochMs = 0L,
            expiresAtEpochMs = expiresAt,
            entries = listOf(entry),
        )
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).apply {
            initSign(pair.private)
            update(PluginCatalogVerifier.canonicalPayload(payload))
        }.sign()
        return Fixture(
            signed = SignedPluginCatalog(
                payload = payload,
                keyId = "root-1",
                signatureBase64 = Base64.getEncoder().encodeToString(signature),
            ),
            publicKey = publicKey,
        )
    }

    private fun baseEntry() = PluginCatalogEntry(
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
    )
}
