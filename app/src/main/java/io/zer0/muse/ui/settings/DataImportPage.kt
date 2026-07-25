package io.zer0.muse.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.`import`.ImportResult
import io.zer0.muse.data.`import`.ThirdPartyImporter
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.assistant.AssistantRepository
import io.zer0.muse.data.session.SessionRepository
import io.zer0.muse.importer.ConfigImporter
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * v1.61-A: 第三方数据导入页。
 *
 * 引导用户从 RikkaHub / Kelivo 备份 ZIP,或 CherryStudio / Chatbox JSON 导入 Provider 配置、助手、会话和消息。
 * 页面结构:
 *  1. 顶部说明卡片
 *  2. 选择来源(RikkaHub / Kelivo / CherryStudio / Chatbox),每个卡片含导出步骤折叠说明
 *  3. 选择备份文件按钮
 *  4. 导入中进度
 *  5. 导入结果(数量统计 + 错误列表)
 *  6. 底部温馨提示
 *
 * P3-1: 新增 CherryStudio / Chatbox JSON 入口,复用 [ConfigImporter](自动嗅探两种 JSON 格式)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsDataImportPage(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val settings: SettingsRepository = koinInject()
    val assistantRepo: AssistantRepository = koinInject()
    val sessionRepo: SessionRepository = koinInject()
    val configImporter: ConfigImporter = koinInject()
    val scope = rememberCoroutineScope()

    var isImporting by remember { mutableStateOf(false) }
    var importResult by remember { mutableStateOf<ImportResult?>(null) }
    // P3-1: CherryStudio / Chatbox JSON 导入结果(与 ThirdPartyImporter.ImportResult 结构不同,独立状态)
    var jsonImportResult by remember { mutableStateOf<ConfigImporter.Result?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                importResult = ThirdPartyImporter.importFromUri(
                    context = context,
                    uri = uri,
                    settings = settings,
                    assistantRepo = assistantRepo,
                    sessionRepo = sessionRepo,
                )
                isImporting = false
            }
        }
    }

    // P3-1: JSON 文件 picker,调用 ConfigImporter(自动嗅探 CherryStudio / Chatbox 格式)
    val jsonFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                isImporting = true
                jsonImportResult = configImporter.importFromUri(context, uri)
                isImporting = false
            }
        }
    }

    SettingsSubPageScaffold(title = stringResource(R.string.settings_import_page_title), onBack = onBack) {
        // ── 顶部说明卡片 ──
        item {
            Surface(
                shape = MuseShapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.CloudUpload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_import_migrate_title), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.settings_import_migrate_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // ── 第一步:了解如何导出备份 ──
        item {
            Text(
                stringResource(R.string.settings_import_step1),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            ImportSourceCard(
                title = "RikkaHub",
                description = stringResource(R.string.settings_import_rikkahub_desc),
                steps = listOf(
                    stringResource(R.string.settings_import_rikkahub_step1),
                    stringResource(R.string.settings_import_rikkahub_step2),
                    stringResource(R.string.settings_import_rikkahub_step3),
                    stringResource(R.string.settings_import_step_remember),
                ),
                onSelect = {
                    filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
            )
        }
        item {
            ImportSourceCard(
                title = "Kelivo",
                description = stringResource(R.string.settings_import_kelivo_desc),
                steps = listOf(
                    stringResource(R.string.settings_import_kelivo_step1),
                    stringResource(R.string.settings_import_kelivo_step2),
                    stringResource(R.string.settings_import_kelivo_step3),
                    stringResource(R.string.settings_import_step_remember),
                ),
                onSelect = {
                    filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
            )
        }
        // P3-1: CherryStudio / Chatbox JSON 导入入口(复用 ConfigImporter,只导入 Provider 配置)
        item {
            ImportSourceCard(
                title = stringResource(R.string.settings_import_cherrystudio_title),
                description = stringResource(R.string.settings_import_cherrystudio_desc),
                steps = listOf(
                    stringResource(R.string.settings_import_cherrystudio_step1),
                    stringResource(R.string.settings_import_cherrystudio_step2),
                    stringResource(R.string.settings_import_cherrystudio_step3),
                    stringResource(R.string.settings_import_step_remember),
                ),
                onSelect = {
                    jsonFilePicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                },
            )
        }

        // ── 第二步:选择备份文件 ──
        item {
            Text(
                stringResource(R.string.settings_import_step2_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            Button(
                onClick = {
                    filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MuseShapes.medium,
                enabled = !isImporting,
            ) {
                Icon(Icons.Outlined.FileUpload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_import_select_backup))
            }
        }

        // ── 导入中 ──
        if (isImporting) {
            item {
                Surface(
                    shape = MuseShapes.large,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.settings_importing), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ── 导入结果 ──
        importResult?.let { result ->
            item {
                ImportResultCard(result)
            }
        }

        // P3-1: CherryStudio / Chatbox JSON 导入结果
        jsonImportResult?.let { result ->
            item {
                ImportJsonResultCard(result)
            }
        }

        // ── 底部温馨提示 ──
        item {
            Text(
                stringResource(R.string.settings_import_tips),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

/**
 * 导入来源卡片,含可折叠的导出步骤说明。
 */
@Composable
private fun ImportSourceCard(
    title: String,
    description: String,
    steps: List<String>,
    onSelect: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val collapseText = stringResource(R.string.action_collapse)
    val expandText = stringResource(R.string.action_expand)
    Surface(
        shape = MuseShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = if (expanded) collapseText else expandText,
                    )
                }
            }
            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 12.dp)) {
                    steps.forEach { step ->
                        Text(
                            step,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = onSelect,
                        shape = MuseShapes.medium,
                    ) {
                        Text(stringResource(R.string.settings_import_ready))
                    }
                }
            }
        }
    }
}

/**
 * 导入结果展示卡片。
 */
@Composable
private fun ImportResultCard(result: ImportResult) {
    Surface(
        shape = MuseShapes.large,
        color = if (result.errors.isEmpty()) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_import_complete), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_import_providers_count, result.providersImported))
            Text(stringResource(R.string.settings_import_assistants_count, result.assistantsImported))
            Text(stringResource(R.string.settings_import_conversations_count, result.conversationsImported))
            Text(stringResource(R.string.settings_import_messages_count, result.messagesImported))
            if (result.errors.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.settings_import_errors_count, result.errors.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                result.errors.take(5).forEach { error ->
                    Text(
                        "• $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * P3-1: CherryStudio / Chatbox JSON 导入结果卡片。
 *
 * 与 [ImportResultCard] 分离:[ConfigImporter.Result] 只含 Provider 维度数据
 * (imported / skipped / providers 名称列表),无助手/会话/消息。
 */
@Composable
private fun ImportJsonResultCard(result: ConfigImporter.Result) {
    val hasImport = result.imported > 0
    Surface(
        shape = MuseShapes.large,
        color = if (hasImport) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.settings_import_complete), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.settings_import_providers_count, result.imported))
            Text(stringResource(R.string.settings_import_json_skipped_count, result.skipped))
            if (result.providers.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        R.string.settings_import_json_names,
                        result.providers.joinToString(", "),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (!hasImport) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.settings_import_json_no_result),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
