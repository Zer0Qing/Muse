package io.zer0.muse.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
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

class MuseChatWidgetConfigureActivity : ComponentActivity() {
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
                        title = "选择显示的助手",
                        onAssistantSelected = { assistantId ->
                            WidgetPrefs.saveChatWidgetAssistant(this, appWidgetId, assistantId)
                            val resultIntent = Intent().putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId,
                            )
                            setResult(Activity.RESULT_OK, resultIntent)
                            val updateIntent = Intent(
                                AppWidgetManager.ACTION_APPWIDGET_UPDATE,
                                null as android.net.Uri?,
                                this@MuseChatWidgetConfigureActivity,
                                MuseChatWidgetReceiver::class.java,
                            )
                            updateIntent.putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_IDS,
                                intArrayOf(appWidgetId),
                            )
                            this@MuseChatWidgetConfigureActivity.sendBroadcast(updateIntent)
                            finish()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun WidgetAssistantPicker(
    title: String,
    onAssistantSelected: (String) -> Unit,
) {
    val repo: AssistantRepository = koinInject()
    var assistants by remember { mutableStateOf<List<AssistantEntity>>(emptyList()) }
    var selectedId by remember { mutableStateOf("default") }
    LaunchedEffect(Unit) {
        repo.observeAll.collect { assistants = it }
    }
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        assistants.forEach { assistant ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { selectedId = assistant.id }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (selectedId == assistant.id) {
                    Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = assistant.name.ifBlank { "(未命名)" },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Button(
            onClick = { onAssistantSelected(selectedId) },
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("确认")
        }
    }
}
