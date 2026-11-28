package io.zer0.muse.data

import io.zer0.ai.core.OAuthConfig
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderSpecificConfig
import io.zer0.common.AppJson
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import java.net.URI
import java.net.URISyntaxException

/** Provider backup parsing, credential redaction, and safe local credential carry-forward. */
internal object ProviderBackupPolicy {
    fun decode(raw: String): List<ProviderConfig>? = try {
        AppJson.decodeFromString(ListSerializer(ProviderConfig.serializer()), raw)
    } catch (_: SerializationException) {
        null
    }

    fun redact(config: ProviderConfig): ProviderConfig = config.copy(
        baseUrl = endpointWithoutCredentials(config.baseUrl),
        balanceApiPath = endpointWithoutCredentials(config.balanceApiPath),
        apiKey = "",
        oauthConfig = config.oauthConfig?.let { oauth ->
            oauth.copy(
                clientSecret = null,
                authorizeUrl = endpointWithoutCredentials(oauth.authorizeUrl),
                tokenUrl = endpointWithoutCredentials(oauth.tokenUrl),
                redirectUri = endpointWithoutCredentials(oauth.redirectUri),
                deviceCodeUrl = oauth.deviceCodeUrl?.let(::endpointWithoutCredentials),
            )
        },
        specific = redactSpecific(config.specific),
    )

    fun mergeLocalCredentials(imported: ProviderConfig, local: ProviderConfig): ProviderConfig {
        if (!sameEndpointIdentity(imported, local)) return imported
        return imported.copy(
            apiKey = local.apiKey,
            oauthConfig = imported.oauthConfig?.let { incoming ->
                incoming.copy(clientSecret = local.oauthConfig?.clientSecret)
            },
            specific = mergeSpecific(imported.specific, local.specific),
        )
    }

    private fun redactSpecific(specific: ProviderSpecificConfig?): ProviderSpecificConfig? = when (specific) {
        null -> null
        is ProviderSpecificConfig.OpenAI -> specific.copy(
            chatCompletionsPath = endpointWithoutCredentials(specific.chatCompletionsPath),
            responsesPath = endpointWithoutCredentials(specific.responsesPath),
            embeddingsPath = endpointWithoutCredentials(specific.embeddingsPath),
            imagesPath = endpointWithoutCredentials(specific.imagesPath),
            videoGenerationsPath = endpointWithoutCredentials(specific.videoGenerationsPath),
            customHeaders = emptyMap(),
            customBody = emptyMap(),
        )
        is ProviderSpecificConfig.Anthropic -> specific.copy(
            messagesPath = endpointWithoutCredentials(specific.messagesPath),
            modelsPath = endpointWithoutCredentials(specific.modelsPath),
        )
        is ProviderSpecificConfig.Gemini -> specific.copy(
            privateKey = "",
            streamPath = endpointWithoutCredentials(specific.streamPath),
            generatePath = endpointWithoutCredentials(specific.generatePath),
        )
        is ProviderSpecificConfig.Custom -> specific.copy(
            chatCompletionsPath = endpointWithoutCredentials(specific.chatCompletionsPath),
            customHeaders = emptyMap(),
            customBody = emptyMap(),
            requestTemplate = "",
        )
    }

    private fun mergeSpecific(imported: ProviderSpecificConfig?, local: ProviderSpecificConfig?): ProviderSpecificConfig? = when {
        imported is ProviderSpecificConfig.OpenAI && local is ProviderSpecificConfig.OpenAI -> imported.copy(
            customHeaders = local.customHeaders,
            customBody = local.customBody,
        )
        imported is ProviderSpecificConfig.Anthropic && local is ProviderSpecificConfig.Anthropic -> imported
        imported is ProviderSpecificConfig.Gemini && local is ProviderSpecificConfig.Gemini ->
            imported.copy(privateKey = local.privateKey)
        imported is ProviderSpecificConfig.Custom && local is ProviderSpecificConfig.Custom -> imported.copy(
            customHeaders = local.customHeaders,
            customBody = local.customBody,
            requestTemplate = local.requestTemplate,
        )
        imported == null -> null
        else -> imported
    }

    private fun sameEndpointIdentity(left: ProviderConfig, right: ProviderConfig): Boolean =
        left.id == right.id && left.type == right.type && left.baseUrl == right.baseUrl &&
            left.balanceApiPath == right.balanceApiPath && left.specific == redactSpecific(right.specific) &&
            sameOAuthEndpoints(left.oauthConfig, right.oauthConfig)

    private fun sameOAuthEndpoints(left: OAuthConfig?, right: OAuthConfig?): Boolean = when {
        left == null && right == null -> true
        left == null || right == null -> false
        else ->
            left.authorizeUrl == right.authorizeUrl &&
                left.tokenUrl == right.tokenUrl && left.clientId == right.clientId &&
                left.redirectUri == right.redirectUri && left.codexDeviceAuth == right.codexDeviceAuth &&
                left.scope == right.scope && left.deviceCodeUrl == right.deviceCodeUrl
    }

    private fun endpointWithoutCredentials(value: String): String {
        // Gemini 的方法后缀是相对片段，Java URI 会把开头的冒号当成非法方案。
        if (value.startsWith(':')) return value.substringBefore('?').substringBefore('#')
        val uri = try {
            URI(value)
        } catch (error: URISyntaxException) {
            // 无法解析时无法安全识别认证信息。
            return ""
        }
        if (uri.isOpaque) return ""
        return buildString {
            uri.scheme?.let { append(it).append(':') }
            uri.rawAuthority?.let { append("//").append(it.substringAfterLast('@')) }
            append(uri.rawPath.orEmpty())
        }
    }
}
