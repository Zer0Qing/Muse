package io.zer0.muse.mcp

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McpOAuthChallengeTest {

    @Test
    fun resourceMetadataUrlIsExtractedFromBearerChallenge() {
        val metadataUrl = McpDynamicRegistration.resourceMetadataUrlFromChallenge(
            "Bearer realm=\"mcp\", resource_metadata=\"https://auth.example/.well-known/oauth-protected-resource/mcp\"",
        )

        assertEquals("https://auth.example/.well-known/oauth-protected-resource/mcp", metadataUrl)
    }

    @Test
    fun insecureMetadataUrlIsRejected() {
        assertNull(
            McpDynamicRegistration.resourceMetadataUrlFromChallenge(
                "Bearer resource_metadata=\"http://auth.example/protected-resource\"",
            ),
        )
    }

    @Test
    fun malformedAndDuplicateCallbackParametersAreRejected() {
        assertNull(McpOAuthFlow.callbackState("io.zer0.muse://oauth/callback?state=%ZZ"))
        assertNull(McpOAuthFlow.parseRedirectCallback("io.zer0.muse://oauth/callback?state=a&code=b&state=c"))
    }

    @Test
    fun callbackStateIsAvailableForDeniedAuthorization() {
        val state = McpOAuthFlow.callbackState(
            "io.zer0.muse://oauth/callback?error=access_denied&state=csrf-value",
            "io.zer0.muse://oauth/callback",
        )

        assertEquals("csrf-value", state)
    }

    @Test
    fun challengeDiscoversRegistersAndBuildsPkceAuthorizationUrl() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val issuer = server.url("/").toString().trimEnd('/')
            val resourceUrl = server.url("/mcp").toString()
            val metadataUrl = server.url("/.well-known/oauth-protected-resource/mcp").toString()
            server.enqueue(
                MockResponse().setResponseCode(401)
                    .addHeader("WWW-Authenticate", "Bearer resource_metadata=\"$metadataUrl\", scope=\"tools:read tools:call\""),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"authorization_servers":["$issuer"]}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"issuer":"$issuer","authorization_endpoint":"${server.url(
                        "/authorize",
                    )}","token_endpoint":"${server.url("/token")}","registration_endpoint":"${server.url("/register")}"}""",
                ),
            )
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"client_id":"local-test-client"}"""))

            val client = McpClient(
                McpServerConfig(
                    id = "oauth-loopback",
                    name = "OAuth Loopback",
                    transportType = McpTransportType.STREAMABLE_HTTP,
                    url = resourceUrl,
                    autoReconnect = false,
                ),
            )
            try {
                client.start()
                withTimeout(5_000L) { client.state.first { it == McpConnectionState.NEEDS_AUTH } }
                assertEquals(metadataUrl, client.protectedResourceMetadataUrl)
                val challengedUrl = McpDynamicRegistration.resourceMetadataUrlFromChallenge(
                    "Bearer resource_metadata=\"$metadataUrl\", scope=\"tools:read tools:call\"",
                )
                val config = McpDynamicRegistration.discoverAndRegister(
                    serverUrl = resourceUrl,
                    scopes = McpDynamicRegistration.scopeFromChallenge(
                        "Bearer resource_metadata=\"$metadataUrl\", scope=\"tools:read tools:call\"",
                    ).orEmpty(),
                    protectedResourceMetadataUrl = challengedUrl,
                )

                checkNotNull(config)
                val authorizationUrl = McpOAuthFlow.buildAuthorizationUrl(config, McpOAuthFlow.generatePkcePair())
                    .toHttpUrl()
                assertEquals("local-test-client", authorizationUrl.queryParameter("client_id"))
                assertEquals("tools:read tools:call", authorizationUrl.queryParameter("scope"))
                assertEquals(resourceUrl, authorizationUrl.queryParameter("resource"))
                assertEquals("S256", authorizationUrl.queryParameter("code_challenge_method"))
                assertEquals("/mcp", server.takeRequest().path)
                assertEquals("/.well-known/oauth-protected-resource/mcp", server.takeRequest().path)
                assertEquals("/.well-known/oauth-authorization-server", server.takeRequest().path)
                assertEquals("/register", server.takeRequest().path)
            } finally {
                client.close()
            }
        }
    }
}
