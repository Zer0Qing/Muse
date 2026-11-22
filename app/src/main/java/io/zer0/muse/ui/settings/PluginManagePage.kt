package io.zer0.muse.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.zer0.ai.plugin.ProviderPlugin
import io.zer0.ai.plugin.ProviderPluginRegistry
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.SettingsRepository
import io.zer0.muse.data.plugin.ConfigItem
import io.zer0.muse.data.plugin.ConfigItemType
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import io.zer0.muse.data.plugin.PluginCapabilityLedgers
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginRuntimeHealth
import io.zer0.muse.data.plugin.PluginSecurityGate
import io.zer0.muse.data.plugin.PluginVersion
import io.zer0.muse.data.plugin.market.CatalogRefreshResult
import io.zer0.muse.data.plugin.market.InstallPlanResult
import io.zer0.muse.data.plugin.market.MarketVersionAdvice
import io.zer0.muse.data.plugin.market.MarketVersionView
import io.zer0.muse.data.plugin.market.PluginCatalogEntry
import io.zer0.muse.data.plugin.market.PluginCatalogRepository
import io.zer0.muse.data.plugin.market.PluginDownloadClient
import io.zer0.muse.data.plugin.market.PluginInstallCoordinator
import io.zer0.muse.data.plugin.market.PluginInstallPlan
import io.zer0.muse.data.plugin.market.PluginMarketDefaults
import io.zer0.muse.data.plugin.market.PluginMarketInstaller
import io.zer0.muse.data.plugin.market.PluginMarketTrustRoots
import io.zer0.muse.data.plugin.market.PluginMarketVersions
import io.zer0.muse.ui.common.feedback.MuseDialog
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.ui.common.form.IosCapsuleButtonVariant
import io.zer0.muse.ui.common.form.MuseBottomSheet
import io.zer0.muse.ui.common.form.MuseCapsuleButton
import io.zer0.muse.ui.common.form.MuseCapsuleTab
import io.zer0.muse.ui.common.form.MuseDropdown
import io.zer0.muse.ui.common.form.MuseFloatingButton
import io.zer0.muse.ui.common.form.MuseFormDialog
import io.zer0.muse.ui.common.form.MuseSwitch
import io.zer0.muse.ui.common.form.MuseTactileButton
import io.zer0.muse.ui.common.form.MuseTextField
import io.zer0.muse.ui.common.icons.MuseIcons
import io.zer0.muse.ui.common.media.WindowWidthClass
import io.zer0.muse.ui.common.media.rememberWindowWidthClass
import io.zer0.muse.ui.common.navigation.MuseTopBar
import io.zer0.muse.ui.common.state.MuseEmptyState
import io.zer0.muse.ui.common.state.MuseIndeterminateProgressBar
import io.zer0.muse.ui.common.state.MuseSpinner
import io.zer0.muse.ui.common.surface.MuseSurface
import io.zer0.muse.ui.markdown.RichContentCard
import io.zer0.muse.ui.theme.MuseActionColors
import io.zer0.muse.ui.theme.MuseIconSizes
import io.zer0.muse.ui.theme.MusePaddings
import io.zer0.muse.ui.theme.MuseShapes
import io.zer0.muse.ui.theme.titleSmallStrong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
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
fun PluginManagePage(onBack: () -> Unit) {
    val pluginManager: PluginManager = koinInject()
    val registry: ProviderPluginRegistry = koinInject()
    val settings: SettingsRepository = koinInject()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val widthClass = rememberWindowWidthClass()

    var externalPlugins by remember { mutableStateOf(pluginManager.list()) }
    var providerPlugins by remember { mutableStateOf(registry.list()) }
    var importing by remember { mutableStateOf(false) }

    /** UI-FIX: 插件页三档层级 —— 0 已安装 / 1 市场 / 2 信任管理。 */
    var pluginTab by rememberSaveable { mutableIntStateOf(0) }
    // v2.6.13: 插件总开关。关闭后所有插件工具/Hook/激活都不执行，但安装与配置保留。
    val pluginsEnabled by settings.pluginsGloballyEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    var pendingExternalInstall by remember { mutableStateOf<PendingExternalInstall?>(null) }
    var pendingDeleteExternal by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }
    var pendingDeleteProvider by remember { mutableStateOf<ProviderPlugin?>(null) }

    /** 待用户确认“签名并启用”的助手自写草稿；确认前不会签名、不会写信任根。 */
    var pendingSignDraft by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }

    // ── Phase 5: 插件市场（签名目录浏览 + 一键安装）──
    // 目录 URL / 信任根保存在 DataStore；仓库缓存目录与已接受 sequence，拒绝回退与过期。
    // v2.0.1: 市场组件改注入 Koin 单例 — 与后台工具(plugin_market_search/install)共享
    // 同一份目录缓存与已接受 sequence，避免双实例导致回退防护出现分叉。
    val marketTrustRoots: PluginMarketTrustRoots = koinInject()
    val marketSettings = marketTrustRoots.settings
    val marketRepository: PluginCatalogRepository = koinInject()
    val catalogRootKeys by marketTrustRoots.keys.collectAsStateWithLifecycle()
    // 信任根变更后重建协调器：目录验签必须始终使用当前配置的信任根。
    val marketCoordinator = remember(marketRepository, catalogRootKeys) {
        PluginInstallCoordinator(
            trustRootKeys = catalogRootKeys,
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
    // 市场网格里点开的那张卡（null = 未打开详情）
    var marketDetailEntry by remember { mutableStateOf<PluginCatalogEntry?>(null) }

    /** 待确认的回滚请求：插件 id 与目标版本。 */
    var rollbackRequest by remember { mutableStateOf<Pair<String, String>?>(null) }

    // ── P0-9: 信任管理(撤销入口)──
    /** 信任的发行者列表(与安装校验同一 trust store 实例)。 */
    var trustedPublishers by remember { mutableStateOf(pluginManager.trustedPublishers()) }

    /** 待确认撤销的发行者 id。 */
    var revokePublisherId by remember { mutableStateOf<String?>(null) }

    /** P4 调试台：最近桥接调用（拒绝原因在这里看，不用去翻日志）。 */
    var debugVisible by remember { mutableStateOf(false) }
    if (debugVisible) {
        PluginDebugDialog(onDismiss = { debugVisible = false })
    }

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
        marketTrustRoots.reload()
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
                actions = {
                    io.zer0.muse.ui.common.form.MuseTactileButton(
                        icon = MuseIcons.bug,
                        onClick = { debugVisible = true },
                        contentDescription = stringResource(R.string.plugin_debug_title),
                    )
                },
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = {
            MuseFloatingButton(
                icon = MuseIcons.plus,
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
            // 安全模式总闸：插件全停时，页面顶部必须说清"为什么什么都不执行"，
            // 并给出唯一的恢复入口 —— 总闸刻意不自动恢复。
            if (io.zer0.muse.data.plugin.PluginKillSwitch.isDisabled(context)) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                        verticalArrangement = Arrangement.spacedBy(MusePaddings.tightGap),
                    ) {
                        Text(
                            text = stringResource(R.string.plugin_kill_switch_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = stringResource(R.string.plugin_kill_switch_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        MuseCapsuleButton(
                            text = stringResource(R.string.plugin_kill_switch_restore),
                            onClick = {
                                io.zer0.muse.data.plugin.PluginKillSwitch.setDisabled(context, false)
                                externalPlugins = pluginManager.list()
                            },
                        )
                    }
                }
            }
            // UI-FIX: 顶部分档 —— 已安装 / 市场 / 信任管理，不再把三块竖堆成一条长列表
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                    contentAlignment = Alignment.Center,
                ) {
                    MuseCapsuleTab(
                        tabs = listOf(
                            stringResource(R.string.muse_plugins_tab_installed),
                            stringResource(R.string.muse_plugins_market_title),
                            stringResource(R.string.muse_plugins_trust_title),
                        ),
                        selectedIndex = pluginTab,
                        onSelect = { pluginTab = it },
                        modifier = Modifier.widthIn(max = 260.dp),
                    )
                }
            }
            // v2.6.13: 插件总开关。放在分档上方而不是埋在某个 tab 里 ——
            // 它的作用是「插件出问题时能一键止损」，用户不该先找到对的页签才能用。
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.muse_plugins_global_switch_title),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.muse_plugins_global_switch_desc),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    MuseSwitch(
                        checked = pluginsEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch { settings.savePluginsGloballyEnabled(enabled) }
                        },
                    )
                }
            }
            if (pluginTab == 1) {
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
                            style = MaterialTheme.typography.titleSmallStrong,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.weight(1f),
                            // LAYOUT-01: Row 内文本取宽 + 限行，防放大字号下被右侧刷新/更多按钮挤成一列
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (marketRefreshing) {
                            MuseSpinner(
                                size = MuseIconSizes.iconSmall,
                            )
                        } else if (catalogUrl?.isNotBlank() == true) {
                            MuseTactileButton(
                                icon = MuseIcons.refresh,
                                onClick = { refreshMarketCatalog() },
                                contentDescription = stringResource(R.string.muse_plugins_market_refresh),
                            )
                        }
                        MuseTactileButton(
                            icon = MuseIcons.sliders,
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
                            icon = MuseIcons.puzzle,
                            title = stringResource(R.string.muse_plugins_market_not_configured),
                            actionText = stringResource(R.string.muse_plugins_market_configure),
                            onAction = { showMarketSettings = true },
                        )
                    }
                    marketEntries.isEmpty() && marketRefreshing -> item { MarketLoadingRow() }
                    marketEntries.isEmpty() -> item {
                        val failure = marketError
                        MuseEmptyState(
                            icon = if (failure != null) MuseIcons.alertCircle else MuseIcons.puzzle,
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
                        // 两列网格：每排两张插件卡，点开进详情页再安装（原大列表太占地方）。
                        // 注意不用 LazyVerticalGrid：它会嵌进当前 LazyColumn 造成无界高度崩溃，
                        // 这里按「每排两格」成对排布，视觉等价且结构安全。
                        items(marketEntries.chunked(2), key = { "market_row_${it.first().id}" }) { pair ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = MusePaddings.screen, vertical = MusePaddings.tightGap),
                                horizontalArrangement = Arrangement.spacedBy(MusePaddings.itemGap),
                                verticalAlignment = Alignment.Top,
                            ) {
                                pair.forEach { entry ->
                                    MarketEntryCell(
                                        entry = entry,
                                        installedVersion = externalPlugins.firstOrNull { it.id == entry.id }?.version,
                                        phase = marketInstall?.takeIf { it.entryId == entry.id }?.phase,
                                        onClick = { marketDetailEntry = entry },
                                        modifier = Modifier.weight(1f),
                                        // PL3:可切回的版本来自本机留档,不是市场目录(目录只有一个版本,
                                        // 而且要等签名三端对账完才可能带 versions[])。
                                        retainedVersions = pluginManager.listRetainedVersions(entry.id).map { it.record.version },
                                    )
                                }
                                if (pair.size == 1) {
                                    Spacer(Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                // ── P0-9: 信任管理(信任的发行者 / 目录信任根)──
            }
            if (pluginTab == 2) {
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
                        val userRootKeys = catalogRootKeys.keys - PluginMarketDefaults.catalogRootKeys.keys
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
                                            marketTrustRoots.reload()
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
            }
            if (pluginTab == 0) {
                // ── 外部插件区 ──
                item { SectionHeader(stringResource(R.string.muse_plugins_external)) }
                if (importing && externalPlugins.isEmpty()) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            MuseSpinner(
                                size = MuseIconSizes.iconMedium,
                            )
                        }
                    }
                } else if (externalPlugins.isEmpty()) {
                    item {
                        MuseEmptyState(
                            icon = MuseIcons.puzzle,
                            title = stringResource(R.string.muse_plugins_empty_hint),
                        )
                    }
                } else {
                    items(externalPlugins, key = { "ext_${it.id}" }) { plugin ->
                        val verified = pluginManager.loadVerifiedPlugin(plugin.id)
                        val configEntries = verified?.let {
                            pluginManager.getPluginConfigsWithDefaults(it.manifest)
                        }
                        val panelHtml = remember(plugin.id, plugin.version) {
                            if (plugin.enabled) pluginManager.loadPluginPanel(plugin.id) else null
                        }
                        InstalledPluginRow(
                            plugin = plugin,
                            configEntries = configEntries,
                            panelHtml = panelHtml,
                            onConfigChanged = { key, value ->
                                scope.launch {
                                    pluginManager.setPluginConfig(plugin.id, key, value)
                                    externalPlugins = pluginManager.list()
                                }
                            },
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
                        // D4：声明式界面（宿主原生渲染）优先；HTML 面板仍在行内保留作逃生口。
                        // 做成兄弟组件而不是给 InstalledPluginRow 加参数：那个函数的多个告警
                        // 由 detekt 基线**按签名**压制，加参数会让它们集体复活。
                        val declarativeView = remember(plugin.id, plugin.version, plugin.enabled) {
                            if (plugin.enabled) pluginManager.loadUiView(plugin.id) else null
                        }
                        // 提交链路：账本判「交互界面」是否已批准，执行走 SkillMediaToolsImpl 的同一个入口
                        // （与工具路径共用桥接路由器，不另建第二条）。koinInject 必须在组合作用域取。
                        val mediaTools: io.zer0.muse.tools.SkillMediaToolsImpl = koinInject()
                        io.zer0.muse.ui.plugin.PluginDeclarativeViewEntry(
                            pluginName = plugin.name,
                            loaded = declarativeView,
                            assetsRoot = if (plugin.enabled) pluginManager.pluginDirFor(plugin.id) else null,
                            interactiveEnabled = PluginCapabilityLedgers.shared(context)
                                .isApproved(plugin.id, PluginCapabilityGateway.UI_INTERACTIVE),
                            disabledReason = stringResource(R.string.plugin_ui_needs_interactive),
                            onSubmit = { handler, values ->
                                when (
                                    val outcome = mediaTools.invokeUiSubmitHandler(plugin.id, handler, values)
                                ) {
                                    is io.zer0.muse.tools.SkillMediaToolsImpl.UiSubmitResult.Tree ->
                                        io.zer0.muse.ui.plugin.PluginUiSubmitOutcome.Rendered(outcome.parsed)
                                    is io.zer0.muse.tools.SkillMediaToolsImpl.UiSubmitResult.Rejected ->
                                        io.zer0.muse.ui.plugin.PluginUiSubmitOutcome.Rejected(outcome.reason)
                                }
                            },
                        )
                        // P0-3: 能力与权限。P0-2 之后声明真的会拦请求，这里必须给用户出口：
                        // 看得到授予了什么、最近被拒过什么、升级新增能力要重新批准。
                        PluginPermissionDisclosure(
                            pluginId = plugin.id,
                            declaredCapabilities = verified?.manifest?.capabilities.orEmpty(),
                            runtime = verified?.manifest?.runtime.orEmpty(),
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
                            icon = MuseIcons.puzzle,
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
                MuseIndeterminateProgressBar(
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            dismissText = null,
        )
    }

    // 市场目录设置：默认使用安装包内置的官方目录，这里只处理“自定义覆盖”。
    marketDetailEntry?.let { entry ->
        val detailId = entry.id
        val detailInstalled = externalPlugins.firstOrNull { it.id == detailId }?.version
        MarketEntryDetailSheet(
            entry = entry,
            installedVersion = detailInstalled,
            phase = marketInstall?.takeIf { it.entryId == detailId }?.phase,
            onInstall = { startMarketInstall(entry) },
            onCancel = { dismissMarketInstall() },
            onRetry = { startMarketInstall(entry) },
            onDismissFailure = { dismissMarketInstall() },
            onDismiss = { marketDetailEntry = null },
            versionView = PluginMarketVersions.view(
                catalogVersion = entry.version,
                installedVersion = detailInstalled,
                retainedVersions = pluginManager.listRetainedVersions(detailId).map { it.record.version },
                appVersion = io.zer0.muse.BuildConfig.VERSION_NAME,
            ),
        )
    }

    if (showMarketSettings) {
        var urlInput by remember { mutableStateOf(catalogUrlOverride) }
        var keyIdInput by remember {
            // 内置官方信任根不可覆盖，因此 keyId 初值只取用户自己追加的那把。
            mutableStateOf(
                (catalogRootKeys.keys - PluginMarketDefaults.catalogRootKeys.keys)
                    .firstOrNull()
                    .orEmpty(),
            )
        }
        var publicKeyInput by remember { mutableStateOf("") }
        var settingsError by remember { mutableStateOf<String?>(null) }
        MuseFormDialog(
            onDismissRequest = { showMarketSettings = false },
            title = stringResource(R.string.muse_plugins_market_settings_title),
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
                    marketTrustRoots.reload()
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
