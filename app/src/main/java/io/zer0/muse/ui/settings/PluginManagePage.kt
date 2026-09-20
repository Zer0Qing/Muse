package io.zer0.muse.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle
import compose.icons.tablericons.Puzzle
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Settings
import compose.icons.tablericons.SwitchHorizontal
import compose.icons.tablericons.Trash
import compose.icons.tablericons.Plus
import io.zer0.ai.plugin.ProviderPlugin
import io.zer0.ai.plugin.ProviderPluginRegistry
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginSecurityGate
import io.zer0.muse.data.plugin.PluginVersion
import io.zer0.muse.data.plugin.market.CatalogRefreshResult
import io.zer0.muse.data.plugin.market.InstallPlanResult
import io.zer0.muse.data.plugin.market.PluginCatalogClient
import io.zer0.muse.data.plugin.market.PluginCatalogEntry
import io.zer0.muse.data.plugin.market.PluginCatalogRepository
import io.zer0.muse.data.plugin.market.PluginDownloadClient
import io.zer0.muse.data.plugin.market.PluginInstallCoordinator
import io.zer0.muse.data.plugin.market.PluginInstallPlan
import io.zer0.muse.data.plugin.market.PluginMarketDefaults
import io.zer0.muse.data.plugin.market.PluginMarketInstaller
import io.zer0.muse.data.plugin.market.PluginMarketSettings
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

/**
 * 统一「插件管理」页（v1.0.62）。
 *
 * 合并两类插件：
 *  - 外部插件（.muse-plugin / ZIP 包）：PluginManager 管理，工具注册进 skills 表由 SkillExecutor 执行
 *  - Provider 插件（JSON 配置）：ProviderPluginRegistry 管理，可转为供应商配置
 *
 * 导入入口统一：自动识别文件类型（ZIP 头 PK → 外部插件；JSON → Provider 插件）。
 */
@Composable
fun PluginManagePage(
    onBack: () -> Unit,
) {
    val pluginManager: PluginManager = koinInject()
    val registry: ProviderPluginRegistry = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widthClass = rememberWindowWidthClass()

    var externalPlugins by remember { mutableStateOf(pluginManager.list()) }
    var providerPlugins by remember { mutableStateOf(registry.list()) }
    var importing by remember { mutableStateOf(false) }
    var pendingExternalInstall by remember { mutableStateOf<PendingExternalInstall?>(null) }
    var pendingDeleteExternal by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }
    var pendingDeleteProvider by remember { mutableStateOf<ProviderPlugin?>(null) }
    /** 待用户确认“签名并启用”的助手自写草稿；确认前不会签名、不会写信任根。 */
    var pendingSignDraft by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }

    // ── Phase 5: 插件市场（签名目录浏览 + 一键安装）──
    // 目录 URL / 信任根保存在 DataStore；仓库缓存目录与已接受 sequence，拒绝回退与过期。
    val marketSettings = remember(context) { PluginMarketSettings(context) }
    val catalogRootKeysState = remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    val marketRepository = remember(context) {
        PluginCatalogRepository(
            client = PluginCatalogClient(),
            cacheDir = File(context.filesDir, "plugin_market"),
            trustRootKeys = { catalogRootKeysState.value },
        )
    }
    // 信任根变更后重建协调器：目录验签必须始终使用当前配置的信任根。
    val marketCoordinator = remember(marketRepository, catalogRootKeysState.value) {
        PluginInstallCoordinator(
            trustRootKeys = catalogRootKeysState.value,
            lastAcceptedSequence = { catalogId -> marketRepository.lastAcceptedSequence(catalogId) },
        )
    }
    // 下载只写入应用私有缓存 staging，绝不直接写入插件目录。
    val marketDownloadClient = remember(context) {
        PluginDownloadClient(stagingDir = File(context.cacheDir, "plugin_market_staging"))
    }
    val marketInstaller = remember(pluginManager) { PluginMarketInstaller(pluginManager) }

    /** null = 设置读取中；空串 = 明确未配置。 */
    var catalogUrl by remember { mutableStateOf<String?>(null) }
    /** 用户自定义的目录地址；空表示正在使用内置官方目录。 */
    var catalogUrlOverride by remember { mutableStateOf("") }
    var marketEntries by remember { mutableStateOf<List<PluginCatalogEntry>>(emptyList()) }
    var marketRefreshing by remember { mutableStateOf(false) }
    var marketError by remember { mutableStateOf<String?>(null) }
    var showMarketSettings by remember { mutableStateOf(false) }
    var marketInstall by remember { mutableStateOf<MarketInstallJob?>(null) }
    var marketInstallJob by remember { mutableStateOf<Job?>(null) }
    /** 待确认的回滚请求：插件 id 与目标版本。 */
    var rollbackRequest by remember { mutableStateOf<Pair<String, String>?>(null) }

    // ── P0-9: 信任管理(撤销入口)──
    /** 信任的发行者列表(与安装校验同一 trust store 实例)。 */
    var trustedPublishers by remember { mutableStateOf(pluginManager.trustedPublishers()) }
    /** 待确认撤销的发行者 id。 */
    var revokePublisherId by remember { mutableStateOf<String?>(null) }

    fun refreshMarketCatalog() {
        val url = catalogUrl.orEmpty()
        if (url.isBlank() || marketRefreshing) return
        marketRefreshing = true
        scope.launch {
            try {
                when (val result = marketRepository.refresh(url)) {
                    CatalogRefreshResult.NotConfigured -> Unit
                    is CatalogRefreshResult.Updated -> {
                        marketEntries = result.signedCatalog.payload.entries
                        marketError = null
                    }
                    is CatalogRefreshResult.Rejected -> marketError = result.reason
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                marketError = error.message ?: context.getString(R.string.plugin_market_load_failed)
            } finally {
                marketRefreshing = false
            }
        }
    }

    /** 丢弃尚未确认的 staged 包；已提交安装的包不在这里删除。 */
    fun discardMarketPlan() {
        marketInstall?.plan?.let { marketInstaller.discard(it) }
    }

    /** 仅当 [entryId] 仍是当前安装任务时更新阶段；旧协程不得覆盖新任务状态。 */
    fun updateMarketInstall(
        entryId: String,
        phase: MarketInstallPhase,
        plan: PluginInstallPlan? = null,
        trustPublisher: Boolean = false,
    ): Boolean {
        val current = marketInstall ?: return false
        if (current.entryId != entryId) return false
        marketInstall = current.copy(
            phase = phase,
            plan = plan ?: current.plan,
            trustPublisher = if (plan != null) trustPublisher else current.trustPublisher,
        )
        return true
    }

    fun dismissMarketInstall() {
        marketInstallJob?.cancel()
        marketInstallJob = null
        discardMarketPlan()
        marketInstall = null
    }

    /** 下载 → 安全审查 → 生成安装计划；此时只展示预览，尚未写入插件目录。 */
    fun startMarketInstall(entry: PluginCatalogEntry) {
        marketInstallJob?.cancel()
        discardMarketPlan()
        marketInstall = MarketInstallJob(entry.id, MarketInstallPhase.Downloading)
        marketInstallJob = scope.launch {
            var staged: File? = null
            var planCreated = false
            try {
                val catalog = marketRepository.currentCatalog()
                    ?: throw IllegalStateException(context.getString(R.string.plugin_market_state_invalid))
                val download = withContext(Dispatchers.IO) { marketDownloadClient.download(entry) }
                val artifact = download.artifact
                    ?: throw IllegalStateException(download.error ?: context.getString(R.string.plugin_download_failed))
                staged = artifact.file
                updateMarketInstall(entry.id, MarketInstallPhase.Preparing)
                val review = withContext(Dispatchers.IO) { pluginManager.reviewFromFile(artifact.file) }
                    .getOrElse { error ->
                        throw IllegalStateException(error.message ?: context.getString(R.string.plugin_review_failed))
                    }
                if (!review.allowed) {
                    throw IllegalStateException(
                        review.reason ?: context.getString(R.string.muse_plugins_security_rejected),
                    )
                }
                if (review.signature.status == PluginSecurityGate.SignatureStatus.UNSIGNED) {
                    throw IllegalStateException(context.getString(R.string.muse_plugins_market_unsigned_package))
                }
                val plan = when (
                    val planResult = marketCoordinator.preparePlan(
                        signedCatalog = catalog,
                        entryId = entry.id,
                        stagedFile = artifact.file,
                        artifactSha256 = artifact.sha256,
                        reviewedPackage = PluginInstallCoordinator.PluginPackageReview(
                            preview = review.preview,
                            contentSha256 = review.preview.contentSha256,
                        ),
                    )
                ) {
                    is InstallPlanResult.Ready -> planResult.plan
                    is InstallPlanResult.Rejected -> throw IllegalStateException(planResult.reason)
                }
                planCreated = true
                val shown = updateMarketInstall(
                    entryId = entry.id,
                    phase = MarketInstallPhase.AwaitingConfirm,
                    plan = plan,
                    // 未知发行者必须由用户在确认框里显式点“信任发行者并安装”。
                    trustPublisher = plan.preview.signatureStatus ==
                        PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
                )
                // 该任务已被新的安装请求取代：计划不会展示，立即清理 staged 包。
                if (!shown) marketInstaller.discard(plan)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                updateMarketInstall(
                    entry.id,
                    MarketInstallPhase.Failed(
                        error.message ?: context.getString(R.string.plugin_install_failed),
                    ),
                )
            } finally {
                // 未生成计划（下载/审查/校验失败或取消）时清理 staged 包。
                if (!planCreated) runCatching { staged?.delete() }
            }
        }
    }

    /** 用户确认后二次校验并原子提交；未知发行者的信任标志在计划生成时就已固定。 */
    fun confirmMarketInstall() {
        val pending = marketInstall ?: return
        val plan = pending.plan ?: return
        if (pending.phase == MarketInstallPhase.Installing) return
        marketInstall = pending.copy(phase = MarketInstallPhase.Installing)
        marketInstallJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                marketInstaller.confirmAndInstall(plan, pending.trustPublisher)
            }
            marketInstaller.discard(plan)
            result
                .onSuccess {
                    externalPlugins = pluginManager.list()
                    if (marketInstall?.entryId == plan.entry.id) marketInstall = null
                    MuseToast.show(context.getString(R.string.muse_plugins_confirmed))
                }
                .onFailure { error ->
                    updateMarketInstall(
                        plan.entry.id,
                        MarketInstallPhase.Failed(
                            error.message ?: context.getString(R.string.plugin_install_failed),
                        ),
                    )
                }
        }
    }

    LaunchedEffect(Unit) {
        // 先读取信任根再加载缓存：缓存校验必须使用当前配置的信任根。
        catalogRootKeysState.value = marketSettings.catalogRootKeys()
        val url = marketSettings.catalogUrl()
        catalogUrl = url
        catalogUrlOverride = marketSettings.catalogUrlOverride()
        val cached = marketRepository.loadCache()
        marketEntries = cached.entries
        marketError = cached.error
        if (url.isNotBlank()) refreshMarketCatalog()
    }

    /** 统一导入：PK 头 → 外部插件包；否则按 JSON Provider 插件处理。 */
    suspend fun importFromUri(uri: Uri) {
        withContext(Dispatchers.IO) {
            // P2-28: 大小限流 — 外部插件包由 PluginManager.withTempPluginFile 的
            // copyLimited(MAX_PLUGIN_PACKAGE_BYTES) 限流;Provider JSON 由下方复制阶段
            // 1MB 限流 + registry.loadFromFile 内置上限双保险,杜绝 OOM。
            val header = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    byteArrayOf(input.read().toByte(), input.read().toByte())
                }
            }.getOrNull() ?: error(context.getString(R.string.plugin_file_read_failed))
            val isZip = header.size == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            if (isZip) {
                pluginManager.reviewFromUri(uri)
                    .onSuccess { decision ->
                        withContext(Dispatchers.Main) {
                            if (decision.allowed) {
                                pendingExternalInstall = PendingExternalInstall(uri, decision.preview)
                            } else {
                                MuseToast.show(
                                    context.getString(
                                        R.string.muse_plugins_review_failed,
                                        decision.reason ?: context.getString(R.string.muse_plugins_security_rejected),
                                    ),
                                    3500,
                                )
                            }
                        }
                    }
                    .onFailure { e ->
                        val msg = e.message ?: e::class.simpleName ?: "unknown"
                        withContext(Dispatchers.Main) {
                            MuseToast.show(context.getString(R.string.provider_plugins_import_failed, msg), 3500)
                        }
                    }
                return@withContext
            }
            val tempFile = File(context.cacheDir, "plugin_import_${System.nanoTime()}.json")
            try {
                // P2-28: 复制阶段同样限流,超大 JSON 在写盘前就拒绝,避免缓存被灌爆 + OOM
                val maxJsonBytes = io.zer0.ai.plugin.ProviderPluginRegistry.MAX_PROVIDER_JSON_BYTES
                var total = 0L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        val buf = ByteArray(8 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxJsonBytes) {
                                tempFile.delete()
                                error(context.getString(R.string.plugin_json_too_large, maxJsonBytes / 1024))
                            }
                            output.write(buf, 0, n)
                        }
                    }
                } ?: error(context.getString(R.string.plugin_file_read_failed))
                registry.loadFromFile(tempFile)
                    .onSuccess {
                        withContext(Dispatchers.Main) {
                            providerPlugins = registry.list()
                            MuseToast.show(context.getString(R.string.provider_plugins_import_success))
                        }
                    }
                    .onFailure { e ->
                        val msg = e.message ?: e::class.simpleName ?: "unknown"
                        withContext(Dispatchers.Main) {
                            MuseToast.show(context.getString(R.string.provider_plugins_import_failed, msg), 3500)
                        }
                    }
            } finally {
                runCatching { if (tempFile.exists()) tempFile.delete() }
            }
        }
    }

    fun convertToProvider(plugin: ProviderPlugin) {
        scope.launch {
            val config = registry.toProviderConfig(plugin)
            settings.addProvider(config)
            MuseToast.show(context.getString(R.string.provider_plugins_import_success))
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            resultOf { importFromUri(uri) }
                .onError { msg, t -> Logger.w("PluginManagePage", "importFromUri failed: $msg", t) }
            importing = false
        }
    }

    io.zer0.muse.ui.common.surface.MusePageScaffold(
        topBar = {
            MuseTopBar(
                title = stringResource(R.string.muse_plugins_manage),
                onBack = onBack,
                largeTitle = true,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            MuseFloatingButton(
                icon = TablerIcons.Plus,
                onClick = { importLauncher.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) },
                contentDescription = stringResource(R.string.provider_plugins_import),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .then(
                    if (widthClass == WindowWidthClass.Expanded) Modifier.widthIn(max = 720.dp) else Modifier,
                ),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + MusePaddings.screen,
            ),
            verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
        ) {
            // ── 插件市场区（Phase 5）──
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.muse_plugins_market_title),
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    if (marketRefreshing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(MuseIconSizes.iconSmall),
                            strokeWidth = 2.dp,
                        )
                    } else if (catalogUrl?.isNotBlank() == true) {
                        MuseTactileButton(
                            icon = TablerIcons.Refresh,
                            onClick = { refreshMarketCatalog() },
                            contentDescription = stringResource(R.string.muse_plugins_market_refresh),
                        )
                    }
                    MuseTactileButton(
                        icon = TablerIcons.Settings,
                        onClick = { showMarketSettings = true },
                        contentDescription = stringResource(R.string.muse_plugins_market_configure),
                    )
                }
            }
            when {
                // 设置读取中：既不显示“未配置”，也不假装目录可用。
                catalogUrl == null -> item { MarketLoadingRow() }
                catalogUrl.isNullOrBlank() -> item {
                    MuseEmptyState(
                        icon = TablerIcons.Puzzle,
                        title = stringResource(R.string.muse_plugins_market_not_configured),
                        subtitle = stringResource(R.string.muse_plugins_market_not_configured_hint),
                        actionText = stringResource(R.string.muse_plugins_market_configure),
                        onAction = { showMarketSettings = true },
                    )
                }
                marketEntries.isEmpty() && marketRefreshing -> item { MarketLoadingRow() }
                marketEntries.isEmpty() -> item {
                    val failure = marketError
                    MuseEmptyState(
                        icon = if (failure != null) TablerIcons.AlertCircle else TablerIcons.Puzzle,
                        title = failure ?: stringResource(R.string.muse_plugins_market_empty),
                        subtitle = if (failure != null) {
                            stringResource(R.string.muse_plugins_market_error_hint)
                        } else {
                            null
                        },
                        actionText = if (failure != null) stringResource(R.string.common_retry) else null,
                        onAction = if (failure != null) ({ refreshMarketCatalog() }) else null,
                    )
                }
                else -> {
                    marketError?.let { reason ->
                        item { MarketErrorRow(reason = reason, onRetry = { refreshMarketCatalog() }) }
                    }
                    items(marketEntries, key = { "market_${it.id}" }) { entry ->
                        MarketEntryRow(
                            entry = entry,
                            installedVersion = externalPlugins.firstOrNull { it.id == entry.id }?.version,
                            phase = marketInstall?.takeIf { it.entryId == entry.id }?.phase,
                            onInstall = { startMarketInstall(entry) },
                            onCancel = { dismissMarketInstall() },
                            onRetry = { startMarketInstall(entry) },
                            onDismissFailure = { dismissMarketInstall() },
                        )
                    }
                }
            }

            // ── P0-9: 信任管理(信任的发行者 / 目录信任根)──
            item { SectionHeader(stringResource(R.string.muse_plugins_trust_title)) }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Text(
                        text = stringResource(R.string.muse_plugins_trust_publishers_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (trustedPublishers.isEmpty()) {
                        Text(
                            text = stringResource(R.string.muse_plugins_trust_publishers_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        trustedPublishers.forEach { publisher ->
                            TrustedPublisherRow(
                                publisherId = publisher.publisherId,
                                fingerprint = publisher.fingerprint,
                                onRevoke = { revokePublisherId = publisher.publisherId },
                            )
                        }
                    }
                    Spacer(Modifier.height(MusePaddings.contentGap))
                    Text(
                        text = stringResource(R.string.muse_plugins_trust_catalog_title),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val userRootKeys = catalogRootKeysState.value.keys - PluginMarketDefaults.catalogRootKeys.keys
                    if (userRootKeys.isEmpty()) {
                        Text(
                            text = stringResource(R.string.muse_plugins_trust_catalog_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        userRootKeys.forEach { keyId ->
                            CatalogRootKeyRow(
                                keyId = keyId,
                                onRemove = {
                                    scope.launch {
                                        marketSettings.removeCatalogRootKey(keyId)
                                        // P0-9: 目录根撤销后清空市场缓存条目,避免旧信任根验证的条目残留
                                        marketRepository.clearCache()
                                        catalogRootKeysState.value = marketSettings.catalogRootKeys()
                                        marketEntries = emptyList()
                                        catalogUrl = marketSettings.catalogUrl()
                                        MuseToast.show(context.getString(R.string.muse_plugins_trust_catalog_removed))
                                        refreshMarketCatalog()
                                    }
                                },
                            )
                        }
                    }
                }
            }

            // ── 外部插件区 ──
            item { SectionHeader(stringResource(R.string.muse_plugins_external)) }
            if (importing && externalPlugins.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(MuseIconSizes.iconMedium),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else if (externalPlugins.isEmpty()) {
                item {
                    MuseEmptyState(
                        icon = TablerIcons.Puzzle,
                        title = stringResource(R.string.muse_plugins_empty_hint),
                    )
                }
            } else {
                items(externalPlugins, key = { "ext_${it.id}" }) { plugin ->
                    InstalledPluginRow(
                        plugin = plugin,
                        onToggle = {
                            scope.launch {
                                pluginManager.setEnabled(plugin.id, !plugin.enabled)
                                externalPlugins = pluginManager.list()
                            }
                        },
                        onConfirm = if (
                            plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED ||
                                plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED
                        ) {
                            {
                                scope.launch {
                                    pluginManager.confirmInstallation(
                                        plugin.id,
                                        trustPublisher = plugin.signatureStatus ==
                                            PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
                                    )
                                        .onSuccess {
                                            externalPlugins = pluginManager.list()
                                            MuseToast.show(context.getString(R.string.muse_plugins_confirmed))
                                        }
                                        .onFailure { error ->
                                            val msg = error.message ?: error::class.simpleName ?: "unknown"
                                            MuseToast.show(
                                                context.getString(R.string.provider_plugins_import_failed, msg),
                                                3500,
                                            )
                                        }
                                }
                            }
                        } else {
                            null
                        },
                        onDelete = { pendingDeleteExternal = plugin },
                        // 助手起草的未签名草稿：只有本机作者密钥签名并启用后才可执行。
                        onSignAndEnable = if (
                            plugin.signatureStatus == PluginSecurityGate.SignatureStatus.UNSIGNED &&
                                !plugin.installationConfirmed
                        ) {
                            { pendingSignDraft = plugin }
                        } else {
                            null
                        },
                        rollbackVersion = pluginManager.listRetainedVersions(plugin.id)
                            .firstOrNull { it.version != plugin.version }
                            ?.version,
                        onRollback = { version -> rollbackRequest = plugin.id to version },
                    )
                }
            }

            // ── Provider 插件区 ──
            item {
                Spacer(Modifier.height(MusePaddings.contentGap))
                SectionHeader(stringResource(R.string.provider_plugins_title))
            }
            if (providerPlugins.isEmpty()) {
                item {
                    MuseEmptyState(
                        icon = TablerIcons.Puzzle,
                        title = stringResource(R.string.provider_plugins_empty),
                    )
                }
            } else {
                items(providerPlugins, key = { "prov_${it.id}" }) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        onDelete = { pendingDeleteProvider = plugin },
                        onConvert = { convertToProvider(plugin) },
                    )
                }
            }
        }
    }

    // 外部插件安装确认：展示能力、工具和摘要后才能提交安装。
    pendingExternalInstall?.let { pending ->
        val preview = pending.preview
        val unknownAuthor = stringResource(R.string.muse_plugins_unknown_author)
        val noCapabilities = stringResource(R.string.muse_plugins_none)
        MuseDialog(
            onDismissRequest = { pendingExternalInstall = null },
            title = stringResource(R.string.muse_plugins_install_confirm_title, preview.name),
            content = { PluginInstallPreviewContent(preview) },
            confirmText = stringResource(
                when (preview.signatureStatus) {
                    PluginSecurityGate.SignatureStatus.UNSIGNED ->
                        R.string.muse_plugins_install_save_draft
                    PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED ->
                        R.string.muse_plugins_install_trust_publisher_action
                    else -> R.string.muse_plugins_install_confirm_action
                },
            ),
            onConfirm = {
                pendingExternalInstall = null
                scope.launch {
                    val result = if (
                        preview.signatureStatus == PluginSecurityGate.SignatureStatus.UNSIGNED
                    ) {
                        pluginManager.installDraftFromUri(pending.uri, preview)
                    } else {
                        pluginManager.installConfirmedFromUri(
                            pending.uri,
                            preview,
                            trustPublisher = preview.signatureStatus ==
                                PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED,
                        )
                    }
                    result
                        .onSuccess {
                            externalPlugins = pluginManager.list()
                            MuseToast.show(
                                context.getString(
                                    if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.UNSIGNED) {
                                        R.string.muse_plugins_draft_saved
                                    } else {
                                        R.string.muse_plugins_imported
                                    },
                                ),
                            )
                        }
                        .onFailure { error ->
                            val msg = error.message ?: error::class.simpleName ?: "unknown"
                            MuseToast.show(
                                context.getString(R.string.provider_plugins_import_failed, msg),
                                3500,
                            )
                        }
                }
            },
        )
    }

    // 助手自写草稿的「签名并启用」确认：展示插件身份与工具清单，用户点头才签名并写信任根。
    pendingSignDraft?.let { target ->
        val noTools = stringResource(R.string.muse_plugins_none)
        MuseDialog(
            onDismissRequest = { pendingSignDraft = null },
            title = stringResource(R.string.muse_plugins_sign_enable_title),
            content = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                ) {
                    Text(
                        text = stringResource(
                            R.string.muse_plugins_sign_enable_summary,
                            target.name,
                            target.id,
                            target.version,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(
                            R.string.muse_plugins_sign_enable_tools,
                            target.tools.joinToString(", ") { it.name }.ifBlank { noTools },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.muse_plugins_sign_enable_warning),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmText = stringResource(R.string.muse_plugins_sign_enable_action),
            onConfirm = {
                pendingSignDraft = null
                scope.launch {
                    pluginManager.signAndEnableDraft(target.id)
                        .onSuccess {
                            externalPlugins = pluginManager.list()
                            MuseToast.show(context.getString(R.string.muse_plugins_sign_enable_success))
                        }
                        .onFailure { error ->
                            val msg = error.message ?: error::class.simpleName ?: "unknown"
                            MuseToast.show(
                                context.getString(R.string.muse_plugins_sign_enable_failed, msg),
                                3500,
                            )
                        }
                }
            },
        )
    }

    // 市场安装确认：预览能力/工具/发行者/指纹后，才进行二次校验与原子提交。
    val marketPlan = marketInstall?.plan
    if (marketPlan != null && marketInstall?.phase == MarketInstallPhase.AwaitingConfirm) {
        val preview = marketPlan.preview
        MuseDialog(
            onDismissRequest = { dismissMarketInstall() },
            title = stringResource(R.string.muse_plugins_install_confirm_title, preview.name),
            content = { PluginInstallPreviewContent(preview) },
            confirmText = stringResource(
                if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED) {
                    R.string.muse_plugins_install_trust_publisher_action
                } else {
                    R.string.muse_plugins_install_confirm_action
                },
            ),
            onConfirm = { confirmMarketInstall() },
            onDismiss = { dismissMarketInstall() },
        )
    }

    // 市场安装进度：提交阶段禁用关闭，避免把原子提交误报成取消。
    if (marketInstall?.phase == MarketInstallPhase.Installing) {
        MuseDialog(
            onDismissRequest = {},
            title = stringResource(R.string.muse_plugins_market_installing),
            content = {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            },
            dismissText = null,
        )
    }

    // 市场目录设置：默认使用安装包内置的官方目录，这里只处理“自定义覆盖”。
    if (showMarketSettings) {
        var urlInput by remember { mutableStateOf(catalogUrlOverride) }
        var keyIdInput by remember {
            // 内置官方信任根不可覆盖，因此 keyId 初值只取用户自己追加的那把。
            mutableStateOf(
                (catalogRootKeysState.value.keys - PluginMarketDefaults.catalogRootKeys.keys)
                    .firstOrNull()
                    .orEmpty(),
            )
        }
        var publicKeyInput by remember { mutableStateOf("") }
        var settingsError by remember { mutableStateOf<String?>(null) }
        MuseFormDialog(
            onDismissRequest = { showMarketSettings = false },
            title = stringResource(R.string.muse_plugins_market_settings_title),
            subtitle = stringResource(R.string.muse_plugins_market_settings_hint),
            onConfirm = {
                scope.launch {
                    val trimmedUrl = urlInput.trim()
                    if (trimmedUrl.isNotEmpty() && !trimmedUrl.startsWith("https://")) {
                        settingsError = context.getString(R.string.muse_plugins_market_invalid_url)
                        return@launch
                    }
                    marketSettings.saveCatalogUrl(trimmedUrl)
                    catalogUrlOverride = trimmedUrl
                    catalogUrl = marketSettings.catalogUrl()
                    if (publicKeyInput.isNotBlank()) {
                        val saved = marketSettings.saveCatalogRootKey(keyIdInput, publicKeyInput)
                        if (saved.isFailure) {
                            settingsError =
                                saved.exceptionOrNull()?.message
                                    ?: context.getString(R.string.plugin_public_key_invalid)
                            return@launch
                        }
                    }
                    catalogRootKeysState.value = marketSettings.catalogRootKeys()
                    settingsError = null
                    showMarketSettings = false
                    MuseToast.show(context.getString(R.string.muse_plugins_market_settings_saved))
                    refreshMarketCatalog()
                }
            },
        ) {
            MuseTextField(
                value = urlInput,
                onValueChange = { urlInput = it },
                label = { Text(stringResource(R.string.muse_plugins_market_url_label)) },
                singleLine = true,
            )
            MuseTextField(
                value = keyIdInput,
                onValueChange = { keyIdInput = it },
                label = { Text(stringResource(R.string.muse_plugins_market_key_id_label)) },
                singleLine = true,
            )
            MuseTextField(
                value = publicKeyInput,
                onValueChange = { publicKeyInput = it },
                label = { Text(stringResource(R.string.muse_plugins_market_public_key_label)) },
                minLines = 2,
                maxLines = 4,
            )
            settingsError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }


    // 回滚确认：回滚会重新校验历史副本的签名与内容摘要，失败不会改变当前版本。
    rollbackRequest?.let { (pluginId, version) ->
        MuseDialog(
            onDismissRequest = { rollbackRequest = null },
            title = stringResource(R.string.muse_plugins_rollback_title),
            content = {
                Text(
                    text = stringResource(R.string.muse_plugins_rollback_confirm, pluginId, version),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.muse_plugins_rollback_action, version),
            onConfirm = {
                rollbackRequest = null
                scope.launch {
                    pluginManager.rollbackTo(pluginId, version)
                        .onSuccess {
                            externalPlugins = pluginManager.list()
                            MuseToast.show(context.getString(R.string.muse_plugins_rollback_done))
                        }
                        .onFailure { error ->
                            MuseToast.show(
                                error.message ?: context.getString(R.string.plugin_rollback_failed),
                                3500,
                            )
                        }
                }
            },
        )
    }

    // P0-9: 撤销发行者信任确认 — 撤销后该发行者的插件将被自动禁用,须再次明确信任才能恢复。
    revokePublisherId?.let { publisherId ->
        MuseDialog(
            onDismissRequest = { revokePublisherId = null },
            title = stringResource(R.string.muse_plugins_trust_revoke_title),
            content = {
                Text(
                    text = stringResource(R.string.muse_plugins_trust_revoke_confirm, publisherId),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.muse_plugins_trust_revoke_action),
            onConfirm = {
                revokePublisherId = null
                scope.launch {
                    pluginManager.revokePublisher(publisherId)
                    trustedPublishers = pluginManager.trustedPublishers()
                    externalPlugins = pluginManager.list()
                    MuseToast.show(context.getString(R.string.muse_plugins_trust_revoke_done))
                }
            },
        )
    }

    // 外部插件删除确认
    pendingDeleteExternal?.let { target ->
        MuseDialog(
            onDismissRequest = { pendingDeleteExternal = null },
            title = stringResource(R.string.provider_plugins_delete),
            content = {
                Text(
                    // ST-02: 点名 + 后果 + 可撤销(重新导入)
                    text = stringResource(R.string.provider_plugins_delete_confirm_named, target.name),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.provider_plugins_delete),
            onConfirm = {
                scope.launch {
                    pluginManager.uninstall(target.id)
                    externalPlugins = pluginManager.list()
                }
                pendingDeleteExternal = null
            },
            destructive = true,
        )
    }
    // Provider 插件删除确认
    pendingDeleteProvider?.let { target ->
        MuseDialog(
            onDismissRequest = { pendingDeleteProvider = null },
            title = stringResource(R.string.provider_plugins_delete),
            content = {
                Text(
                    // ST-02: 点名 + 后果 + 可撤销(重新导入)
                    text = stringResource(R.string.provider_plugins_delete_confirm_named, target.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            confirmText = stringResource(R.string.provider_plugins_delete),
            onConfirm = {
                scope.launch {
                    registry.unregister(target.id)
                    providerPlugins = registry.list()
                }
                pendingDeleteProvider = null
            },
            destructive = true,
        )
    }
}

private data class PendingExternalInstall(
    val uri: Uri,
    val preview: PluginSecurityGate.InstallPreview,
)

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
    )
}

@Composable
private fun InstalledPluginRow(
    plugin: PluginManager.InstalledPlugin,
    onToggle: () -> Unit,
    onConfirm: (() -> Unit)?,
    onDelete: () -> Unit,
    /** 未签名草稿的「签名并启用」入口；null 表示该行不提供（非草稿或已确认）。 */
    onSignAndEnable: (() -> Unit)? = null,
    /** 可回滚到的历史版本；null 表示没有可回退的副本。 */
    rollbackVersion: String? = null,
    onRollback: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.itemGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = plugin.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // ST-04: 面向用户只显示版本/工具数;插件 id/指纹/sha256 收进「开发者信息」折叠区
                    text = stringResource(R.string.plugin_summary_brief, plugin.version, plugin.tools.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = signatureStatusLabel(plugin.signatureStatus),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = if (!plugin.installationConfirmed) {
                    stringResource(R.string.muse_plugins_draft)
                } else {
                    stringResource(if (plugin.enabled) R.string.skill_enabled else R.string.skill_disabled)
                },
                style = MaterialTheme.typography.labelMedium,
                color = if (plugin.enabled && plugin.installationConfirmed) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(Modifier.height(MusePaddings.itemGap))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
        ) {
            if (plugin.installationConfirmed) {
                MuseCapsuleButton(
                    text = stringResource(if (plugin.enabled) R.string.skill_disabled else R.string.skill_enabled),
                    onClick = onToggle,
                    modifier = Modifier.weight(1f),
                    variant = IosCapsuleButtonVariant.Secondary,
                )
            } else if (onSignAndEnable != null) {
                // 助手自写草稿：签名并启用是本行唯一能把它变成可执行的入口。
                MuseCapsuleButton(
                    text = stringResource(R.string.muse_plugins_sign_enable_action),
                    onClick = onSignAndEnable,
                    modifier = Modifier.weight(1f),
                    variant = IosCapsuleButtonVariant.Secondary,
                )
            } else if (onConfirm != null) {
                MuseCapsuleButton(
                    text = stringResource(
                        if (plugin.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED) {
                            R.string.muse_plugins_install_trust_publisher_action
                        } else {
                            R.string.muse_plugins_install_confirm_action
                        },
                    ),
                    onClick = { onConfirm?.invoke() },
                    modifier = Modifier.weight(1f),
                    variant = IosCapsuleButtonVariant.Secondary,
                )
            } else {
                Text(
                    text = stringResource(R.string.muse_plugins_unsigned_draft_only),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = MusePaddings.itemGap),
                )
            }
            MuseCapsuleButton(
                text = stringResource(R.string.provider_plugins_delete),
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                variant = IosCapsuleButtonVariant.Secondary,
            )
        }
        // 回滚入口：只有存在「非当前版本」的历史副本时才出现；真正的校验在回滚时重做。
        rollbackVersion?.let { version ->
            MuseCapsuleButton(
                text = stringResource(R.string.muse_plugins_rollback_action, version),
                onClick = { onRollback(version) },
                modifier = Modifier.fillMaxWidth(),
                variant = IosCapsuleButtonVariant.Secondary,
            )
        }
        // ST-04: 开发者信息默认折叠 — id/发布者指纹/内容摘要仅供排查,不打扰普通用户
        var showDevInfo by remember { mutableStateOf(false) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showDevInfo = !showDevInfo }
                .padding(vertical = MusePaddings.tightGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.muse_plugins_developer_info),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (showDevInfo) {
                    Icons.Filled.KeyboardArrowUp
                } else {
                    Icons.Filled.KeyboardArrowDown
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(MuseIconSizes.iconSmall),
            )
        }
        if (showDevInfo) {
            Column(verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap)) {
                Text(
                    text = stringResource(R.string.plugin_summary, plugin.id, plugin.version, plugin.tools.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (plugin.publisherId.isNotBlank() && plugin.publisherKeyFingerprint.isNotBlank()) {
                    Text(
                        text = stringResource(
                            R.string.muse_plugins_publisher_fingerprint,
                            plugin.publisherId,
                            plugin.publisherKeyFingerprint,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (plugin.contentSha256.isNotBlank()) {
                    Text(
                        text = plugin.contentSha256,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun signatureStatusLabel(status: PluginSecurityGate.SignatureStatus): String = when (status) {
    PluginSecurityGate.SignatureStatus.VALID_TRUSTED -> stringResource(R.string.muse_plugins_signature_trusted)
    PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED -> stringResource(R.string.muse_plugins_signature_untrusted)
    PluginSecurityGate.SignatureStatus.UNSIGNED -> stringResource(R.string.muse_plugins_signature_unsigned)
    PluginSecurityGate.SignatureStatus.INVALID -> stringResource(R.string.muse_plugins_signature_invalid)
    PluginSecurityGate.SignatureStatus.UNSUPPORTED -> stringResource(R.string.muse_plugins_signature_unsupported)
}

@Composable
private fun PluginCard(
    plugin: ProviderPlugin,
    onDelete: () -> Unit,
    onConvert: () -> Unit,
) {
    Surface(
        shape = MuseShapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MusePaddings.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = TablerIcons.Puzzle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(MuseIconSizes.iconMedium),
            )
            Spacer(Modifier.size(MusePaddings.iconPadding))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
            ) {
                Text(
                    text = plugin.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (plugin.description.isNotBlank()) {
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stringResource(R.string.plugin_models_summary, plugin.models.size, plugin.baseUrl),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(
                shape = MuseShapes.medium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.clickable(role = androidx.compose.ui.semantics.Role.Button) { onConvert() },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MusePaddings.contentGap, vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = TablerIcons.SwitchHorizontal,
                        contentDescription = stringResource(R.string.provider_plugins_convert),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(MuseIconSizes.iconSmall),
                    )
                    Spacer(Modifier.size(MusePaddings.tightGap))
                    Text(
                        text = stringResource(R.string.provider_plugins_convert),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
            Spacer(Modifier.size(MusePaddings.contentGap))
            MuseTactileButton(
                icon = TablerIcons.Trash,
                onClick = onDelete,
                contentDescription = stringResource(R.string.provider_plugins_delete),
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/** 市场安装任务阶段；Failed 携带面向用户的原因。 */
private sealed interface MarketInstallPhase {
    data object Downloading : MarketInstallPhase
    data object Preparing : MarketInstallPhase
    data object AwaitingConfirm : MarketInstallPhase
    data object Installing : MarketInstallPhase
    data class Failed(val reason: String) : MarketInstallPhase
}

/** 一次市场安装的 UI 状态；[plan] 只在下载+审查+计划全部成功后存在。 */
private data class MarketInstallJob(
    val entryId: String,
    val phase: MarketInstallPhase,
    val plan: PluginInstallPlan? = null,
    /** 未知发行者的显式信任标志；生成计划时固定，确认时按原值提交。 */
    val trustPublisher: Boolean = false,
)

@Composable
private fun MarketLoadingRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(MusePaddings.screen),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(MuseIconSizes.iconMedium),
            strokeWidth = 2.dp,
        )
    }
}

@Composable
private fun MarketErrorRow(
    reason: String,
    onRetry: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Icon(
            imageVector = TablerIcons.AlertCircle,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(MuseIconSizes.iconMedium),
        )
        Text(
            text = reason,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        MuseCapsuleButton(
            text = stringResource(R.string.common_retry),
            onClick = onRetry,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/** 目录版本低于已安装版本时不允许安装：降级会被版本策略拒绝，界面不应给出死路入口。 */
private fun isDowngrade(installedVersion: String?, catalogVersion: String): Boolean {
    val installed = installedVersion?.let(PluginVersion::parse) ?: return false
    val candidate = PluginVersion.parse(catalogVersion) ?: return false
    return candidate < installed
}

@Composable
private fun MarketEntryRow(
    entry: PluginCatalogEntry,
    installedVersion: String?,
    phase: MarketInstallPhase?,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onDismissFailure: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = entry.name,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stringResource(R.string.muse_plugins_market_entry_meta, entry.version, entry.publisherId),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (entry.description.isNotBlank()) {
            Text(
                text = entry.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(
                R.string.muse_plugins_market_entry_capabilities,
                entry.capabilities.ifEmpty { listOf(stringResource(R.string.muse_plugins_none)) }
                    .joinToString(", "),
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when {
                phase == MarketInstallPhase.Downloading || phase == MarketInstallPhase.Preparing -> {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(
                            if (phase == MarketInstallPhase.Downloading) {
                                R.string.muse_plugins_market_downloading
                            } else {
                                R.string.muse_plugins_market_preparing
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_cancel),
                        onClick = onCancel,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                    )
                }
                phase == MarketInstallPhase.AwaitingConfirm -> Text(
                    text = stringResource(R.string.muse_plugins_market_awaiting_confirm),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                phase == MarketInstallPhase.Installing -> {
                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.muse_plugins_market_installing),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                phase is MarketInstallPhase.Failed -> {
                    Text(
                        text = stringResource(R.string.muse_plugins_market_install_failed, phase.reason),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_retry),
                        onClick = onRetry,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                    )
                    MuseCapsuleButton(
                        text = stringResource(R.string.common_close),
                        onClick = onDismissFailure,
                        variant = IosCapsuleButtonVariant.Secondary,
                        fillWidth = false,
                    )
                }
                installedVersion == entry.version -> Text(
                    text = stringResource(R.string.muse_plugins_market_installed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                isDowngrade(installedVersion, entry.version) -> Text(
                    // 目录版本低于已安装版本：安装会被版本策略拒绝，不给死路入口。
                    text = stringResource(R.string.muse_plugins_market_downgrade_blocked, installedVersion.orEmpty()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                else -> MuseCapsuleButton(
                    text = stringResource(
                        if (installedVersion != null) {
                            R.string.muse_plugins_market_update_action
                        } else {
                            R.string.muse_plugins_market_install_action
                        },
                    ),
                    onClick = onInstall,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** P0-9: 信任的发行者行 — 展示 id + 指纹,提供撤销入口。 */
@Composable
private fun TrustedPublisherRow(
    publisherId: String,
    fingerprint: String,
    onRevoke: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = publisherId,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(R.string.muse_plugins_trust_publisher_fingerprint, fingerprint),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        MuseCapsuleButton(
            text = stringResource(R.string.muse_plugins_trust_revoke_action),
            onClick = onRevoke,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/** P0-9: 目录信任根行 — 展示 keyId,提供移除入口(内置根不可移除)。 */
@Composable
private fun CatalogRootKeyRow(
    keyId: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = MusePaddings.tightGap),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
    ) {
        Text(
            text = keyId,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        MuseCapsuleButton(
            text = stringResource(R.string.muse_plugins_trust_catalog_remove_action),
            onClick = onRemove,
            variant = IosCapsuleButtonVariant.Secondary,
            fillWidth = false,
        )
    }
}

/** 安装确认预览：能力 / 工具 / 发行者指纹 / 签名状态；市场与本地导入共用同一份内容。 */
@Composable
private fun PluginInstallPreviewContent(preview: PluginSecurityGate.InstallPreview) {
    val unknownAuthor = stringResource(R.string.muse_plugins_unknown_author)
    val noCapabilities = stringResource(R.string.muse_plugins_none)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
    ) {
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_summary,
                preview.version,
                preview.author.ifBlank { unknownAuthor },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (preview.description.isNotBlank()) {
            Text(
                text = preview.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_capabilities,
                preview.capabilities.ifEmpty { listOf(noCapabilities) }
                    .joinToString(", "),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_tools,
                preview.tools.joinToString(", ") { it.name },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_install_confirm_code,
                preview.entryBytes,
                preview.extraFileCount,
                preview.entrySha256,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(
                R.string.muse_plugins_signature_status,
                signatureStatusLabel(preview.signatureStatus),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
        if (preview.publisherId.isNotBlank() && preview.publisherKeyFingerprint.isNotBlank()) {
            Text(
                text = stringResource(
                    R.string.muse_plugins_publisher_fingerprint,
                    preview.publisherId,
                    preview.publisherKeyFingerprint,
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = when (preview.signatureStatus) {
                PluginSecurityGate.SignatureStatus.UNSIGNED ->
                    stringResource(R.string.muse_plugins_install_unsigned_warning)
                PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED ->
                    stringResource(
                        R.string.muse_plugins_install_untrusted_warning,
                        preview.publisherId,
                        preview.publisherKeyFingerprint,
                    )
                PluginSecurityGate.SignatureStatus.VALID_TRUSTED ->
                    stringResource(R.string.muse_plugins_install_trusted_warning)
                PluginSecurityGate.SignatureStatus.INVALID ->
                    stringResource(R.string.muse_plugins_signature_invalid)
                PluginSecurityGate.SignatureStatus.UNSUPPORTED ->
                    stringResource(R.string.muse_plugins_signature_unsupported)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (preview.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
    }
}
