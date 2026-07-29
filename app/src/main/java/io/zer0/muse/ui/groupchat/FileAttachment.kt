package io.zer0.muse.ui.groupchat

import kotlinx.serialization.Serializable

@Serializable
data class FileAttachment(
    val name: String,
    val mimeType: String,
    val base64: String,
)
