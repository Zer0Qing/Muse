package io.zer0.muse.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.common.settings.SettingsGroupDivider
import io.zer0.muse.ui.common.settings.SettingsItemRow
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.web.CustomSearchEngine
import io.zer0.muse.web.WebSearchConfig
import kotlinx.coroutines.launch

/**
 * 自定义搜索引擎设置块。
 *
 * 数据层是 [WebSearchConfig.customEngines]，这里只负责增删改与启停。
 * Auto 模式会把启用的引擎排在链路最前面（见 CompositeWebSearchService.buildProviderChain）。
 */
@Composable
internal fun CustomSearchEnginesSection(webSearchConfig: WebSearchConfig, settings: SettingsRepository) {
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<CustomSearchEngine?>(null) }
    val engines = webSearchConfig.customEngines

    SectionLabel(stringResource(R.string.search_custom_engines_section))
    Column(modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = 4.dp)) {
        Text(
            text = stringResource(R.string.search_custom_engines_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
        if (engines.isEmpty()) {
            SettingsItemRow(
                icon = MuseIcons.search,
                title = stringResource(R.string.search_custom_engine_empty),
                subtitle = stringResource(R.string.search_custom_engine_add),
                onClick = { editing = newEngine() },
            )
        } else {
            engines.forEachIndexed { index, engine ->
                if (index > 0) SettingsGroupDivider()
                CustomEngineRow(
                    engine = engine,
                    enabled = engine.enabled,
                    onToggle = { enabled ->
                        val updated =
                            engines.toMutableList().also {
                                it[index] = engine.copy(enabled = enabled)
                            }
                        scope.launch {
                            settings.saveWebSearchConfig(webSearchConfig.copy(customEngines = updated))
                        }
                    },
                )
            }
        }
    }

    SettingsGroup(modifier = Modifier.padding(top = 8.dp)) {
        SettingsItemRow(
            icon = MuseIcons.plus,
            title = stringResource(R.string.search_custom_engine_add),
            subtitle = "",
            onClick = { editing = newEngine() },
        )
    }

    editing?.let { draft ->
        CustomEngineEditor(
            initial = draft,
            onDismiss = { editing = null },
            onSave = { saved ->
                val updated =
                    engines.toMutableList().also { list ->
                        val at = list.indexOfFirst { it.id == saved.id }
                        if (at >= 0) list[at] = saved else list.add(saved)
                    }
                scope.launch {
                    settings.saveWebSearchConfig(webSearchConfig.copy(customEngines = updated))
                    MuseToast.show("已保存")
                }
                editing = null
            },
        )
    }
}

/** 新引擎的默认值：给一份可直接改的 POST 模板。 */
private fun newEngine(): CustomSearchEngine = CustomSearchEngine(
    id = "custom_" + System.currentTimeMillis(),
    name = "",
    endpoint = "",
    method = "POST",
    bodyTemplate = "{\"query\":\"{query}\",\"count\":{count}}",
    resultsPath = "results",
)

/**
 * 编辑弹窗。字段不多，一屏放得下，不做多级页面。
 */
@Composable
private fun CustomEngineEditor(initial: CustomSearchEngine, onDismiss: () -> Unit, onSave: (CustomSearchEngine) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var endpoint by remember { mutableStateOf(initial.endpoint) }
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var method by remember { mutableStateOf(initial.method) }
    var body by remember { mutableStateOf(initial.bodyTemplate) }
    var resultsPath by remember { mutableStateOf(initial.resultsPath) }

    MuseDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.search_custom_engine_add),
        content = {
            CustomEngineFields(
                name = name,
                onNameChange = { name = it },
                endpoint = endpoint,
                onEndpointChange = { endpoint = it },
                apiKey = apiKey,
                onApiKeyChange = { apiKey = it },
                method = method,
                onMethodChange = { method = it },
                body = body,
                onBodyChange = { body = it },
                resultsPath = resultsPath,
                onResultsPathChange = { resultsPath = it },
            )
        },
        confirmText = stringResource(R.string.action_save),
        onConfirm = {
            if (endpoint.isBlank()) {
                MuseToast.show("请求地址不能为空")
            } else {
                onSave(
                    initial.copy(
                        name = name.ifBlank { "Custom" },
                        endpoint = endpoint.trim(),
                        apiKey = apiKey.trim(),
                        method = method,
                        bodyTemplate = body,
                        resultsPath = resultsPath.trim(),
                    ),
                )
            }
        },
        dismissText = stringResource(R.string.action_cancel),
        onDismiss = onDismiss,
    )
}

/** 列表里的一行自定义引擎。 */
@Composable
private fun CustomEngineRow(engine: CustomSearchEngine, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = engine.name.ifBlank { engine.endpoint },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${engine.method.uppercase()} ${engine.endpoint}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.size(40.dp),
        )
    }
}

/** 编辑弹窗里的单个输入项。 */
@Composable
private fun EngineTextField(value: String, onValueChange: (String) -> Unit, labelRes: Int) {
    MuseTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(stringResource(labelRes)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** 编辑弹窗的输入区，单独成函以压住两个函数的长度。 */
@Composable
@Suppress("LongParameterList")
private fun CustomEngineFields(
    name: String,
    onNameChange: (String) -> Unit,
    endpoint: String,
    onEndpointChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    method: String,
    onMethodChange: (String) -> Unit,
    body: String,
    onBodyChange: (String) -> Unit,
    resultsPath: String,
    onResultsPathChange: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EngineTextField(
            value = name,
            onValueChange = onNameChange,
            labelRes = R.string.search_custom_engine_name,
        )
        EngineTextField(
            value = endpoint,
            onValueChange = onEndpointChange,
            labelRes = R.string.search_custom_engine_endpoint,
        )
        EngineTextField(
            value = apiKey,
            onValueChange = onApiKeyChange,
            labelRes = R.string.search_custom_engine_key,
        )
        MethodPicker(method = method, onMethodChange = onMethodChange)
        MuseTextField(
            value = body,
            onValueChange = onBodyChange,
            placeholder = { Text(stringResource(R.string.search_custom_engine_body)) },
            modifier = Modifier.fillMaxWidth(),
        )
        MuseTextField(
            value = resultsPath,
            onValueChange = onResultsPathChange,
            placeholder = { Text(stringResource(R.string.search_custom_engine_results_path)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** GET / POST 二选一。 */
@Composable
private fun MethodPicker(method: String, onMethodChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.search_custom_engine_method),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        listOf("GET", "POST").forEach { m ->
            val selected = method.equals(m, ignoreCase = true)
            Text(
                text = m,
                style = MaterialTheme.typography.labelMedium,
                color =
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier =
                Modifier
                    .clickable { onMethodChange(m) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
