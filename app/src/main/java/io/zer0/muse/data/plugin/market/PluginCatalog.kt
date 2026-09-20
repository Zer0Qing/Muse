package io.zer0.muse.data.plugin.market

import io.zer0.common.AppJson
import io.zer0.muse.data.plugin.PluginSecurityGate
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** A signed, immutable catalog entry; it describes a package but never grants runtime access. */
@Serializable
data class PluginCatalogEntry(
    val id: String,
    val version: String,
    val name: String,
    val description: String = "",
    val publisherId: String,
    val publisherKeyFingerprint: String,
    val artifactUrl: String,
    val artifactBytes: Long,
    val artifactSha256: String,
    val manifestSha256: String,
    val capabilities: List<String> = emptyList(),
    val permissions: List<String> = emptyList(),
)

/** Catalog payload signed by an app-bundled catalog root, not by the user trust store. */
@Serializable
data class PluginCatalog(
    val catalogId: String,
    val schemaVersion: Int = 1,
    val sequence: Long,
    val generatedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val entries: List<PluginCatalogEntry> = emptyList(),
)

@Serializable
data class SignedPluginCatalog(
    val payload: PluginCatalog,
    val keyId: String,
    val signatureBase64: String,
)

data class CatalogVerification(
    val valid: Boolean,
    val reason: String? = null,
)

/**
 * Verifies catalog metadata before any artifact download. The trusted key map must be
 * bundled/configured by the app; it must never be populated from the catalog itself.
 */
object PluginCatalogVerifier {
    private const val MAX_CATALOG_BYTES = 512 * 1024
    private const val MAX_ARTIFACT_BYTES = 20L * 1024 * 1024
    private const val SIGNATURE_ALGORITHM = PluginSecurityGate.SIGNATURE_ALGORITHM
    private val ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]{0,63}$")
    private val VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+(?:[-+][0-9A-Za-z.-]+)?$")
    private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")

    fun canonicalPayload(payload: PluginCatalog): ByteArray = AppJson.encodeToString(
        PluginCatalog.serializer(),
        payload,
    ).toByteArray(Charsets.UTF_8)

    fun verify(
        signed: SignedPluginCatalog,
        trustedKeys: Map<String, String>,
        nowEpochMs: Long,
        lastAcceptedSequence: Long? = null,
    ): CatalogVerification {
        val payloadBytes = canonicalPayload(signed.payload)
        if (payloadBytes.size > MAX_CATALOG_BYTES) return invalid("catalog too large")
        val keyEncoded = trustedKeys[signed.keyId] ?: return invalid("unknown catalog key")
        if (signed.payload.catalogId.isBlank() || signed.payload.schemaVersion != 1) {
            return invalid("unsupported catalog metadata")
        }
        if (signed.payload.expiresAtEpochMs <= nowEpochMs) return invalid("catalog expired")
        if (lastAcceptedSequence != null && signed.payload.sequence < lastAcceptedSequence) {
            return invalid("catalog sequence rollback")
        }
        if (signed.payload.entries.any { !entryValid(it) }) return invalid("invalid catalog entry")
        val signature = runCatching {
            val key = parsePublicKey(keyEncoded)
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(key)
                update(payloadBytes)
            }.verify(Base64.getDecoder().decode(signed.signatureBase64))
        }.getOrElse { return invalid("catalog signature could not be verified") }
        return if (signature) CatalogVerification(valid = true) else invalid("catalog signature mismatch")
    }

    private fun entryValid(entry: PluginCatalogEntry): Boolean =
        ID_REGEX.matches(entry.id) &&
            VERSION_REGEX.matches(entry.version) &&
            PluginSecurityGate.isValidPublisherId(entry.publisherId) &&
            SHA256_REGEX.matches(entry.publisherKeyFingerprint.lowercase()) &&
            entry.artifactUrl.startsWith("https://") &&
            entry.artifactBytes in 1..MAX_ARTIFACT_BYTES &&
            SHA256_REGEX.matches(entry.artifactSha256) &&
            SHA256_REGEX.matches(entry.manifestSha256) &&
            entry.capabilities.all { it in PluginSecurityGate.allowedCapabilities } &&
            entry.permissions.all { it in PluginSecurityGate.allowedCapabilities }

    private fun parsePublicKey(encoded: String): PublicKey {
        val bytes = Base64.getDecoder().decode(encoded)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun invalid(reason: String) = CatalogVerification(valid = false, reason = reason)
}
