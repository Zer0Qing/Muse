package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import io.zer0.muse.data.plugin.PluginSecurityGate
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** 市场定向测试共用的签名目录夹具（P-256 ECDSA，与目录校验器同算法）。 */
internal object PluginMarketTestFixtures {

    fun newKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp256r1"))
        return generator.generateKeyPair()
    }

    fun publicKeyBase64(pair: KeyPair): String = Base64.getEncoder().encodeToString(pair.public.encoded)

    fun payload(
        sequence: Long = 1L,
        expiresAtEpochMs: Long = 10_000L,
        entries: List<PluginCatalogEntry> = listOf(entry()),
        catalogId: String = "official",
    ): PluginCatalog = PluginCatalog(
        catalogId = catalogId,
        sequence = sequence,
        generatedAtEpochMs = 0L,
        expiresAtEpochMs = expiresAtEpochMs,
        entries = entries,
    )

    fun sign(payload: PluginCatalog, pair: KeyPair, keyId: String = "root-1"): SignedPluginCatalog {
        val signature = Signature.getInstance(PluginSecurityGate.SIGNATURE_ALGORITHM).apply {
            initSign(pair.private)
            update(PluginCatalogVerifier.canonicalPayload(payload))
        }.sign()
        return SignedPluginCatalog(
            payload = payload,
            keyId = keyId,
            signatureBase64 = Base64.getEncoder().encodeToString(signature),
        )
    }

    fun catalogJson(signed: SignedPluginCatalog): String =
        AppJson.encodeToString(SignedPluginCatalog.serializer(), signed)

    fun entry(
        id: String = "safe-plugin",
        version: String = "1.0.0",
        artifactSha256: String = "b".repeat(64),
        manifestSha256: String = "c".repeat(64),
    ): PluginCatalogEntry = PluginCatalogEntry(
        id = id,
        version = version,
        name = "Safe Plugin",
        publisherId = "publisher.test",
        publisherKeyFingerprint = "a".repeat(64),
        artifactUrl = "https://example.com/safe.muse-plugin",
        artifactBytes = 1024L,
        artifactSha256 = artifactSha256,
        manifestSha256 = manifestSha256,
        capabilities = listOf("resource.read"),
    )
}
