package io.zer0.muse.data

import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.ai.core.ProviderType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBackupPolicyTest {
    @Test
    fun `redaction preserves provider configuration but removes credential fields`() {
        val config = provider().copy(
            baseUrl = "https://fixture-user:fixture-password@api.example.test/v1?k=fixture-query#fixture-fragment",
        )
        val encoded = io.zer0.common.AppJson.encodeToString(
            ProviderConfig.serializer(),
            ProviderBackupPolicy.redact(config),
        )

        assertTrue(encoded.contains("Example relay"))
        assertTrue(encoded.contains("https://api.example.test/v1"))
        assertTrue(encoded.contains("model-a"))
        assertFalse(encoded.contains("fixture-user"))
        assertFalse(encoded.contains("fixture-password"))
        assertFalse(encoded.contains("fixture-query"))
        assertFalse(encoded.contains("fixture-fragment"))
        assertFalse(encoded.contains("custom-value-fixture"))
        assertFalse(encoded.contains("api-secret-fixture"))
        assertFalse(encoded.contains("header-secret-fixture"))
        assertFalse(encoded.contains("body-secret-fixture"))
        assertFalse(encoded.contains("oauth-secret-fixture"))
        val gemini = ProviderBackupPolicy.redact(config.copy(specific = ProviderSpecificConfig.Gemini(privateKey = "fixture")))
        assertEquals("", (gemini.specific as? ProviderSpecificConfig.Gemini)?.privateKey)
    }

    @Test
    fun `malformed provider snapshot is omitted without raw fallback`() {
        val malformed = "not-json api-secret-fixture"
        assertNull(ProviderBackupPolicy.decode(malformed))
        assertFalse(ProviderBackupPolicy.decode(malformed)?.toString().orEmpty().contains("api-secret-fixture"))
    }

    @Test
    fun `local credentials carry forward only to exact provider endpoint identity`() {
        val incoming = ProviderBackupPolicy.redact(provider())
        val local = provider()
        val merged = ProviderBackupPolicy.mergeLocalCredentials(incoming, local)
        assertEquals("api-secret-fixture", merged.apiKey)
        val mergedHeaders = (merged.specific as? ProviderSpecificConfig.OpenAI)?.customHeaders
        assertEquals("header-secret-fixture", mergedHeaders?.get("Authorization"))
        assertEquals("oauth-secret-fixture", merged.oauthConfig?.clientSecret)

        val changedEndpoint = local.copy(baseUrl = "https://other.example.test/v1")
        val detached = ProviderBackupPolicy.mergeLocalCredentials(incoming, changedEndpoint)
        assertEquals("", detached.apiKey)
        assertEquals(emptyMap<String, String>(), (detached.specific as? ProviderSpecificConfig.OpenAI)?.customHeaders)
        assertNull(detached.oauthConfig?.clientSecret)
    }

    @Test
    fun `legacy provider snapshot implies secrets while explicit marker controls new snapshots`() {
        assertTrue(SettingsSnapshotPolicy.providerSecretsIncluded(mapOf("providers_json" to "[]")))
        assertFalse(
            SettingsSnapshotPolicy.providerSecretsIncluded(
                mapOf(
                    "providers_json" to "[]",
                    SettingsSnapshotPolicy.PROVIDER_SECRETS_INCLUDED_KEY to "false",
                ),
            ),
        )
        assertFalse(
            SettingsSnapshotPolicy.providerSecretsIncluded(
                mapOf(
                    "providers_json" to "[]",
                    SettingsSnapshotPolicy.PROVIDER_SECRETS_INCLUDED_KEY to "invalid",
                ),
            ),
        )
        assertTrue(SettingsSnapshotPolicy.isSafeKey("home_layout_mode"))
    }

    private fun provider(): ProviderConfig = ProviderConfig(
        id = "relay-1",
        displayName = "Example relay",
        type = ProviderType.OPENAI,
        baseUrl = "https://api.example.test/v1",
        apiKey = "api-secret-fixture",
        models = listOf(io.zer0.ai.core.Model(id = "model-a", name = "Model A", providerId = "relay-1")),
        specific = ProviderSpecificConfig.OpenAI(
            customHeaders = mapOf(
                "Authorization" to "header-secret-fixture",
                "X-User-Value" to "custom-value-fixture",
            ),
            customBody = mapOf(
                "api_token" to JsonPrimitive("body-secret-fixture"),
                "metadata" to buildJsonObject { put("mode", JsonPrimitive("safe")) },
            ),
        ),
        oauthConfig = OAuthConfig(
            authorizeUrl = "https://auth.example.test/authorize",
            tokenUrl = "https://auth.example.test/token",
            clientId = "public-client-id",
            clientSecret = "oauth-secret-fixture",
        ),
    )
}
