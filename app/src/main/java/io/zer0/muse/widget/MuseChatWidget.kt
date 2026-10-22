package io.zer0.muse.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.MainActivity
import io.zer0.muse.R
import io.zer0.muse.data.session.MessageDao
import io.zer0.muse.data.session.MuseDb
import io.zer0.muse.intent.ShareIntentHandler
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 桌面对话小部件(增强版)。
 *
 * 主题色跟随 App 主色调。支持多种桌面尺寸。
 *
 * 显示内容跟随「用户在配置页选中的助手」:每个小组件实例各存各的助手 id,
 * 取该助手最近一条 assistant 消息。此前直接取全局最近一条,选助手等于没选。
 */
class MuseChatWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = runCatching {
            GlanceAppWidgetManager(context).getAppWidgetId(id)
        }.getOrNull()
        val assistantId = appWidgetId
            ?.let { WidgetPrefs.getChatWidgetAssistant(context, it) }
            ?: "default"
        val data = queryLatestReply(context, assistantId)
        val params = buildActionParams(data.sessionId, assistantId)
        provideContent {
            MuseChatWidgetContent(data, params)
        }
    }

    private suspend fun queryLatestReply(context: Context, assistantId: String): ChatWidgetData {
        val fallback = ChatWidgetData(
            sessionId = null,
            preview = context.getString(R.string.widget_chat_no_content),
            updatedAt = 0L,
        )
        return resultOf {
            val dao: MessageDao = MuseDb.get(context).messageDao()
            val msg = withTimeoutOrNull(2_000L) {
                dao.getLastAssistantMessageForAssistant(assistantId)
            }
            if (msg != null) {
                ChatWidgetData(
                    sessionId = msg.sessionId,
                    preview = msg.content.take(MAX_PREVIEW_LEN),
                    updatedAt = msg.createdAt,
                )
            } else {
                fallback
            }
        }.onError { errMsg, _ ->
            Logger.w("MuseChatWidget", "queryLatestReply 失败: $errMsg")
        }.getOrNull() ?: fallback
    }

    private fun buildActionParams(sessionId: String?, assistantId: String): ActionParameters {
        return if (sessionId != null) {
            actionParametersOf(
                WidgetActionKey to ShareIntentHandler.WIDGET_ACTION_OPEN_SESSION,
                WidgetSessionIdKey to sessionId,
            )
        } else {
            // 没有历史消息时新建会话,但仍要带上是哪个助手 —— 否则点开就退回全局默认助手。
            actionParametersOf(
                WidgetActionKey to ShareIntentHandler.WIDGET_ACTION_NEW_SESSION,
                WidgetAssistantIdKey to assistantId,
            )
        }
    }

    private companion object {
        const val MAX_PREVIEW_LEN = 100
    }
}

private data class ChatWidgetData(
    val sessionId: String?,
    val preview: String,
    val updatedAt: Long,
)

@Composable
private fun MuseChatWidgetContent(data: ChatWidgetData, params: ActionParameters) {
    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFFF5F5F5), Color(0xFF1C1C1E)))
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>(params)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "muse",
                    style = TextStyle(
                        fontWeight = FontWeight.Bold,
                        color = ColorProvider(Color(0xFF8B5CF6), Color(0xFFA78BFA)),
                        fontSize = 16.sp,
                    ),
                )
                Text(
                    text = " 对话",
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF333333), Color(0xFFE5E5E5)),
                        fontSize = 16.sp,
                    ),
                )
            }
            Spacer(GlanceModifier.height(8.dp))
            Text(
                text = data.preview,
                style = TextStyle(
                    color = ColorProvider(Color(0xFF555555), Color(0xFFA0A0A0)),
                    fontSize = 14.sp,
                ),
                maxLines = 5,
            )
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = formatTime(data.updatedAt),
                style = TextStyle(
                    color = ColorProvider(Color(0xFF999999), Color(0xFF666666)),
                    fontSize = 10.sp,
                ),
            )
        }
    }
}

private fun formatTime(timestamp: Long): String {
    if (timestamp <= 0L) return ""
    val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

class MuseChatWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MuseChatWidget()
}

private val WidgetActionKey =
    ActionParameters.Key<String>(ShareIntentHandler.EXTRA_WIDGET_ACTION)

private val WidgetSessionIdKey =
    ActionParameters.Key<String>(ShareIntentHandler.EXTRA_WIDGET_SESSION_ID)

private val WidgetAssistantIdKey =
    ActionParameters.Key<String>(ShareIntentHandler.EXTRA_WIDGET_ASSISTANT_ID)
