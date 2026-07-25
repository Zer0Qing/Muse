package io.zer0.muse.data.schedule

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

private val Context.pendingMessageStore: DataStore<Preferences> by preferencesDataStore(name = "pending_messages")

/**
 * Phase 3 3E: 定时消息管理器 — 存储待发送的定时消息,到期后自动发送。
 *
 * 用法:
 * 1. 用户长按发送按钮 → 选择时间 → 调用 [scheduleMessage]
 * 2. ScheduledTaskRunner 的轮询循环调用 [checkAndDeliver] 检查到期消息
 * 3. UI 通过 [pendingMessagesFlow] 显示 "Message scheduled for HH:MM" 横幅
 */
class PendingMessageManager(
    private val context: Context,
) {
    private val store get() = context.pendingMessageStore

    companion object {
        private const val TAG = "PendingMessage"
        private val KEY_PENDING_MESSAGES = stringPreferencesKey("pending_messages_json")
    }

    /**
     * 待发送消息列表 Flow(供 UI 显示横幅)。
     */
    val pendingMessagesFlow: Flow<List<PendingMessage>> = store.data.map { prefs ->
        val json = prefs[KEY_PENDING_MESSAGES] ?: return@map emptyList()
        try {
            AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json)
        } catch (e: Exception) {
            Logger.w(TAG, "Parse pending messages failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * 安排一条定时消息。
     *
     * @param content 消息文本
     * @param sessionId 目标会话 ID
     * @param assistantId 助手 ID
     * @param sendAtMillis 计划发送时间(毫秒时间戳)
     */
    suspend fun scheduleMessage(
        content: String,
        sessionId: String,
        assistantId: String,
        sendAtMillis: Long,
    ): PendingMessage {
        val msg = PendingMessage(
            id = "pm-${java.util.UUID.randomUUID()}",
            content = content,
            sessionId = sessionId,
            assistantId = assistantId,
            sendAtMillis = sendAtMillis,
            createdAtMillis = System.currentTimeMillis(),
        )
        store.edit { prefs ->
            val current = prefs[KEY_PENDING_MESSAGES]?.let { json ->
                try { AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json) }
                catch (_: Exception) { emptyList() }
            } ?: emptyList()
            prefs[KEY_PENDING_MESSAGES] = AppJson.encodeToString(
                ListSerializer(PendingMessage.serializer()),
                current + msg,
            )
        }
        Logger.i(TAG, "Scheduled message ${msg.id} for ${msg.sendAtMillis}")
        return msg
    }

    /**
     * 取消一条定时消息。
     */
    suspend fun cancelMessage(messageId: String) {
        store.edit { prefs ->
            val current = prefs[KEY_PENDING_MESSAGES]?.let { json ->
                try { AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json) }
                catch (_: Exception) { emptyList() }
            } ?: emptyList()
            prefs[KEY_PENDING_MESSAGES] = AppJson.encodeToString(
                ListSerializer(PendingMessage.serializer()),
                current.filter { it.id != messageId },
            )
        }
        Logger.i(TAG, "Cancelled message $messageId")
    }

    /**
     * 检查并返回所有到期的消息(从存储中移除并返回)。
     *
     * 由 ScheduledTaskRunner 的轮询循环调用,返回的消息由调用方负责发送。
     */
    suspend fun drainDueMessages(): List<PendingMessage> {
        val now = System.currentTimeMillis()
        var due: List<PendingMessage> = emptyList()
        store.edit { prefs ->
            val current = prefs[KEY_PENDING_MESSAGES]?.let { json ->
                try { AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json) }
                catch (_: Exception) { emptyList() }
            } ?: emptyList()
            due = current.filter { it.sendAtMillis <= now }
            val remaining = current.filter { it.sendAtMillis > now }
            if (remaining.isEmpty()) {
                prefs.remove(KEY_PENDING_MESSAGES)
            } else {
                prefs[KEY_PENDING_MESSAGES] = AppJson.encodeToString(
                    ListSerializer(PendingMessage.serializer()),
                    remaining,
                )
            }
        }
        if (due.isNotEmpty()) {
            Logger.i(TAG, "Drained ${due.size} due messages")
        }
        return due
    }

    /**
     * 获取所有待发送消息(不修改存储)。
     */
    suspend fun getAllPending(): List<PendingMessage> {
        val json = store.data.first()[KEY_PENDING_MESSAGES] ?: return emptyList()
        return try {
            AppJson.decodeFromString(ListSerializer(PendingMessage.serializer()), json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * 待发送的定时消息。
 */
@Serializable
data class PendingMessage(
    val id: String,
    val content: String,
    val sessionId: String,
    val assistantId: String,
    val sendAtMillis: Long,
    val createdAtMillis: Long = System.currentTimeMillis(),
)
