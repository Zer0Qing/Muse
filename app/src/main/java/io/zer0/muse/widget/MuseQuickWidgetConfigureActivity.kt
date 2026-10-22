package io.zer0.muse.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.ui.theme.MuseTheme

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
                        title = stringResource(R.string.widget_quick_pick_assistant),
                        onAssistantSelected = { assistantId ->
                            WidgetPrefs.saveQuickWidgetAssistant(this, appWidgetId, assistantId)
                            val resultIntent = Intent().putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID,
                                appWidgetId,
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
