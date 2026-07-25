package io.zer0.muse.widget

import android.content.Context
import android.content.SharedPreferences
import androidx.glance.appwidget.GlanceAppWidgetManager

object WidgetPrefs {
    private const val PREFS_NAME = "muse_widget_prefs"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

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

    suspend fun getChatWidgetAssistantId(context: Context): String {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(MuseChatWidget::class.java)
        val id = ids.firstOrNull() ?: return "default"
        return getChatWidgetAssistant(context, id.hashCode())
    }

    suspend fun getQuickWidgetAssistantId(context: Context): String {
        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(MuseQuickWidget::class.java)
        val id = ids.firstOrNull() ?: return "default"
        return getQuickWidgetAssistant(context, id.hashCode())
    }
}
