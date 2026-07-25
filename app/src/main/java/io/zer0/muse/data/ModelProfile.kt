package io.zer0.muse.data

import kotlinx.serialization.Serializable

@Serializable
data class ModelProfile(
    val avatarUrl: String = "",
    val showAvatar: Boolean = false,
)
