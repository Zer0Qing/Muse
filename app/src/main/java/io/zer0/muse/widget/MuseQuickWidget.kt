package io.zer0.muse.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.zer0.muse.MainActivity
import io.zer0.muse.R
import io.zer0.muse.intent.ShareIntentHandler

/**
 * 桌面快捷小部件(增强版)。
 *
 * 主题色跟随 App 主色调(紫色)。支持多种桌面尺寸。
 */
class MuseQuickWidget : GlanceAppWidget() {

    override val sizeMode: SizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            MuseQuickWidgetContent()
        }
    }
}

@Composable
private fun MuseQuickWidgetContent() {
    val context = LocalContext.current
    val accentColor = Color(0xFF8B5CF6)

    GlanceTheme {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFFF5F5F5), Color(0xFF1C1C1E)))
                .padding(12.dp),
        ) {
            Text(
                text = "Muse",
                style = TextStyle(
                    fontWeight = FontWeight.Bold,
                    color = ColorProvider(accentColor, Color(0xFFA78BFA)),
                    fontSize = 16.sp,
                ),
            )
            Spacer(GlanceModifier.height(8.dp))
            Text(
                text = context.getString(R.string.widget_new_session),
                modifier = GlanceModifier.clickable(
                    actionStartActivity<MainActivity>(
                        actionParametersOf(
                            WidgetActionKey to ShareIntentHandler.WIDGET_ACTION_NEW_SESSION,
                        ),
                    ),
                ),
                style = TextStyle(
                    color = ColorProvider(accentColor, Color(0xFFA78BFA)),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                ),
            )
            Spacer(GlanceModifier.height(8.dp))
            Text(
                text = context.getString(R.string.widget_recent_sessions),
                modifier = GlanceModifier.clickable(
                    actionStartActivity<MainActivity>(
                        actionParametersOf(
                            WidgetActionKey to ShareIntentHandler.WIDGET_ACTION_OPEN_CHATS,
                        ),
                    ),
                ),
                style = TextStyle(
                    color = ColorProvider(accentColor, Color(0xFFA78BFA)),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                ),
            )
            Spacer(GlanceModifier.height(8.dp))
            Text(
                text = "🎤 快速语音输入",
                modifier = GlanceModifier.clickable(
                    actionStartActivity<MainActivity>(
                        actionParametersOf(
                            WidgetActionKey to ShareIntentHandler.WIDGET_ACTION_NEW_SESSION,
                        ),
                    ),
                ),
                style = TextStyle(
                    color = ColorProvider(accentColor, Color(0xFFA78BFA)),
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                ),
            )
        }
    }
}

class MuseQuickWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MuseQuickWidget()
}

private val WidgetActionKey =
    ActionParameters.Key<String>(ShareIntentHandler.EXTRA_WIDGET_ACTION)
