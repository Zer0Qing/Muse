package io.zer0.muse.data.quota

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.zer0.common.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import java.util.Calendar

private val Context.quotaDataStore: DataStore<Preferences> by preferencesDataStore(name = "muse_quota")

/**
 * Phase 4 4C: API 配额管理器 — 控制免费/付费用户的每日使用限额。
 *
 * 免费层: 10 chats/day, 3 images, 5 searches
 * Pro 层: 无限(公平使用 500/day) + 云同步
 *
 * 计数器存储在 DataStore,每天午夜重置。
 */
class QuotaManager(
    private val context: Context,
) {
    private val store get() = context.quotaDataStore

    companion object {
        private const val TAG = "QuotaManager"
        private val KEY_CHAT_COUNT = intPreferencesKey("daily_chat_count")
        private val KEY_IMAGE_COUNT = intPreferencesKey("daily_image_count")
        private val KEY_SEARCH_COUNT = intPreferencesKey("daily_search_count")
        private val KEY_RESET_DATE = longPreferencesKey("reset_date")
        private val KEY_IS_PRO = intPreferencesKey("is_pro") // 0=free, 1=pro

        // 免费层限额
        private const val FREE_CHAT_LIMIT = 10
        private const val FREE_IMAGE_LIMIT = 3
        private const val FREE_SEARCH_LIMIT = 5

        // Pro 层限额(公平使用)
        private const val PRO_CHAT_LIMIT = 500
        private const val PRO_IMAGE_LIMIT = 100
        private const val PRO_SEARCH_LIMIT = 200

        // 警告阈值(80%)
        private const val WARNING_THRESHOLD = 0.8f
    }

    /** 配额状态 Flow。 */
    val quotaStateFlow: Flow<QuotaState> = store.data.map { prefs ->
        maybeReset(prefs)
        QuotaState(
            chatCount = prefs[KEY_CHAT_COUNT] ?: 0,
            imageCount = prefs[KEY_IMAGE_COUNT] ?: 0,
            searchCount = prefs[KEY_SEARCH_COUNT] ?: 0,
            isPro = (prefs[KEY_IS_PRO] ?: 0) == 1,
            resetDate = prefs[KEY_RESET_DATE] ?: 0L,
        )
    }

    /**
     * 检查是否可以使用某类资源,如果可以则增加计数。
     *
     * @return 使用结果(允许/警告/拒绝)
     */
    suspend fun tryUse(type: QuotaType): QuotaResult {
        val state = quotaStateFlow.first()
        val (limit, current) = when (type) {
            QuotaType.CHAT -> (if (state.isPro) PRO_CHAT_LIMIT else FREE_CHAT_LIMIT) to state.chatCount
            QuotaType.IMAGE -> (if (state.isPro) PRO_IMAGE_LIMIT else FREE_IMAGE_LIMIT) to state.imageCount
            QuotaType.SEARCH -> (if (state.isPro) PRO_SEARCH_LIMIT else FREE_SEARCH_LIMIT) to state.searchCount
        }

        if (current >= limit) {
            Logger.w(TAG, "Quota exceeded: $type ($current/$limit)")
            return QuotaResult.DENIED
        }

        // 增加计数
        store.edit { prefs ->
            ensureResetDate(prefs)
            val key = when (type) {
                QuotaType.CHAT -> KEY_CHAT_COUNT
                QuotaType.IMAGE -> KEY_IMAGE_COUNT
                QuotaType.SEARCH -> KEY_SEARCH_COUNT
            }
            prefs[key] = (prefs[key] ?: 0) + 1
        }

        val newCount = current + 1
        val ratio = newCount.toFloat() / limit
        return when {
            ratio >= 1.0f -> QuotaResult.DENIED
            ratio >= WARNING_THRESHOLD -> QuotaResult.WARNING(ratio)
            else -> QuotaResult.ALLOWED
        }
    }

    /** 获取当前配额状态。 */
    suspend fun getState(): QuotaState = quotaStateFlow.first()

    /** 设置为 Pro 用户。 */
    suspend fun setPro(isPro: Boolean) {
        store.edit { it[KEY_IS_PRO] = if (isPro) 1 else 0 }
    }

    /** 每日重置检查 — 如果跨天则归零计数。 */
    private fun maybeReset(prefs: Preferences) {
        val lastReset = prefs[KEY_RESET_DATE] ?: 0L
        val today = startOfToday()
        if (lastReset < today) {
            // 跨天了,重置计数(注意:这是在 map 中的同步读,实际重置在 edit 中)
            // 这里只是检测,真正重置在下次 edit 时
        }
    }

    private fun ensureResetDate(prefs: androidx.datastore.preferences.core.MutablePreferences) {
        val lastReset = prefs[KEY_RESET_DATE] ?: 0L
        val today = startOfToday()
        if (lastReset < today) {
            prefs[KEY_CHAT_COUNT] = 0
            prefs[KEY_IMAGE_COUNT] = 0
            prefs[KEY_SEARCH_COUNT] = 0
            prefs[KEY_RESET_DATE] = today
        }
    }

    private fun startOfToday(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}

/** 配额类型。 */
enum class QuotaType {
    CHAT, IMAGE, SEARCH
}

/** 配额状态。 */
@Serializable
data class QuotaState(
    val chatCount: Int = 0,
    val imageCount: Int = 0,
    val searchCount: Int = 0,
    val isPro: Boolean = false,
    val resetDate: Long = 0L,
) {
    fun chatLimit(isPro: Boolean = this.isPro) = if (isPro) 500 else 10
    fun imageLimit(isPro: Boolean = this.isPro) = if (isPro) 100 else 3
    fun searchLimit(isPro: Boolean = this.isPro) = if (isPro) 200 else 5

    fun chatUsagePercent() = (chatCount.toFloat() / chatLimit() * 100).toInt().coerceAtMost(100)
    fun imageUsagePercent() = (imageCount.toFloat() / imageLimit() * 100).toInt().coerceAtMost(100)
    fun searchUsagePercent() = (searchCount.toFloat() / searchLimit() * 100).toInt().coerceAtMost(100)
}

/** 配额检查结果。 */
sealed class QuotaResult {
    /** 允许使用。 */
    data object ALLOWED : QuotaResult()
    /** 接近限额(80%+)。 */
    data class WARNING(val ratio: Float) : QuotaResult()
    /** 超出限额,拒绝使用。 */
    data object DENIED : QuotaResult()
}
