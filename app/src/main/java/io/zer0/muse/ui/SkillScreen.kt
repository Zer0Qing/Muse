package io.zer0.muse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import io.zer0.muse.ui.common.IosSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import io.zer0.muse.ui.common.IosTopBar
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.common.AppJson
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.ui.common.EmptyState
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.SkillExecutor
import io.zer0.muse.tools.SkillImporter
import io.zer0.muse.ui.common.MuseAlertDialog
import io.zer0.muse.ui.common.MuseDialog
import io.zer0.muse.ui.common.MuseToast
import io.zer0.muse.ui.common.SectionLabel
import io.zer0.muse.ui.common.SettingsGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MuseMonoFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.compose.koinInject

/**
 * Phase 8.8: Skill(技能/工具)管理页。
 *
 * 列出全部 Skill,支持启用/禁用切换与查看详情(参数 Schema / id / 是否内置)。
 * 视觉沿用 muse warm-paper 风格(iOS 风格 SettingsGroup + SectionLabel)。
 *
 * 内置 Skill 由 [MuseApp] 启动时 seed(见 [SkillExecutor.BUILT_IN_SKILLS]),
 * 此页只读展示其元信息,不可删除/编辑(自定义 Skill 后续可扩展)。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillScreen(
    onBack: () -> Unit,
    skillRepository: SkillRepository = koinInject(),
) {
    val skills by skillRepository.observeAll.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var detailTarget by remember { mutableStateOf<SkillEntity?>(null) }
    var deleteConfirmTarget by remember { mutableStateOf<SkillEntity?>(null) }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    val builtInIds = remember {
        SkillExecutor.BUILT_IN_SKILLS.map { it.id }.toSet()
    }

    // v0.23: .skill.json 文件导入 launcher
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            try {
                val text = withContext(Dispatchers.IO) {
                    // v1.73: 用 use{} 确保 InputStream/BufferedReader 关闭,避免 FD 泄漏
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        // v1.114: 限制读取 10MB,防止超大文件 OOM
                        val MAX_READ_BYTES = 10L * 1024 * 1024
                        val sb = StringBuilder()
                        val buffer = CharArray(8192)
                        var total = 0L
                        input.bufferedReader().use { reader ->
                            while (true) {
                                val read = reader.read(buffer)
                                if (read <= 0) break
                                total += read
                                if (total > MAX_READ_BYTES) {
                                    error("文件过大,超过 ${MAX_READ_BYTES / 1024 / 1024}MB 限制")
                                }
                                sb.append(buffer, 0, read)
                            }
                        }
                        sb.toString()
                    } ?: ""
                }
                when (val result = SkillImporter.parse(text)) {
                    is SkillImporter.Result.Ok -> {
                        // M-SKUI1: 用 resultOf 替代 runCatching,避免吞 CancellationException
                        resultOf { skillRepository.upsert(result.skill) }
                            .onSuccess { importMessage = context.getString(R.string.skill_imported, result.skill.name) }
                            .onError { msg, _ -> importMessage = context.getString(R.string.skill_import_failed, msg) }
                    }
                    is SkillImporter.Result.Err -> importMessage = result.reason
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // H-SKUI1: 捕获导入流程异常,设置错误提示避免崩溃
                importMessage = context.getString(R.string.skill_import_error, e.message?.take(80))
            } finally {
                importing = false
            }
        }
    }

    Scaffold(
        topBar = {
            IosTopBar(
                title = stringResource(R.string.skill_management),
                onBack = onBack,
                actions = {
                    IconButton(
                        onClick = {
                            runCatching { importLauncher.launch(arrayOf("application/json", "text/plain")) }
                                .onFailure { importMessage = context.getString(R.string.skill_open_picker_failed, it.message) }
                        },
                        enabled = !importing,
                    ) {
                        Icon(Icons.Default.FileUpload, contentDescription = stringResource(R.string.skill_import_cd))
                    }
                },
            )
        },
    ) { innerPadding ->
        val skillList = skills
        when {
            skillList == null -> {
                Box(Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            else -> {
                // v1.71: 委托属性无法 smart-cast,用局部变量捕获非空值,消除重复 !!
                // L-SK1: 用局部 val 捕获后编译器在 else 分支自动 smart-cast 为非空,无需 !!
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.skill_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    items(skillList, key = { it.id }) { skill ->
                        SkillRow(
                            skill = skill,
                            isBuiltIn = skill.id in builtInIds,
                            onToggleEnabled = { enabled ->
                                scope.launch {
                                    // M-SKUI1: 用 resultOf 替代 runCatching,避免吞 CancellationException
                                    resultOf { skillRepository.setEnabled(skill.id, enabled) }
                                        .onError { msg, _ -> MuseToast.show(context.getString(R.string.skill_operation_failed, msg)) }
                                }
                            },
                            onShowDetail = { detailTarget = skill },
                        )
                    }
                    if (skillList.isEmpty()) {
                        item {
                            EmptyState(
                                icon = Icons.Outlined.Extension,
                                title = stringResource(R.string.skill_no_skill_title),
                                subtitle = stringResource(R.string.skill_no_skill_subtitle),
                            )
                        }
                    }
                }
            }
        }
    }

    detailTarget?.let { skill ->
        SkillDetailDialog(
            skill = skill,
            isBuiltIn = skill.id in builtInIds,
            onDismiss = { detailTarget = null },
            onDelete = if (skill.id in builtInIds) null else {
                {
                    detailTarget = null
                    deleteConfirmTarget = skill
                }
            },
        )
    }

    deleteConfirmTarget?.let { skill ->
        MuseDialog(
            onDismissRequest = { deleteConfirmTarget = null },
            title = stringResource(R.string.skill_delete_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.skill_delete_confirm_message, skill.name.ifBlank { skill.id }),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.skill_delete),
            onConfirm = {
                deleteConfirmTarget = null
                scope.launch {
                    resultOf { skillRepository.delete(skill.id) }
                        .onError { msg, _ -> MuseToast.show(context.getString(R.string.skill_delete_failed_ui, msg)) }
                }
            },
            dismissText = stringResource(R.string.skill_close),
            onDismiss = { deleteConfirmTarget = null },
            destructive = true,
        )
    }

    importMessage?.let { msg ->
        MuseAlertDialog(
            onDismissRequest = { importMessage = null },
            title = stringResource(R.string.skill_import_result),
            message = msg,
            confirmText = stringResource(R.string.skill_got_it),
        )
    }

    if (importing) {
        MuseDialog(
            onDismissRequest = { /* 不可中断 */ },
            title = stringResource(R.string.skill_importing),
            content = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.skill_importing_detail), style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmText = "",
            onConfirm = {},
            onDismiss = {},
        )
    }
}

@Composable
private fun SkillRow(
    skill: SkillEntity,
    isBuiltIn: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onShowDetail: () -> Unit,
) {
    val unnamedText = stringResource(R.string.skill_unnamed)
    val stateLabel = if (skill.enabled) stringResource(R.string.skill_enabled) else stringResource(R.string.skill_disabled)
    // 预提取 semantics contentDescription(semantics lambda 非 @Composable,不能直接调用 stringResource)
    val rowCd = stringResource(R.string.skill_row_cd, skill.name.ifBlank { unnamedText }, stateLabel)
    SettingsGroup {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onShowDetail() }
                .semantics {
                    contentDescription = rowCd
                }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = skill.name.ifBlank { unnamedText },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (skill.enabled) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.outline,
                    )
                    if (!skill.enabled) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.skill_disabled),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    if (isBuiltIn) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = stringResource(R.string.skill_built_in),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (skill.description.isNotEmpty()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            IosSwitch(
                checked = skill.enabled,
                onCheckedChange = onToggleEnabled,
                modifier = Modifier.semantics {
                    stateDescription = stateLabel
                },
            )
            IconButton(
                onClick = onShowDetail,
                modifier = Modifier.size(MuseIconSizes.touchTarget),
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = stringResource(R.string.skill_detail_cd),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun SkillDetailDialog(
    skill: SkillEntity,
    isBuiltIn: Boolean,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val unnamedText = stringResource(R.string.skill_unnamed)
    val builtInSeedText = stringResource(R.string.skill_built_in_seed)
    val customText = stringResource(R.string.skill_custom)
    MuseDialog(
        onDismissRequest = onDismiss,
        title = skill.name.ifBlank { unnamedText },
        content = {
            // v1.0.7 修复崩溃:MuseDialog 的 content 区已自带 verticalScroll,嵌套会崩溃
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (skill.description.isNotEmpty()) {
                    Text(
                        text = skill.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.skill_parameters_schema),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                // v1.???: 把原始 JSON Schema 解析成可读参数列表
                ParameterSchemaView(
                    parametersJson = skill.parametersJson,
                    requiredSet = skill.requiredJson
                        .takeIf { it.isNotBlank() }
                        ?.let { json ->
                            resultOf {
                                AppJson.decodeFromString(kotlinx.serialization.json.JsonArray.serializer(), json)
                                    .mapNotNull { it.jsonPrimitive.contentOrNull }
                                    .toSet()
                            }.getOrNull()
                        } ?: emptySet(),
                )
                HorizontalDivider()
                DetailLine(label = "ID", value = skill.id)
                DetailLine(label = stringResource(R.string.skill_label_category), value = skill.category)
                DetailLine(label = stringResource(R.string.skill_label_implementation), value = skill.implementationKotlin)
                DetailLine(
                    label = stringResource(R.string.skill_label_source),
                    value = if (isBuiltIn) builtInSeedText else customText,
                )
                // v0.29 P1-5: 能力使用场景示例(帮助用户理解何时触发)
                rememberSkillUseCases()[skill.id]?.let { useCase ->
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.skill_use_cases),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = useCase,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmText = stringResource(R.string.skill_close),
        onConfirm = onDismiss,
        dismissText = if (onDelete != null) stringResource(R.string.skill_delete) else null,
        onDismiss = onDelete,
    )
}

/**
 * 把 parametersJson(JSON Schema) 渲染成可读的参数列表。
 * 每个参数显示:名称、type、是否必填、description。
 */
@Composable
private fun ParameterSchemaView(
    parametersJson: String,
    requiredSet: Set<String>,
) {
    val params: List<Pair<String, JsonObject?>> = remember(parametersJson) {
        val schemaObj = resultOf<JsonObject> {
            AppJson.decodeFromString(JsonObject.serializer(), parametersJson)
        }.getOrNull() ?: return@remember emptyList()
        val properties = schemaObj["properties"]?.jsonObject ?: return@remember emptyList()
        properties.entries.map { (name, def) ->
            name to (def as? JsonObject)
        }
    }

    if (params.isEmpty()) {
        Text(
            text = parametersJson.ifBlank { "{}" },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MuseMonoFontFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        params.forEach { (name, def) ->
            val type = def?.get("type")?.jsonPrimitive?.contentOrNull ?: "string"
            val description = def?.get("description")?.jsonPrimitive?.contentOrNull ?: ""
            val isRequired = name in requiredSet
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = type,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (isRequired) {
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = "必填",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** v0.29 P1-5: 内置 Skill 使用场景说明(帮助用户理解何时触发)。 */
@Composable
private fun rememberSkillUseCases(): Map<String, String> = mapOf(
    "read_file" to stringResource(R.string.skill_use_case_read_file),
    "write_file" to stringResource(R.string.skill_use_case_write_file),
    "http_get" to stringResource(R.string.skill_use_case_http_get),
    "http_post" to stringResource(R.string.skill_use_case_http_post),
    "web_search" to stringResource(R.string.skill_use_case_web_search),
    "web_fetch" to stringResource(R.string.skill_use_case_web_fetch),
    "knowledge_search" to stringResource(R.string.skill_use_case_knowledge_search),
    "arxiv_search" to stringResource(R.string.skill_use_case_arxiv_search),
    "install_skill" to stringResource(R.string.skill_use_case_install_skill),
)

@Composable
private fun DetailLine(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
