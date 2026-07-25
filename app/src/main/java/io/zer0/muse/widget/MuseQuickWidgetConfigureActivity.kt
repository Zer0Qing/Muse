package io.zer0.muse.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.theme.MuseTheme
import org.koin.compose.koinInject

class MuseQuickWidgetConfigureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setContent {
            MuseTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WidgetAssistantPicker(
                        title = "选择快捷操作对应的助手",
                        onAssistantSelected = { assistantId ->
                            WidgetPrefs.saveQuickWidgetAssistant(this, appWidgetId, assistantId)
                            val resultIntent = Intent().putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId,
                            )
                            setResult(Activity.RESULT_OK, resultIntent)
                            val updateIntent = Intent(
                                AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                                null as Uri?,
                                this@MuseQuickWidgetConfigureActivity,
                                MuseQuickWidgetReceiver::class.java,
                            )
                            updateIntent.putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_IDS,
                                intArrayOf(appWidgetId),
                            )
                            this@MuseQuickWidgetConfigureActivity.sendBroadcast(updateIntent)
                            finish()
                        },
                    )
                }
            }
        }
    }
}
