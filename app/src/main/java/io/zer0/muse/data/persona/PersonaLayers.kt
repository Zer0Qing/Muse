package io.zer0.muse.data.persona

import kotlinx.serialization.Serializable

/**
 * Three-layer persona architecture (openhanako agent.ts identity/yuan/ishiki port).
 *
 * - Identity: basic identity (name, role, background)
 * - Capability (Yuan): capability type and behavior protocol
 * - Behavior(行为/Ishiki):行为倾向与性格特征
 */
@Serializable
data class PersonaLayers(
    val identity: Identity = Identity(),
    val capability: Capability = Capability(),
    val behavior: PersonaBehavior = PersonaBehavior(),
) {
    @Serializable
    data class Identity(
        val name: String = "",
        val role: String = "",
        val background: String = "",
        val emoji: String = "",
    )

    @Serializable
    data class Capability(
        val type: String = "general",
        val specialties: List<String> = emptyList(),
        val limitations: String = "",
        val protocol: String = "",
    )

    @Serializable
    data class PersonaBehavior(
        val tone: String = "warm",
        val speakingStyle: String = "",
        val boundaries: String = "",
        val catchphrases: List<String> = emptyList(),
        val rawIshiki: String = "",
    )

    fun renderForPrompt(): String = buildString {
        if (identity.name.isNotBlank()) {
            appendLine("## Identity")
            appendLine("- Name: ${identity.name}")
            if (identity.role.isNotBlank()) appendLine("- Role: ${identity.role}")
            if (identity.background.isNotBlank()) appendLine("- Background: ${identity.background}")
            appendLine()
        }
        if (capability.type != "general" || capability.specialties.isNotEmpty()) {
            appendLine("## Capabilities")
            appendLine("- Type: ${capability.type}")
            if (capability.specialties.isNotEmpty())
                appendLine("- Specialties: ${capability.specialties.joinToString(", ")}")
            if (capability.protocol.isNotBlank()) appendLine(capability.protocol)
            appendLine()
        }
        if (behavior.tone != "warm" || behavior.speakingStyle.isNotBlank()) {
            appendLine("## Behavior and Personality")
            appendLine("- Tone: ${behavior.tone}")
            if (behavior.speakingStyle.isNotBlank()) appendLine("- Style: ${behavior.speakingStyle}")
            if (behavior.boundaries.isNotBlank()) appendLine("- Boundaries: ${behavior.boundaries}")
            if (behavior.catchphrases.isNotEmpty())
                appendLine("- Catchphrases: ${behavior.catchphrases.joinToString("; ")}")
            appendLine()
        }
    }.trimEnd()
}
