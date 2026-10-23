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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.assistant.AssistantEntity
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.icons.MuseIcons
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
                        title = stringResource(R.string.widget_chat_pick_assistant),
                        // v2.6.7 fix (AUDIT F12): 读已存值作初始选中。
                        initialSelectedId = WidgetPrefs.getChatWidgetAssistant(this, appWidgetId),
                        onAssistantSelected = { assistantId ->
                            WidgetPrefs.saveChatWidgetAssistant(this, appWidgetId, assistantId)
                            val resultIntent = Intent().putExtra(
                                AppWidgetManager.EXTRA_APPWIDGET_ID,
                                appWidgetId,
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
    /** v2.6.7 fix (AUDIT F12): 初始选中值。旧实现写死 "default"，
     * 重配置时直接点确认就把用户原选择覆盖成 default。 */
    initialSelectedId: String = "default",
    onAssistantSelected: (String) -> Unit,
) {
    val repo: AssistantRepository = koinInject()
    var assistants by remember { mutableStateOf<List<AssistantEntity>>(emptyList()) }
    var selectedId by remember { mutableStateOf(initialSelectedId.ifBlank { "default" }) }
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
                    Icon(
                        MuseIcons.check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = assistant.name.ifBlank {
                        stringResource(R.string.chat_delegate_unnamed_assistant)
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        MuseCapsuleButton(
            text = stringResource(R.string.common_confirm),
            // v2.6.7 fix (AUDIT F12): 列表为空时不允许确认 ——
            // 旧实现会静默存下 "default"，用户以为选了。
            onClick = { if (assistants.isNotEmpty()) onAssistantSelected(selectedId) },
            enabled = assistants.isNotEmpty(),
            modifier = Modifier.padding(top = 16.dp),
            fillWidth = false,
        )
    }
}
