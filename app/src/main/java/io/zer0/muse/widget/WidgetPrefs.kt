package io.zer0.muse.widget

import android.content.Context
import android.content.SharedPreferences

/**
 * 小组件按实例持久化用户选择的助手。
 *
 * 键带 appWidgetId:同一桌面可以放多个小组件,各选各的助手。
 * 读取方用 [MuseChatWidget]/[MuseQuickWidget] 里 `provideGlance` 拿到的 GlanceId
 * 换出 appWidgetId 后按实例取,不要用「取第一个实例」的近似做法。
 */
object WidgetPrefs {
    private const val PREFS_NAME = "muse_widget_prefs"

    private fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun saveChatWidgetAssistant(context: Context, appWidgetId: Int, assistantId: String) {
        prefs(context).edit().putString("chat_widget_assistant_$appWidgetId", assistantId).apply()
    }

    fun getChatWidgetAssistant(context: Context, appWidgetId: Int): String {
        return prefs(context).getString("chat_widget_assistant_$appWidgetId", "default") ?: "default"
    }

    fun saveQuickWidgetAssistant(context: Context, appWidgetId: Int, assistantId: String) {
        prefs(context).edit().putString("quick_widget_assistant_$appWidgetId", assistantId).apply()
    }

    fun getQuickWidgetAssistant(context: Context, appWidgetId: Int): String {
        return prefs(context).getString("quick_widget_assistant_$appWidgetId", "default") ?: "default"
    }
}
