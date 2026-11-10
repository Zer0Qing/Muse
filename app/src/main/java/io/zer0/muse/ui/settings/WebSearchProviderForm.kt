package io.zer0.muse.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseChip
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.settings.SectionLabel
import io.zer0.muse.ui.common.settings.SettingsGroup
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.web.WebSearchConfig
import io.zer0.muse.web.WebSearchProviderCatalog
import io.zer0.muse.web.WebSearchProviderEntry
import io.zer0.muse.web.probeSearchProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * 联网搜索供应商的「先选类型 → 按类型动态表单」填写区。
 *
 * 抽成独立文件是为了让 WebSearchSection 不再继续膨胀；这里只负责填写与落库，
 * 不改执行层：类型、默认端点、字段声明全部读 [WebSearchProviderCatalog]，
 * 生效语义仍是 providerName 单选 + providerOrder 排序 + fallbackEnabled 全链回退。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WebSearchProviderTypeSection(
    config: WebSearchConfig,
    settings: SettingsRepository,
    scope: CoroutineScope,
    webSearchClient: OkHttpClient,
) {
    val context = LocalContext.current
    val savedText = stringResource(R.string.settings_saved)
    var selectedType by remember { mutableStateOf(WebSearchProviderCatalog.chainKeys.first()) }
    val meta = WebSearchProviderCatalog.meta(selectedType)
    val existing = config.providerEntries.firstOrNull { it.type == selectedType }
    // 已保存池：新真源优先，旧 apiKeys 映射作兼容回退（迁移前的配置仍可能只有旧字段）。
    val savedPool = existing?.apiKeys.orEmpty().ifEmpty {
        listOfNotNull(config.apiKeys[selectedType]?.takeIf { it.isNotBlank() })
    }

    // 草稿与已保存值分开：默认显示掩码，留空不代表清空，避免「打开就按保存」把密钥删掉。
    var keyDraft by remember(selectedType) { mutableStateOf("") }
    var keyEdited by remember(selectedType) { mutableStateOf(false) }
    var urlDraft by remember(selectedType) { mutableStateOf(existing?.customUrl.orEmpty()) }
    var modelDraft by remember(selectedType) { mutableStateOf(existing?.model.orEmpty()) }
    var verifyText by remember(selectedType) { mutableStateOf<String?>(null) }
    var status by remember(selectedType) { mutableStateOf(ProviderFormStatus.UNTESTED) }
    var testing by remember(selectedType) { mutableStateOf(false) }

    SectionLabel(
        stringResource(R.string.settings_web_search_provider_type_title),
        modifier = Modifier.padding(top = MusePaddings.cardGap),
    )
    SettingsGroup {
        Column(Modifier.padding(MusePaddings.cardInner)) {
            Text(
                text = stringResource(R.string.settings_web_search_provider_type_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(MusePaddings.contentGap))
            // chip 网格：用项目自有 MuseChip（已含 selected/Role/48dp 触控），不直接用 M3 FilterChip。
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                WebSearchProviderCatalog.chainKeys.forEach { type ->
                    MuseChip(
                        selected = selectedType == type,
                        onClick = { selectedType = type },
                        label = type,
                    )
                }
            }
            Spacer(Modifier.height(MusePaddings.contentGap))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (status) {
                        ProviderFormStatus.CONNECTED -> stringResource(R.string.settings_web_search_status_connected)
                        ProviderFormStatus.FAILED -> stringResource(R.string.settings_web_search_status_failed)
                        ProviderFormStatus.UNTESTED -> stringResource(R.string.settings_web_search_status_untested)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // 动态表单：字段集由 Catalog 的 Meta 决定，不同类型只显示自己需要的项。
    SettingsGroup(modifier = Modifier.padding(top = MusePaddings.tightGap)) {
        Column(Modifier.padding(MusePaddings.cardInner)) {
            if (meta.needsKey) {
                Text(
                    stringResource(R.string.settings_web_search_key_pool_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.settings_web_search_key_pool_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MuseTextField(
                    value = keyDraft,
                    onValueChange = {
                        keyDraft = it
                        keyEdited = true
                    },
                    placeholder = { Text(stringResource(R.string.settings_web_search_key_pool_hint)) },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().padding(top = MusePaddings.tightGap),
                )
                // 输入框默认不回填已保存 Key（防一开屏就泄露），所以必须另给指示行，
                // 否则用户看不出自己到底配没配过。只报个数，不回显任何 Key 字符。
                val savedCount = WebSearchKeyFieldState.savedCount(savedPool)
                if (savedCount > 0) {
                    Text(
                        stringResource(R.string.settings_provider_keys_count, savedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = MusePaddings.tightGap),
                    )
                }
                // 逐条展示已保存的 Key：掩码行 + 每行删除按钮。完整 Key 永不出现在语义树，
                // 删除即时落库（走与保存按钮同一套 entries+compat 双写），不用先点保存才生效。
                savedPool.forEachIndexed { index, key ->
                    if (key.isBlank()) return@forEachIndexed
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MusePaddings.tightGap),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = WebSearchKeyFieldState.masked(key),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        MuseTactileButton(
                            icon = MuseIcons.trash,
                            onClick = {
                                val remaining = WebSearchKeyFieldState.removeAt(savedPool, index)
                                val entry = WebSearchProviderEntry(
                                    id = existing?.id ?: selectedType,
                                    type = selectedType,
                                    customUrl = existing?.customUrl.orEmpty(),
                                    apiKeys = remaining,
                                    model = existing?.model.orEmpty(),
                                    enabled = existing?.enabled ?: true,
                                )
                                val entries = config.providerEntries.filter { it.type != selectedType } + entry
                                val compatKeys = config.apiKeys.toMutableMap().apply {
                                    if (remaining.isEmpty()) remove(selectedType) else put(selectedType, remaining.first())
                                }
                                scope.launch {
                                    settings.saveWebSearchConfig(
                                        config.copy(providerEntries = entries, apiKeys = compatKeys),
                                    )
                                    // 草稿态同步失效：被删的可能正是草稿里那把，留着会凭空复活。
                                    keyDraft = ""
                                    keyEdited = false
                                }
                            },
                            contentDescription = stringResource(R.string.settings_provider_remove_key),
                            size = 32.dp,
                            iconSize = MuseIconSizes.iconSmall,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (meta.supportsCustomUrl) {
                Spacer(Modifier.height(MusePaddings.contentGap))
                Text(
                    stringResource(R.string.settings_web_search_custom_url_optional),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MuseTextField(
                    value = urlDraft,
                    onValueChange = { urlDraft = it },
                    placeholder = {
                        Text(meta.defaultEndpoint ?: stringResource(R.string.settings_web_search_custom_url_hint))
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = MusePaddings.tightGap),
                )
                Text(
                    stringResource(R.string.settings_web_search_custom_url_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (meta.needsInstanceUrl && urlDraft.isBlank()) {
                    Text(
                        stringResource(R.string.settings_web_search_instance_url_required),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (meta.needsModel) {
                Spacer(Modifier.height(MusePaddings.contentGap))
                Text(
                    stringResource(R.string.settings_web_search_model_optional),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                MuseTextField(
                    value = modelDraft,
                    onValueChange = { modelDraft = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = MusePaddings.tightGap),
                )
            }
            verifyText?.let { text ->
                Spacer(Modifier.height(MusePaddings.tightGap))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(MusePaddings.contentGap))
            // 测试连接：真发一次最小请求；失败给可操作原因，不把上游原始报文整段直出。
            MuseCapsuleButton(
                text = if (testing) {
                    stringResource(R.string.settings_web_search_testing)
                } else {
                    stringResource(R.string.settings_web_search_key_verify)
                },
                enabled = !testing,
                onClick = {
                    if (testing) return@MuseCapsuleButton
                    testing = true
                    verifyText = null
                    val draftKeys = WebSearchKeyFieldState.poolToSave(savedPool, keyDraft, keyEdited)
                    val testKey = draftKeys.firstOrNull().orEmpty()
                    scope.launch {
                        val outcome = probeSearchProvider(
                            client = webSearchClient,
                            cfg = config.copy(
                                providerEntries = listOf(
                                    WebSearchProviderEntry(
                                        id = selectedType,
                                        type = selectedType,
                                        customUrl = urlDraft.trim(),
                                        apiKeys = draftKeys,
                                    ),
                                ) + config.providerEntries.filter { it.type != selectedType },
                            ),
                            providerKey = selectedType,
                            apiKeyDraft = testKey,
                        )
                        status = if (outcome.ok) ProviderFormStatus.CONNECTED else ProviderFormStatus.FAILED
                        verifyText = when {
                            outcome.ok -> context.getString(R.string.settings_web_search_key_verify_ok, outcome.results)
                            outcome.detail != null -> context.getString(
                                R.string.settings_web_search_key_verify_failed,
                                outcome.detail,
                            )
                            else -> context.getString(R.string.settings_web_search_key_verify_empty)
                        }
                        testing = false
                    }
                },
            )
            Spacer(Modifier.height(MusePaddings.tightGap))
            // 保存只写 providerEntries（新真源），同时把首把 Key 同步回旧 apiKeys 映射，
            // 让旧的「测试连接」/兼容读取路径与这里保持一致，不产生双源分叉。
            MuseCapsuleButton(
                text = stringResource(R.string.settings_web_search_save_api_key),
                onClick = {
                    if (meta.needsInstanceUrl && urlDraft.isBlank()) {
                        verifyText = context.getString(R.string.settings_web_search_instance_url_required)
                        return@MuseCapsuleButton
                    }
                    val keys = WebSearchKeyFieldState.poolToSave(savedPool, keyDraft, keyEdited)
                    val entry = WebSearchProviderEntry(
                        id = existing?.id ?: selectedType,
                        type = selectedType,
                        customUrl = urlDraft.trim(),
                        apiKeys = keys,
                        model = modelDraft.trim(),
                        enabled = existing?.enabled ?: true,
                    )
                    val entries = config.providerEntries.filter { it.type != selectedType } + entry
                    val compatKeys = config.apiKeys.toMutableMap().apply {
                        if (keys.isEmpty()) remove(selectedType) else put(selectedType, keys.first())
                    }
                    scope.launch {
                        settings.saveWebSearchConfig(
                            config.copy(providerEntries = entries, apiKeys = compatKeys),
                        )
                        keyDraft = ""
                        keyEdited = false
                        status = ProviderFormStatus.UNTESTED
                        MuseToast.show(savedText)
                    }
                },
            )
        }
    }
}

private enum class ProviderFormStatus { CONNECTED, FAILED, UNTESTED }
