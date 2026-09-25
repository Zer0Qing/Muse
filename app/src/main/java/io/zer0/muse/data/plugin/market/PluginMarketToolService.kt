package io.zer0.muse.data.plugin.market

import android.content.Context
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.plugin.PluginSecurityGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * v2.0.1: 插件市场的工具侧服务 — 供 `plugin_market_search` / `plugin_market_install` 使用。
 *
 * 与插件管理页共享同一套市场组件（目录缓存、信任根、staging 目录），保证后台工具
 * 与 UI 看到的是同一份已验签目录与已接受 sequence，不产生双实例状态分叉。
 *
 * 安全边界：
 *  - 搜索是只读操作；
 *  - 安装必须经过会话审批卡（工具注册为 HIGH 风险），并复用 [PluginManager] 的完整
 *    审查链路（ZIP 结构、能力白名单、发行者签名、内容摘要）与 [PluginInstallCoordinator]
 *    的目录绑定校验，不提供任何绕过用户确认的路径；
 *  - 未知发行者默认拒绝：只有调用方显式传 `trustPublisher=true`（表示用户已在对话中
 *    确认信任该发行者）才会写入信任根并继续安装。
 */
internal class PluginMarketToolService(
    private val context: Context,
    private val pluginManager: PluginManager,
    private val marketSettings: PluginMarketSettings,
    private val trustRoots: PluginMarketTrustRoots,
    private val repository: PluginCatalogRepository,
) : PluginMarketToolGateway {

    private val downloadClient = PluginDownloadClient(
        stagingDir = File(context.cacheDir, "plugin_market_staging"),
    )
    private val installer = PluginMarketInstaller(pluginManager)

    /** 检索插件目录；返回给模型阅读的文本清单。 */
    override suspend fun search(query: String): String {
        val catalog = ensureCatalog().getOrElse { error ->
            return "插件市场暂时不可用：${error.message ?: "未知错误"}"
        }
        return renderSearchResult(catalog, query)
    }

    /** 下载并安装指定插件；返回给模型阅读的执行结果。 */
    override suspend fun install(entryId: String, trustPublisher: Boolean): String {
        val id = entryId.trim()
        if (id.isBlank()) return "安装失败：缺少 plugin_id。"
        return runInstall(id, trustPublisher)
    }

    // ── 检索 ─────────────────────────────────────────────────────────────

    private fun renderSearchResult(catalog: SignedPluginCatalog, query: String): String {
        val entries = catalog.payload.entries
        val installedById = pluginManager.list().associateBy { it.id }
        val trustedPublishers = pluginManager.trustedPublishers()
        val keyword = query.trim().lowercase()
        val matched = if (keyword.isEmpty()) {
            entries
        } else {
            entries.filter { entry ->
                entry.id.lowercase().contains(keyword) ||
                    entry.name.lowercase().contains(keyword) ||
                    entry.description.lowercase().contains(keyword)
            }
        }
        if (matched.isEmpty()) {
            return if (entries.isEmpty()) {
                "插件市场目前没有可安装的插件。"
            } else {
                "没有匹配「$query」的插件。"
            }
        }
        val builder = StringBuilder()
        builder.appendLine("插件市场 ${entries.size} 个插件，匹配 ${matched.size} 个：")
        matched.take(MAX_SEARCH_RESULTS).forEach { entry ->
            val installedVersion = installedById[entry.id]?.version
            val publisherTrusted = trustedPublishers.any { publisher ->
                publisher.publisherId == entry.publisherId &&
                    publisher.fingerprint.equals(entry.publisherKeyFingerprint, ignoreCase = true)
            }
            builder.appendLine(
                "- ${entry.id} v${entry.version}「${entry.name}」：${entry.description.take(DESCRIPTION_LIMIT)}" +
                    "（发行者 ${entry.publisherId}" +
                    (if (publisherTrusted) "，已受信任" else "，未受本机信任") +
                    "；" + (if (installedVersion != null) "已安装 v$installedVersion" else "未安装") + "）",
            )
        }
        if (matched.size > MAX_SEARCH_RESULTS) {
            builder.appendLine("（共 ${matched.size} 个，仅列出前 $MAX_SEARCH_RESULTS 个）")
        }
        builder.append(
            "安装用 plugin_market_install（plugin_id 见上）。安装会弹出审批卡片；" +
                "发行者未受信任时，工具会先返回待确认信息，请把发行者信息展示给用户，" +
                "用户确认后再带 trust_publisher=true 重新调用。",
        )
        return builder.toString()
    }

    // ── 安装 ─────────────────────────────────────────────────────────────

    /**
     * 安装主流程（不含输入校验）。
     *
     * 失败路径统一抛 [InstallFailed]，避免多出口散落；成功输出两种形态：
     *  - 发行者未受信任且调用方未确认 → 返回待确认信息（不落盘）；
     *  - 安装完成 → 返回安装结果。
     */
    private suspend fun runInstall(id: String, trustPublisher: Boolean): String {
        var stagedFile: File? = null
        var planCreated = false
        return try {
            val catalog = ensureCatalog().getOrElse { error ->
                throw InstallFailed("安装失败：${error.message ?: "插件市场暂时不可用"}")
            }
            val staged = requireStaged(catalog, id)
            stagedFile = staged.artifact.file
            val decision = requireAllowed(reviewArtifact(staged.artifact))
            if (decision.requiresPublisherTrust && !trustPublisher) {
                publisherTrustPrompt(decision, staged.entry)
            } else {
                val plan = buildPlan(catalog, staged.entry, staged.artifact, decision)
                planCreated = true
                val result = installer.confirmAndInstall(
                    plan,
                    trustPublisher = decision.requiresPublisherTrust,
                )
                installer.discard(plan)
                val installed = result.getOrElse { error ->
                    throw InstallFailed("安装失败：${error.message ?: "安装提交失败"}")
                }
                "已安装并启用插件：${installed.name} v${installed.version}（${installed.id}）。"
            }
        } catch (failed: InstallFailed) {
            failed.message ?: "安装失败"
        } finally {
            val staged = stagedFile
            if (!planCreated && staged != null) runCatching { staged.delete() }
        }
    }

    /** 定位目录条目并下载产物到 staging（大小/摘要校验在下载客户端内完成）；失败抛 [InstallFailed]。 */
    private suspend fun requireStaged(catalog: SignedPluginCatalog, id: String): StagedPlugin {
        val entry = catalog.payload.entries.firstOrNull { it.id == id }
            ?: throw InstallFailed("安装失败：目录中不存在插件 $id（先用 plugin_market_search 检索确认 id）。")
        val artifact = withContext(Dispatchers.IO) { downloadClient.download(entry) }.artifact
            ?: throw InstallFailed("安装失败：下载失败")
        return StagedPlugin(entry, artifact)
    }

    /** 审查插件包（结构/能力/发行者签名）；异常或不可预览时抛 [InstallFailed]。 */
    private suspend fun reviewArtifact(artifact: DownloadedArtifact): PluginSecurityGate.Decision =
        pluginManager.reviewFromFile(artifact.file).getOrElse { error ->
            throw InstallFailed("安装失败：${error.message ?: "插件审查失败"}")
        }

    /** 拒绝不可安装的审查结果：市场包必须签名有效且未被篡改。 */
    private fun requireAllowed(decision: PluginSecurityGate.Decision): PluginSecurityGate.Decision {
        if (!decision.allowed) {
            throw InstallFailed("安装失败：${decision.reason ?: "插件未通过安全审查"}")
        }
        if (decision.signature.status == PluginSecurityGate.SignatureStatus.UNSIGNED) {
            throw InstallFailed("安装失败：市场包必须带有发行者签名。")
        }
        return decision
    }

    /** 用当前信任根构造安装计划；目录校验失败抛 [InstallFailed]。 */
    private fun buildPlan(
        catalog: SignedPluginCatalog,
        entry: PluginCatalogEntry,
        artifact: DownloadedArtifact,
        decision: PluginSecurityGate.Decision,
    ): PluginInstallPlan {
        val coordinator = PluginInstallCoordinator(
            trustRootKeys = trustRoots.current(),
            lastAcceptedSequence = { catalogId -> repository.lastAcceptedSequence(catalogId) },
        )
        return when (
            val planResult = coordinator.preparePlan(
                signedCatalog = catalog,
                entryId = entry.id,
                stagedFile = artifact.file,
                artifactSha256 = artifact.sha256,
                reviewedPackage = PluginInstallCoordinator.PluginPackageReview(
                    preview = decision.preview,
                    contentSha256 = decision.preview.contentSha256,
                ),
            )
        ) {
            is InstallPlanResult.Ready -> planResult.plan
            is InstallPlanResult.Rejected -> throw InstallFailed("安装失败：${planResult.reason}")
        }
    }

    // ── 目录与信任 ───────────────────────────────────────────────────────

    /**
     * 确保目录可用：优先使用本地已验签缓存；缓存不可用时按当前配置刷新一次。
     *
     * 缓存可用时不做网络刷新（与插件管理页打开时的行为一致），避免每次搜索都等网络；
     * 缓存缺失/过期/损坏时刷新，失败返回具体原因。
     */
    private suspend fun ensureCatalog(): Result<SignedPluginCatalog> {
        trustRoots.reload()
        val cached = repository.loadCache().signedCatalog
        if (cached != null) return Result.success(cached)
        val url = marketSettings.catalogUrl()
        return if (url.isBlank()) {
            Result.failure(IllegalStateException("插件目录未配置"))
        } else {
            when (val refreshed = repository.refresh(url)) {
                is CatalogRefreshResult.Updated -> Result.success(refreshed.signedCatalog)
                is CatalogRefreshResult.Rejected ->
                    Result.failure(IllegalStateException("目录刷新失败：${refreshed.reason}"))
                CatalogRefreshResult.NotConfigured ->
                    Result.failure(IllegalStateException("插件目录未配置"))
            }
        }
    }

    /** 未受信任发行者的待确认信息；模型应展示给用户后再带 `trust_publisher=true` 重试。 */
    private fun publisherTrustPrompt(
        decision: PluginSecurityGate.Decision,
        entry: PluginCatalogEntry,
    ): String {
        val capabilities = (decision.preview.capabilities + decision.preview.permissions).distinct()
        return buildString {
            appendLine("需要用户确认信任发行者后才能安装（本机第一次安装该发行者的插件）。")
            appendLine("请先把下面信息展示给用户；用户确认后，重新调用 plugin_market_install 并带 trust_publisher=true：")
            appendLine("- 插件：${entry.name} v${entry.version}（${entry.id}）")
            appendLine("- 用途：${entry.description}")
            appendLine("- 发行者：${decision.signature.publisherId}")
            appendLine("- 公钥指纹：${decision.signature.fingerprint}")
            append("- 声明能力：${if (capabilities.isEmpty()) "无（仅提供工具）" else capabilities.joinToString()}")
        }
    }

    /** 已下载并落 staging 的插件（目录条目 + 本地产物）。 */
    private data class StagedPlugin(
        val entry: PluginCatalogEntry,
        val artifact: DownloadedArtifact,
    )

    /** 安装流程的控制流异常：只承载给用户的失败原因，不跨出 [runInstall]。 */
    private class InstallFailed(message: String) : Exception(message)

    private companion object {
        const val MAX_SEARCH_RESULTS = 30
        const val DESCRIPTION_LIMIT = 120
    }
}
