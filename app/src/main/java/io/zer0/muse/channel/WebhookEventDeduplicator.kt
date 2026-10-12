package io.zer0.muse.channel

import io.zer0.common.AppJson
import io.zer0.muse.data.AtomicFileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.security.MessageDigest

/** 持久化短时 webhook 事件 ID，去重范围不受 UI inbox 的保留条数影响。 */
internal class WebhookEventDeduplicator(
    private val file: File,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private var entries = loadEntries()

    @Synchronized
    fun claim(platform: String, channelId: String, eventId: String): Boolean {
        require(platform.isNotBlank() && channelId.isNotBlank() && eventId.isNotBlank())
        val now = nowMillis()
        val retained = entries.filter { now - it.recordedAtMillis <= RETENTION_MILLIS }
        val key = digest("$platform\u0000$channelId\u0000$eventId")
        if (retained.any { it.digest == key }) {
            replaceEntries(retained)
            return false
        }
        check(retained.size < MAX_ENTRIES) {
            "Webhook event deduplication capacity reached; refusing event"
        }
        replaceEntries(retained + Entry(key, now))
        return true
    }

    @Synchronized
    fun contains(platform: String, channelId: String, eventId: String): Boolean {
        require(platform.isNotBlank() && channelId.isNotBlank() && eventId.isNotBlank())
        val now = nowMillis()
        val retained = entries.filter { now - it.recordedAtMillis <= RETENTION_MILLIS }
        val key = digest("$platform\u0000$channelId\u0000$eventId")
        val found = retained.any { it.digest == key }
        replaceEntries(retained)
        return found
    }

    private fun loadEntries(): List<Entry> {
        if (!file.exists()) return emptyList()
        val loaded = AppJson.decodeFromString(ListSerializer(Entry.serializer()), file.readText())
        // v2.6.0 (AUD-CHANNEL-F4): 超容量（异常写入/被篡改）的账本隔离后重开，
        // 而不是让构造抛错——抛错会让 attach 标记账本不可用、后续事件全部被拒。
        // 隔离保留现场供诊断，同时让去重器从干净状态继续工作。
        return if (loaded.size > MAX_ENTRIES) {
            AtomicFileStore.quarantine(file, "webhook_ledger_oversize")
            emptyList()
        } else {
            loaded
        }
    }

    private fun replaceEntries(updated: List<Entry>) {
        if (updated == entries) return
        AtomicFileStore.writeText(
            file,
            AppJson.encodeToString(ListSerializer(Entry.serializer()), updated),
        )
        entries = updated
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Serializable
    private data class Entry(
        val digest: String,
        val recordedAtMillis: Long,
    )

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val RETENTION_MILLIS = 10 * 60 * 1000L
    }
}
