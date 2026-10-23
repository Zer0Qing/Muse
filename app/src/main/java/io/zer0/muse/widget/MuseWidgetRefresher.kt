package io.zer0.muse.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import io.zer0.common.Logger

/**
 * v2.6.7 fix (AUDIT F12): 桌面小组件的主动刷新入口。
 *
 * 背景：Glance 的 `provideGlance` 只在首次添加、配置完成、系统广播 APPWIDGET_UPDATE
 * 时执行。`muse_chat_widget_info.xml` 没设 `updatePeriodMillis`，全仓也没有任何地方
 * 对 `MuseChatWidget` 调用 `update/updateAll` —— 于是组件预览长期停在添加那一刻的内容，
 * 之后聊多少都不会变。
 *
 * 这里提供统一入口：生成完成后调用一次即可让两个小组件重新渲染。
 * 失败只记日志：组件刷新不能影响对话主链路。
 */
object MuseWidgetRefresher {

    private const val TAG = "MuseWidgetRefresher"

    /** 刷新两个 Glance 小组件（对话 + 快捷）。多个实例会一起更新。 */
    suspend fun refreshAll(context: Context) {
        // updateAll 是 GlanceAppWidget 的扩展，内部会遍历该 widget 的所有实例。
        runCatching { MuseChatWidget().updateAll(context) }
            .onFailure { Logger.w(TAG, "刷新对话小组件失败: ${it.message}") }
        runCatching { MuseQuickWidget().updateAll(context) }
            .onFailure { Logger.w(TAG, "刷新快捷小组件失败: ${it.message}") }
    }

    /** 仅刷新对话小组件（消息变化时用）。 */
    suspend fun refreshChatWidget(context: Context) {
        runCatching { MuseChatWidget().updateAll(context) }
            .onFailure { Logger.w(TAG, "刷新对话小组件失败: ${it.message}") }
    }

    /** 供诊断：当前已添加的小组件实例数。 */
    suspend fun instanceCount(context: Context): Int = runCatching {
        GlanceAppWidgetManager(context).getGlanceIds(MuseChatWidget::class.java).size
    }.getOrDefault(0)
}
