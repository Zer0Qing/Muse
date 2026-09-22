package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.SecureKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * v1.0.92: 渠道配置存储 — 持久化到 filesDir/channel_configs.json(原子写)。
 *
 * APP Secret 经 [SecureKeyStore] 加密后落盘,内存中保持明文。
 */
class ChannelStore(private val file: File) {

    @Serializable
    private data class Payload(val channels: List<ChannelConfig> = emptyList())

    suspend fun load(): List<ChannelConfig> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val payload = AppJson.decodeFromString(Payload.serializer(), file.readText())
            payload.channels.map { decrypt(it) }
        }.onFailure { e ->
            Logger.w(TAG, "渠道配置读取失败: ${e.message}")
        }.getOrDefault(emptyList())
    }

    suspend fun save(channels: List<ChannelConfig>) = withContext(Dispatchers.IO) {
        val payload = Payload(channels.map { encrypt(it) })
        AtomicFileStore.writeText(file, AppJson.encodeToString(Payload.serializer(), payload))
    }

    private suspend fun encrypt(config: ChannelConfig): ChannelConfig =
        config.copy(
            appSecret = if (config.appSecret.isBlank()) "" else SecureKeyStore.encrypt(config.appSecret),
        )

    private suspend fun decrypt(config: ChannelConfig): ChannelConfig =
        config.copy(
            appSecret = if (config.appSecret.isBlank()) "" else SecureKeyStore.decrypt(config.appSecret),
        )

    companion object {
        private const val TAG = "ChannelStore"
    }
}
