package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.zer0.muse.R
import io.zer0.muse.mcp.McpPrompt
import io.zer0.muse.mcp.McpRegistry
import io.zer0.muse.mcp.McpRegistry.QueryResult
import io.zer0.muse.mcp.McpResource
import io.zer0.muse.mcp.McpServerConfig
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseListItem
import io.zer0.muse.ui.theme.MuseSpacing
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal enum class McpContentBrowserKind { RESOURCES, PROMPTS }

private sealed interface McpContentEntry {
    val name: String
    val description: String?

    data class Resource(val item: McpResource) : McpContentEntry {
        override val name = item.name ?: item.uri
        override val description = item.description
    }

    data class Prompt(val item: McpPrompt) : McpContentEntry {
        override val name = item.name
        override val description = item.description
    }
}

private data class McpContentPreview(val text: String, val otherContentTypes: List<String>)

private data class McpContentDialogState(
    val list: QueryResult<List<McpContentEntry>>?,
    val selected: McpContentEntry?,
    val preview: QueryResult<McpContentPreview>?,
    val working: Boolean,
    val args: Map<String, String>,
    val emptyLabel: String,
)

private data class McpContentDialogActions(
    val onSelect: (McpContentEntry) -> Unit,
    val onRead: (McpContentEntry) -> Unit,
    val onRetry: () -> Unit,
    val onArgumentChange: (String, String) -> Unit,
    val onDraft: ((String) -> Unit)?,
    val onBack: () -> Unit,
    val onDismiss: () -> Unit,
)

@Composable
internal fun McpContentBrowser(
    server: McpServerConfig,
    kind: McpContentBrowserKind,
    registry: McpRegistry,
    onDismiss: () -> Unit,
    onDraft: ((String) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var list by remember(server.id, kind) { mutableStateOf<QueryResult<List<McpContentEntry>>?>(null) }
    var selected by remember(server.id, kind) { mutableStateOf<McpContentEntry?>(null) }
    var preview by remember(server.id, kind) { mutableStateOf<QueryResult<McpContentPreview>?>(null) }
    var working by remember(server.id, kind) { mutableStateOf(false) }
    var retry by remember(server.id, kind) { mutableStateOf(0) }
    val args = remember(selected) { mutableStateMapOf<String, String>() }

    LaunchedEffect(server.id, kind, retry) {
        list = null
        list = when (kind) {
            McpContentBrowserKind.RESOURCES -> registry.listResources(server.id).map { items ->
                items.map { McpContentEntry.Resource(it) }
            }
            McpContentBrowserKind.PROMPTS -> registry.listPrompts(server.id).map { items ->
                items.map { McpContentEntry.Prompt(it) }
            }
        }
    }
    val readEntry: (McpContentEntry) -> Unit = { entry ->
        preview = null
        working = true
        scope.launch {
            preview = loadMcpContentPreview(server.id, entry, registry, args)
            working = false
        }
    }
    McpContentDialog(
        kind = kind,
        state = McpContentDialogState(list, selected, preview, working, args, stringResource(R.string.mcp_content_empty)),
        actions = McpContentDialogActions(
            onSelect = { entry ->
                selected = entry
                if (entry is McpContentEntry.Resource || (entry is McpContentEntry.Prompt && entry.item.arguments.isEmpty())) {
                    readEntry(entry)
                }
            },
            onRead = readEntry,
            onRetry = { retry++ },
            onArgumentChange = { name, value -> args[name] = value },
            onDraft = onDraft,
            onBack = {
                selected = null
                preview = null
            },
            onDismiss = onDismiss,
        ),
    )
}

private suspend fun loadMcpContentPreview(
    serverId: String,
    entry: McpContentEntry,
    registry: McpRegistry,
    args: Map<String, String>,
): QueryResult<McpContentPreview> = when (entry) {
    is McpContentEntry.Resource -> registry.readResource(serverId, entry.item.uri).map { contents ->
        McpContentPreview(
            text = contents.mapNotNull { it.text }.joinToString("\n\n"),
            otherContentTypes = contents.filter { it.blob != null }.map {
                it.mimeType ?: "application/octet-stream"
            }.distinct(),
        )
    }
    is McpContentEntry.Prompt -> registry.getPrompt(serverId, entry.item.name, args.filterValues(String::isNotBlank)).map { result ->
        val text = result.messages.mapNotNull { message ->
            val content = message.content as? JsonObject ?: return@mapNotNull null
            val resource = content["resource"] as? JsonObject
            (content["text"] ?: resource?.get("text")).scalar()?.let { message.role + ": " + it }
        }.joinToString("\n\n")
        val media = result.messages.mapNotNull { (it.content as? JsonObject)?.get("type").scalar() }
            .filter { it != "text" }.distinct()
        McpContentPreview(text, media)
    }
}

@Composable
private fun McpContentDialog(
    kind: McpContentBrowserKind,
    state: McpContentDialogState,
    actions: McpContentDialogActions,
) {
    val details = state.selected
    val loadedPreview = (state.preview as? QueryResult.Success)?.value
    val canAdd = actions.onDraft != null && !loadedPreview?.text.isNullOrBlank()
    MuseDialog(
        title = details?.name ?: stringResource(kind.label()),
        onDismissRequest = actions.onDismiss,
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(MuseSpacing.sm)) {
                if (details == null) {
                    McpContentList(state.list, state.emptyLabel, actions)
                } else {
                    McpContentDetails(details, state, actions)
                }
            }
        },
        confirmText = stringResource(
            when {
                canAdd -> R.string.mcp_add_to_chat
                details != null && state.preview !is QueryResult.Success -> R.string.mcp_content_preview
                else -> R.string.common_close
            },
        ),
        confirmEnabled = !state.working && (
            details !is McpContentEntry.Prompt || state.preview is QueryResult.Success ||
                details.item.arguments.none { it.required && state.args[it.name].isNullOrBlank() }
            ),
        onConfirm = {
            when {
                canAdd -> actions.onDraft?.invoke(loadedPreview?.text.orEmpty())
                details != null && state.preview !is QueryResult.Success -> actions.onRead(details)
                else -> actions.onDismiss()
            }
        },
        dismissText = if (details != null && !state.working) stringResource(R.string.mcp_content_back) else null,
        onDismiss = {
            if (details != null && !state.working) actions.onBack()
            else actions.onDismiss()
        },
    )
}

@Composable
private fun McpContentList(
    list: QueryResult<List<McpContentEntry>>?,
    emptyLabel: String,
    actions: McpContentDialogActions,
) {
    when (val result = list) {
        null -> MuseSpinner()
        is QueryResult.Failure -> {
            Text(stringResource(result.label()), style = MaterialTheme.typography.bodyMedium)
            if (result != QueryResult.Failure.UNSUPPORTED) {
                MuseCapsuleButton(
                    text = stringResource(R.string.common_retry),
                    onClick = actions.onRetry,
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }
        }
        is QueryResult.Success -> {
            if (result.value.isEmpty()) Text(emptyLabel)
            result.value.forEach { entry ->
                MuseListItem(
                    onClick = { actions.onSelect(entry) },
                    headlineContent = { Text(entry.name) },
                    supportingContent = entry.description?.takeIf(String::isNotBlank)?.let { description ->
                        { Text(description) }
                    },
                )
            }
        }
    }
}

@Composable
private fun McpContentDetails(
    details: McpContentEntry,
    state: McpContentDialogState,
    actions: McpContentDialogActions,
) {
    if (details is McpContentEntry.Prompt && state.preview !is QueryResult.Success && !state.working) {
        details.item.arguments.forEach { argument ->
            SettingField(
                label = argument.name,
                value = state.args[argument.name].orEmpty(),
                onValueChange = { actions.onArgumentChange(argument.name, it) },
                placeholder = argument.description,
            )
        }
    }
    when (val result = state.preview) {
        null -> if (state.working) MuseSpinner()
        is QueryResult.Failure -> Text(stringResource(result.label()))
        is QueryResult.Success -> {
            Text(result.value.text.ifBlank { state.emptyLabel }, style = MaterialTheme.typography.bodyMedium)
            result.value.otherContentTypes.forEach { mime ->
                Text(
                    stringResource(R.string.mcp_content_nontext, mime),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (actions.onDraft == null && result.value.text.isNotBlank()) {
                val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
                MuseCapsuleButton(
                    text = stringResource(R.string.mcp_copy_text),
                    onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(result.value.text))
                    },
                    variant = IosCapsuleButtonVariant.Text,
                    fillWidth = false,
                )
            }
        }
    }
}

private fun McpContentBrowserKind.label(): Int = when (this) {
    McpContentBrowserKind.RESOURCES -> R.string.mcp_resource_browser
    McpContentBrowserKind.PROMPTS -> R.string.mcp_prompt_browser
}

private fun QueryResult.Failure.label(): Int = when (this) {
    QueryResult.Failure.DISCONNECTED -> R.string.mcp_status_disconnected
    QueryResult.Failure.UNSUPPORTED -> R.string.mcp_unsupported_capability
    QueryResult.Failure.REQUEST_FAILED -> R.string.mcp_content_failed
}

private fun JsonElement?.scalar(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private inline fun <T, R> QueryResult<T>.map(convert: (T) -> R): QueryResult<R> = when (this) {
    is QueryResult.Success -> QueryResult.Success(convert(value))
    is QueryResult.Failure -> this
}
