package io.zer0.muse.conversation.core

enum class ProviderEventType {
    CONTENT,
    REASONING,
    TOOL_CALL,
    IMAGE,
    FALLBACK,
    USAGE,
    CITATION,
    COMPLETED,
    INTERRUPTED,
    FAILED,
}

sealed interface ProviderEvent {
    val type: ProviderEventType
    val sequence: Long

    data class Content(
        override val sequence: Long,
        val text: String,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.CONTENT
    }

    data class Reasoning(
        override val sequence: Long,
        val text: String,
        val signature: String? = null,
        val encryptedContent: String? = null,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.REASONING
    }

    data class ToolCall(
        override val sequence: Long,
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String?,
        val snapshot: Boolean,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.TOOL_CALL
    }

    data class Image(
        override val sequence: Long,
        val base64Data: String,
        val mimeType: String,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.IMAGE
    }

    data class Fallback(
        override val sequence: Long,
        val message: String,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.FALLBACK
    }

    data class Usage(
        override val sequence: Long,
        val inputTokens: Int?,
        val outputTokens: Int?,
        val totalTokens: Int?,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.USAGE
    }

    data class Citation(
        override val sequence: Long,
        val urls: List<String>,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.CITATION
    }

    data class Completed(
        override val sequence: Long,
        val finishReason: String?,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.COMPLETED
    }

    data class Interrupted(
        override val sequence: Long,
        val message: String,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.INTERRUPTED
    }

    data class Failed(
        override val sequence: Long,
        val message: String,
    ) : ProviderEvent {
        override val type: ProviderEventType = ProviderEventType.FAILED
    }
}
