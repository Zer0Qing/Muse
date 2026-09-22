package io.zer0.muse.data.plugin

import android.content.Context
import android.net.Uri
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.BuildConfig
import io.zer0.muse.R
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.script.ToolDeclaration
import io.zer0.muse.ui.theme.BubbleSkinValidator
import io.zer0.muse.ui.theme.InstalledSkin
import io.zer0.muse.ui.theme.PluginSkinSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.util.Base64
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * 插件配置存储：按插件 id 存储用户自定义配置值。
 *
 * 存储在 `filesDir/plugin_configs.json`，格式为 `{"pluginId": {"key": value}}`。
 * 使用 AtomicFileStore 保证写入原子性，避免进程被杀留下半个 JSON。
 */
@Serializable
data class PluginConfigStore(val configs: Map<String, Map<String, JsonElement>> = emptyMap()) {
    companion object {
        fun fromFile(file: File): PluginConfigStore {
            if (!file.exists()) return PluginConfigStore()
            return runCatching { AppJson.decodeFromString<PluginConfigStore>(file.readText()) }
                .getOrElse { PluginConfigStore() }
        }
        fun toFile(file: File, store: PluginConfigStore) {
            AtomicFileStore.writeText(file, AppJson.encodeToString(PluginConfigStore.serializer(), store))
        }
    }
}

/**
 * B6-01: 外部插件管理器。
 *
 * 插件包安装到 `filesDir/plugins/<id>/`，注册表保存在 `filesDir/plugin_registry.json`；
 * 插件工具以 `plugin_<pluginId>_<toolName>` 写入 skills 表，执行时由 SkillExecutor
 * 按 `plugin:<pluginId>:<functionName>` 路由到 JS 沙盒。
 *
 * 同时作为 [PluginSkinSource] 暴露 `kind == "ui-skin"` 的声明式皮肤；皮肤读取复用
 * 已安装包的重新校验路径，不执行任何插件代码。
 */
@Suppress("TooGenericExceptionCaught", "FunctionParameterNaming")
class PluginManager(
    private val context: Context,
    private val skillRepository: SkillRepository,
    /** 发行者信任根只允许来自 Android 私有存储；测试可注入临时文件实现。 */
    private val trustStore: PluginTrustStore = PluginTrustStore(context),
    /** 历史版本库：保留最近若干已校验版本，供用户主动回滚；刻意放在插件目录之外。 */
    private val versionStore: PluginVersionStore =
        PluginVersionStore(File(context.filesDir, "plugin_versions")),
    /** 当前 App 版本，用于强制 manifest 的 minAppVersion。 */
    private val appVersionName: String = BuildConfig.VERSION_NAME,
    /**
     * 本机作者密钥：助手起草的插件只有在用户明确确认后，才由它签名并启用。
     *
     * 与信任根一样放在 Android 私有存储；测试可注入临时文件实现。
     */
    private val localAuthorKeyStore: LocalAuthorKeyStore = LocalAuthorKeyStore(context),
) : PluginSkinSource {

    @Serializable
    data class InstalledPlugin(
        val id: String,
        val name: String,
        val version: String,
        val author: String = "",
        val description: String = "",
        val entry: String = "main.js",
        val kind: String = "tool",
        val trust: String = "sandboxed",
        val capabilities: List<String> = emptyList(),
        val permissions: List<String> = emptyList(),
        val tools: List<ToolDeclaration> = emptyList(),
        /**
         * 是否启用。外部插件首次安装固定为 false，必须由用户确认后显式启用。
         * 旧注册表缺少该字段时保持旧值 true，避免破坏既有导入兼容性。
         */
        val enabled: Boolean = true,
        /** 已安装插件内容的 SHA-256，用于检测插件文件被替换。旧注册表缺失时为空。 */
        val contentSha256: String = "",
        val installedAt: Long = System.currentTimeMillis(),
        /**
         * 是否完成过本次插件内容的明确安装确认。
         * 缺失该字段的旧注册表按“未确认”处理，避免在无法证明用户确认时静默执行；
         * 旧包仍会保留在注册表中，用户可重新查看摘要后确认，不破坏导入数据兼容性。
         * 放在已有参数之后，保持旧的按位置构造调用兼容。
         */
        val installationConfirmed: Boolean = false,
        /** 最近一次安装/审查得到的签名状态；执行前仍会重新验证，不信任此缓存字段。 */
        val signatureStatus: PluginSecurityGate.SignatureStatus =
            PluginSecurityGate.SignatureStatus.UNSIGNED,
        val publisherId: String = "",
        val publisherKeyFingerprint: String = "",
        /**
         * 本插件历次安装/确认注册过的 skill id 全集（含长度前缀编码前的旧格式 id）。
         *
         * 卸载/更新时按该集合清理，避免只依赖当前 manifest 而残留旧版本 skill。
         * 旧注册表缺失该字段时为空列表，此时回退到当前 manifest 推导清理目标。
         */
        val registeredSkillIds: List<String> = emptyList(),
    )

    /**
     * 重新验证后的插件运行视图。
     *
     * [capabilities]/[tools]/[entryCode] 全部来自磁盘上重新读取并通过安全门复核的
     * manifest，而不是 [InstalledPlugin] 的注册表缓存字段；执行路径必须使用本视图。
     */
    data class VerifiedPlugin(
        val id: String,
        val manifest: PluginManifest,
        val capabilities: List<String>,
        val tools: List<ToolDeclaration>,
        val entryCode: String,
    )

    @Serializable
    private data class PluginRegistry(val plugins: List<InstalledPlugin> = emptyList())

    private val pluginsDir = File(context.filesDir, "plugins")
    private val registryFile = File(context.filesDir, "plugin_registry.json")
    private val configStoreFile = File(context.filesDir, "plugin_configs.json")
    private var configStore: PluginConfigStore = PluginConfigStore.fromFile(configStoreFile)

    @Volatile
    private var cached: List<InstalledPlugin> = loadRegistry()

    /** R-SVC-07: registry 读改写互斥,避免并发安装/卸载写坏 JSON。 */
    private val registryMutex = Mutex()

    /** P2-26: 按插件 id 的提交互斥 — 同插件并发安装/卸载串行化,消除 staging 交错覆盖。 */
    private val installLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()

    /**
     * 读取并审查外部插件包，返回不含源码的安全决策和安装预览。
     */
    suspend fun reviewFromUri(uri: Uri): Result<PluginSecurityGate.Decision> =
        withTempPluginFile(uri, "muse-plugin") { file -> reviewFromFile(file) }

    /**
     * 将预览绑定的无签名包保存为禁用草稿。
     *
     * 该路径不会写入信任根、不会注册为可执行插件，并会重新比较内容摘要，避免用户确认
     * 预览后 URI 被替换成另一份包。有效签名包应使用 [installConfirmedFromUri]。
     */
    suspend fun installDraftFromUri(
        uri: Uri,
        expectedPreview: PluginSecurityGate.InstallPreview,
    ): Result<InstalledPlugin> = withTempPluginFile(uri, "muse-plugin") { file ->
        installDraftFromFile(file, expectedPreview)
    }

    suspend fun installDraftFromFile(
        file: File,
        expectedPreview: PluginSecurityGate.InstallPreview,
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val loaded = loadPackage(file)
            .getOrElse { error -> return@withContext Result.failure(error) }
        val decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        if (!decision.allowed) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "插件安全审查未通过"),
            )
        }
        if (!PluginSecurityGate.hasSameContentIdentity(expectedPreview, decision.preview)) {
            return@withContext Result.failure(
                IllegalStateException("插件内容在确认后发生变化，请重新预览"),
            )
        }
        commitInstall(
            loaded,
            installationConfirmed = false,
            signature = decision.signature,
        )
    }

    /** 读取文件并返回纯逻辑安全门的决策，供确认 UI 和单测使用。 */
    suspend fun reviewFromFile(file: File): Result<PluginSecurityGate.Decision> =
        withContext(Dispatchers.IO) {
            val loaded = loadPackage(file)
                .getOrElse { error -> return@withContext Result.failure(error) }
            Result.success(PluginSecurityGate.review(loaded, trustedPublisherKeys()))
        }

    /** 当前 Android 私有信任根的只读公钥映射；不把私钥或存储路径暴露给调用方。 */
    private fun trustedPublisherKeys(): Map<String, String> =
        trustStore.list().associate { it.publisherId to it.publicKey }

    /**
     * 在用户已查看 [expectedPreview] 后确认并安装外部插件。
     *
     * 包会重新读取、解析并重新计算摘要；如果 URI/文件在预览后被替换，确认会失败而不会
     * 写入插件目录或注册表，避免“预览 A、执行 B”的 TOCTOU 绕过。
     */
    suspend fun installConfirmedFromUri(
        uri: Uri,
        expectedPreview: PluginSecurityGate.InstallPreview,
        trustPublisher: Boolean = false,
    ): Result<InstalledPlugin> = withTempPluginFile(uri, "muse-plugin") { file ->
        installConfirmedFromFile(file, expectedPreview, trustPublisher)
    }

    /**
     * 确认并安装一个与预览摘要一致的外部插件文件。
     *
     * [trustPublisher] 只在用户明确点击“信任发行者并安装”时传 true；未知发行者的
     * 公钥会先经过签名校验，再原子写入 Android 私有信任根，随后用同一份已加载包重新验签。
     * 未签名/无效签名包永远不能通过此路径成为可执行插件。
     */
    suspend fun installConfirmedFromFile(
        file: File,
        expectedPreview: PluginSecurityGate.InstallPreview,
        trustPublisher: Boolean = false,
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val loaded = loadPackage(file)
            .getOrElse { error -> return@withContext Result.failure(error) }
        var decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        if (!decision.allowed) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "插件安全审查未通过"),
            )
        }
        if (!PluginSecurityGate.hasSameContentIdentity(expectedPreview, decision.preview)) {
            return@withContext Result.failure(
                IllegalStateException("插件内容在确认后发生变化，请重新预览"),
            )
        }
        if (decision.requiresPublisherTrust) {
            if (!trustPublisher) {
                return@withContext Result.failure(
                    IllegalStateException("发行者尚未受信，请明确选择信任发行者后再安装"),
                )
            }
            val signature = loaded.manifest.signature
                ?: return@withContext Result.failure(IllegalStateException("插件缺少发行者签名"))
            trustStore.trust(signature.publisherId, signature.publicKey)
                .getOrElse { error -> return@withContext Result.failure(error) }
            // 信任根写入成功后，用同一份包重新计算决策；不能仅凭 UI 的 preview 状态放行。
            decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        }
        if (!decision.isInstallable) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "插件必须具有受本机信任根信任的有效签名"),
            )
        }
        commitInstall(
            loaded,
            installationConfirmed = true,
            signature = decision.signature,
        )
    }


    /**
     * 显式确认已安装的草稿插件。
     *
     * [trustPublisher] 只在用户明确确认发行者时传 true。确认前重新读取完整包、验证
     * manifest 策略、ECDSA 签名、本机信任根和内容摘要；未签名/未知发行者/篡改包一律
     * 不能变成可执行插件。
     */
    suspend fun confirmInstallation(
        id: String,
        trustPublisher: Boolean = false,
    ): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val plugin = findPlugin(id)
            ?: return@withContext Result.failure(IllegalStateException("插件未安装: $id"))
        if (plugin.contentSha256.isBlank()) {
            return@withContext Result.failure(IllegalStateException("插件缺少内容摘要，无法确认"))
        }
        val directory = File(pluginsDir, plugin.id)
        val loaded = loadInstalledPackage(directory)
            ?: return@withContext Result.failure(IllegalStateException("插件内容无法完整读取，无法确认"))
        var decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        if (!decision.allowed) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "插件安全审查未通过"),
            )
        }
        val actual = PluginSecurityGate.contentSha256(loaded)
        if (actual != plugin.contentSha256) {
            return@withContext Result.failure(IllegalStateException("插件内容摘要不匹配，请重新导入"))
        }
        if (decision.requiresPublisherTrust) {
            if (!trustPublisher) {
                return@withContext Result.failure(
                    IllegalStateException("发行者尚未受信，请明确选择信任发行者后再安装"),
                )
            }
            val signature = loaded.manifest.signature
                ?: return@withContext Result.failure(IllegalStateException("插件缺少发行者签名"))
            trustStore.trust(signature.publisherId, signature.publicKey)
                .getOrElse { error -> return@withContext Result.failure(error) }
            decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        }
        if (!decision.isInstallable) {
            return@withContext Result.failure(
                IllegalStateException(decision.reason ?: "插件必须具有受本机信任根信任的有效签名"),
            )
        }
        val confirmed = plugin.copy(
            installationConfirmed = true,
            // A revalidation call must not turn a deliberately disabled plugin back on.
            enabled = if (plugin.installationConfirmed) {
                plugin.enabled
            } else {
                // P2-29: 确认时尊重 manifest.enabled — 声明禁用的包确认后仍保持禁用,
                // 由用户手动启用,不再一律强制启用
                loaded.manifest.enabled
            },
            signatureStatus = decision.signature.status,
            publisherId = decision.signature.publisherId,
            publisherKeyFingerprint = decision.signature.fingerprint,
            // 确认即激活：同步记录当前 manifest 的 skill id，并清理旧格式/已移除的 skill。
            registeredSkillIds = collectRegisteredSkillIds(plugin, loaded.manifest),
        )
        try {
            registryMutex.withLock {
                cached = cached.map { if (it.id == id) confirmed else it }
                persistRegistryOrThrow()
            }
        } catch (error: Exception) {
            Logger.e(TAG, "插件确认写入失败: $id", error)
            return@withContext Result.failure(error)
        }
        // upsert 会按 confirmed.enabled 落库，避免“插件已确认但 skill 仍禁用”或
        // “插件禁用但确认调用把 skill 重新启用”的状态撕裂。
        syncSkills(plugin, confirmed)
        markRegistryChanged()
        Result.success(confirmed)
    }

    /**
     * 用宿主构建 ZIP，写出一份**未签名草稿**。
     *
     * 助手（大模型）只能走到这里：manifest 由宿主按 [PluginAuthoringRules] 生成，
     * 草稿入库固定 `installationConfirmed=false`、`enabled=false`，[loadVerifiedPlugin]
     * 对其始终返回 null，因此这份代码在用户点头之前不可能被执行。
     *
     * 校验先于写盘：id/名称/版本/代码大小/工具形状/函数是否存在/能力白名单任何一条
     * 不通过都直接返回中文原因，不在插件目录或注册表留下半成品。
     */
    suspend fun createAuthoredDraft(request: AuthoredPluginRequest): Result<InstalledPlugin> =
        withContext(Dispatchers.IO) {
            PluginAuthoringRules.validate(request)?.let { reason ->
                Logger.w(TAG, "助手插件草稿被拒: ${request.id} ($reason)")
                return@withContext Result.failure(IllegalArgumentException(reason))
            }
            val manifest = PluginAuthoringRules.manifestOf(request)
            val zipBytes = runCatching {
                PluginAuthoringRules.buildZip(manifest, request.code)
            }.getOrElse { error ->
                return@withContext Result.failure(
                    IllegalStateException("插件包构建失败: ${error.message}", error),
                )
            }
            val temp = File(context.cacheDir, "authored_draft_${System.nanoTime()}.muse-plugin")
            try {
                temp.writeBytes(zipBytes)
                val decision = reviewFromFile(temp)
                    .getOrElse { error -> return@withContext Result.failure(error) }
                if (!decision.allowed) {
                    return@withContext Result.failure(
                        IllegalStateException(decision.reason ?: "插件安全审查未通过"),
                    )
                }
                // 复用既有草稿落库路径：内容摘要与预览绑定，且不写信任根、不启用。
                installDraftFromFile(temp, decision.preview)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                Logger.e(TAG, "助手插件草稿写入失败: ${request.id}", error)
                Result.failure(error)
            } finally {
                runCatching { if (temp.exists()) temp.delete() }
            }
        }

    /**
     * 用本机作者密钥签名并启用一份已安装的未签名草稿。
     *
     * 调用方必须已经拿到用户的明确同意（UI 确认对话框展示插件名/id/版本/工具清单），
     * 因此这里会按需把本机作者公钥写入信任根 [PluginTrustStore.trust]；写入信任根本身
     * 不代表放行——签名包仍要重新经过 [PluginSecurityGate.review] 且
     * `isInstallable == true` 才会提交。
     *
     * 顺序是安全边界的一部分：先复核草稿身份与内容摘要，任何一步失败都在写盘之前返回，
     * 现有安装（含已确认的签名版本）保持不变。版本策略不绕过：同版本重装由
     * [PluginVersionPolicy] 放行，降级仍会被它拦下。
     */
    suspend fun signAndEnableDraft(id: String): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val plugin = findPlugin(id)
            ?: return@withContext Result.failure(IllegalStateException("插件未安装: $id"))
        if (plugin.installationConfirmed || plugin.signatureStatus != PluginSecurityGate.SignatureStatus.UNSIGNED) {
            return@withContext Result.failure(
                IllegalStateException("只有未签名的禁用草稿可以签名启用: $id"),
            )
        }
        if (plugin.contentSha256.isBlank()) {
            return@withContext Result.failure(IllegalStateException("插件缺少内容摘要，无法签名"))
        }
        val loaded = loadInstalledPackage(File(pluginsDir, plugin.id))
            ?: return@withContext Result.failure(IllegalStateException("插件内容无法完整读取，无法签名"))
        if (loaded.manifest.id != plugin.id) {
            return@withContext Result.failure(IllegalStateException("插件目录与注册表 id 不一致，拒绝签名"))
        }
        // 草稿可能在生成后被改动：内容摘要不一致说明这份代码不再是被审阅的那份。
        if (PluginSecurityGate.contentSha256(loaded) != plugin.contentSha256) {
            return@withContext Result.failure(
                IllegalStateException("插件内容在签名前已被修改，请重新生成草稿"),
            )
        }
        val publicKey = localAuthorKeyStore.publicKeyBase64()
            .getOrElse { error -> return@withContext Result.failure(error) }
        val envelope = PluginSignature(
            publisherId = localAuthorKeyStore.publisherId,
            publicKey = publicKey,
            signature = "",
            algorithm = PluginSecurityGate.SIGNATURE_ALGORITHM,
        )
        val packageForSigning = loaded.copy(manifest = loaded.manifest.copy(signature = envelope))
        val signatureBytes = localAuthorKeyStore.sign(PluginSecurityGate.signaturePayload(packageForSigning))
            .getOrElse { error -> return@withContext Result.failure(error) }
        val signedManifest = packageForSigning.manifest.copy(
            signature = envelope.copy(signature = Base64.getEncoder().encodeToString(signatureBytes)),
        )
        val zipBytes = runCatching {
            PluginAuthoringRules.buildZip(signedManifest, packageForSigning.entryCode, packageForSigning.extraFiles)
        }.getOrElse { error ->
            return@withContext Result.failure(IllegalStateException("签名包构建失败: ${error.message}", error))
        }
        val temp = File(context.cacheDir, "authored_signed_${System.nanoTime()}.muse-plugin")
        try {
            temp.writeBytes(zipBytes)
            var decision = reviewFromFile(temp).getOrElse { error -> return@withContext Result.failure(error) }
            if (!decision.allowed) {
                return@withContext Result.failure(
                    IllegalStateException(decision.reason ?: "签名后的插件安全审查未通过"),
                )
            }
            if (decision.signature.status == PluginSecurityGate.SignatureStatus.VALID_UNTRUSTED) {
                // 用户已在确认对话框明确同意信任本机作者密钥；同一个发行者 id 不允许换钥。
                trustStore.trust(localAuthorKeyStore.publisherId, publicKey)
                    .getOrElse { error -> return@withContext Result.failure(error) }
                decision = reviewFromFile(temp).getOrElse { error -> return@withContext Result.failure(error) }
            }
            if (!decision.isInstallable) {
                return@withContext Result.failure(
                    IllegalStateException(decision.reason ?: "本机作者签名未受信任，拒绝启用"),
                )
            }
            installConfirmedFromFile(temp, decision.preview, trustPublisher = true)
        } catch (cancellation: kotlinx.coroutines.CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            Logger.e(TAG, "助手插件草稿签名启用失败: $id", error)
            Result.failure(error)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    private suspend fun <T> withTempPluginFile(
        uri: Uri,
        suffix: String,
        operation: suspend (File) -> Result<T>,
    ): Result<T> = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "plugin_import_${System.nanoTime()}.$suffix")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> copyLimited(input, output, MAX_PLUGIN_PACKAGE_BYTES) }
            } ?: return@withContext Result.failure(IllegalStateException("无法打开所选文件"))
            operation(temp)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    private fun loadPackage(file: File): Result<PluginPackageLoader.LoadedPluginPackage> {
        val bytes = readPluginPackage(file).getOrElse { error -> return Result.failure(error) }
        return when (val loaded = PluginPackageLoader.loadFromZip(bytes)) {
            is PluginPackageLoader.Result.Err -> Result.failure(IllegalStateException(loaded.reason))
            is PluginPackageLoader.Result.Ok -> Result.success(loaded.package_)
        }
    }

    /**
     * P2-26: 按插件 id 串行化提交 — 并发安装同一插件(市场刷新 + 手动导入同时触发)时,
     * staging/目录切换/注册表互不交错,失败不再遗留半安装状态。
     */
    private suspend fun commitInstall(
        loaded: PluginPackageLoader.LoadedPluginPackage,
        installationConfirmed: Boolean,
        signature: PluginSecurityGate.SignatureVerification =
            PluginSecurityGate.SignatureVerification(
                status = PluginSecurityGate.SignatureStatus.UNSIGNED,
            ),
        /** 仅回滚入口传 true：用户主动回退到历史版本时放行降级。 */
        allowDowngrade: Boolean = false,
    ): Result<InstalledPlugin> {
        val lockId = loaded.manifest.id
        return installLocks.getOrPut(lockId) { Mutex() }.withLock {
            commitInstallLocked(loaded, installationConfirmed, signature, allowDowngrade)
        }
    }

    private suspend fun commitInstallLocked(
        loaded: PluginPackageLoader.LoadedPluginPackage,
        installationConfirmed: Boolean,
        signature: PluginSecurityGate.SignatureVerification =
            PluginSecurityGate.SignatureVerification(
                status = PluginSecurityGate.SignatureStatus.UNSIGNED,
            ),
        /** 仅回滚入口传 true：用户主动回退到历史版本时放行降级。 */
        allowDowngrade: Boolean = false,
    ): Result<InstalledPlugin> {
        val manifest = loaded.manifest
        val contentSha256 = PluginSecurityGate.contentSha256(loaded)
        val previous = cached.firstOrNull { it.id == manifest.id }
        // 版本策略：降级安装、发行者换人、未签名包覆盖已签名安装、minAppVersion 高于当前 App
        // 都在写盘之前拦下。放在这里是因为它是所有安装路径（草稿/导入/市场/回滚）的唯一收口。
        val versionDecision = PluginVersionPolicy.evaluate(
            candidate = PluginInstallCandidate(
                id = manifest.id,
                version = manifest.version,
                publisherId = signature.publisherId,
                publisherKeyFingerprint = signature.fingerprint,
                signed = signature.isCryptographicallyValid,
                minAppVersion = manifest.minAppVersion,
            ),
            installed = previous?.let { installed ->
                InstalledPluginIdentity(
                    version = installed.version,
                    publisherId = installed.publisherId,
                    publisherKeyFingerprint = installed.publisherKeyFingerprint,
                    signed = installed.signatureStatus == PluginSecurityGate.SignatureStatus.VALID_TRUSTED,
                )
            },
            appVersion = appVersionName,
            allowDowngrade = allowDowngrade,
            // 本机作者签名的插件允许被新草稿覆盖，助手才能迭代自己写的插件。
            localAuthorPublisherId = localAuthorKeyStore.publisherId,
        )
        if (versionDecision is PluginVersionDecision.Reject) {
            Logger.w(TAG, "插件安装被版本策略拒绝: ${manifest.id} (${versionDecision.reason})")
            return Result.failure(IllegalStateException(versionDecision.reason))
        }
        // P0: 安装前先检测 skill id 冲突（用户/内置 skill 占用，或 manifest 内工具名重复），
        // 拒绝而不是静默覆盖不属于本插件的既有 skill。
        val conflict = runCatching { findSkillConflict(manifest) }.getOrElse { error ->
            return Result.failure(
                IllegalStateException("插件 skill 冲突检测失败: ${error.message}", error),
            )
        }
        if (conflict != null) {
            Logger.w(TAG, "插件安装被拒绝: ${manifest.id} ($conflict)")
            return Result.failure(IllegalStateException(conflict))
        }
        val targetDir = File(pluginsDir, manifest.id)
        val stagingDir = File(pluginsDir, ".staging_${manifest.id}_${System.nanoTime()}")
        val previousCached = cached
        var backupDir: File? = null
        // 替换前先把当前生效版本留一份历史副本：升级后用户仍能回退到它。
        // 保留失败只记日志，绝不阻断安装（历史副本是便利能力，不是安装前置条件）。
        previous?.let { installed ->
            if (targetDir.isDirectory) {
                versionStore.retain(
                    pluginId = installed.id,
                    version = installed.version,
                    sourceDirectory = targetDir,
                    record = RetainedVersionRecord(
                        version = installed.version,
                        publisherId = installed.publisherId,
                        publisherKeyFingerprint = installed.publisherKeyFingerprint,
                        contentSha256 = installed.contentSha256,
                        retainedAtEpochMs = 0L,
                    ),
                ).onFailure { error ->
                    Logger.w(TAG, "保留历史版本失败(不影响安装): ${installed.id}@${installed.version}", error)
                }
            }
        }
        val installResult = runCatching {
            // 先完整写入同一文件系统下的 staging 目录，再切换 active 目录。
            // 旧版本保留到临时备份，切换失败时恢复，避免安装中断留下半安装插件。
            writePluginDirectory(stagingDir, manifest, loaded)
            backupDir = replacePluginDirectory(stagingDir, targetDir)
        }
        if (installResult.isFailure) {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            installResult.exceptionOrNull()?.let { error ->
                Logger.e(TAG, "插件安装提交失败: ${manifest.id}", error)
            }
            return Result.failure(
                installResult.exceptionOrNull() ?: IllegalStateException("插件安装提交失败"),
            )
        }

        val installed = InstalledPlugin(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            author = manifest.author,
            description = manifest.description,
            entry = manifest.entry,
            kind = manifest.kind,
            trust = manifest.trust,
            capabilities = manifest.capabilities,
            permissions = manifest.permissions,
            tools = manifest.tools,
            // 未确认的外部包永远禁用；manifest.enabled 只有在明确确认后才可生效。
            enabled = installationConfirmed && manifest.enabled,
            installationConfirmed = installationConfirmed,
            signatureStatus = signature.status,
            publisherId = signature.publisherId,
            publisherKeyFingerprint = signature.fingerprint,
            contentSha256 = contentSha256,
            installedAt = System.currentTimeMillis(),
            // 累积记录历次注册过的 skill id（含旧格式 id），卸载时据此删除全部属于本插件的
            // skill，而不只依赖当前 manifest。
            registeredSkillIds = collectRegisteredSkillIds(previous, manifest),
        )
        try {
            registryMutex.withLock {
                cached = cached.filterNot { it.id == installed.id } + installed
                try {
                    persistRegistryOrThrow()
                } catch (error: Exception) {
                    cached = previousCached
                    rollbackActivatedPlugin(targetDir, backupDir)
                    throw error
                }
            }
        } catch (error: Exception) {
            Logger.e(TAG, "插件注册表提交失败，已恢复旧版本: ${manifest.id}", error)
            return Result.failure(error)
        }
        backupDir?.deleteRecursively()
        // 保留刚装好的版本：回滚方向是可逆的（回退后还能再切回来）。
        versionStore.retain(
            pluginId = installed.id,
            version = installed.version,
            sourceDirectory = targetDir,
            record = RetainedVersionRecord(
                version = installed.version,
                publisherId = installed.publisherId,
                publisherKeyFingerprint = installed.publisherKeyFingerprint,
                contentSha256 = installed.contentSha256,
                retainedAtEpochMs = 0L,
            ),
        ).onFailure { error ->
            Logger.w(TAG, "保留已安装版本失败(不影响安装): ${installed.id}@${installed.version}", error)
        }
        // P0: 更新时删除新 manifest 已移除的旧 skill，再注册当前声明的 skill。
        syncSkills(previous, installed)
        markRegistryChanged()
        Logger.i(
            TAG,
            "插件已安装: ${installed.id} v${installed.version}, " +
                "confirmed=${installed.installationConfirmed} enabled=${installed.enabled}",
        )
        return Result.success(installed)
    }

    suspend fun uninstall(id: String) {
        withContext(Dispatchers.IO) {
            val plugin = findPlugin(id) ?: return@withContext
            // P2-26: 与安装同锁,避免安装/卸载交错
            installLocks.getOrPut(id) { Mutex() }.withLock {
                // P2-27: 先写注册表、后删目录 —
                //  原顺序「删目录→写注册表」在注册表写失败时出现"目录已删但注册表仍列出"的撕裂态。
                //  新顺序:注册表更新失败则整体不变(无撕裂);目录删除失败仅遗留孤儿文件(日志提示)。
                val previousEntry = cached
                registryMutex.withLock {
                    cached = cached.filterNot { it.id == id }
                    val persisted = runCatching { persistRegistryOrThrow() }
                    if (persisted.isFailure) {
                        cached = previousEntry
                        Logger.e(TAG, "插件注册表移除失败,卸载中止(状态未变): $id", persisted.exceptionOrNull())
                        return@withLock
                    }
                }
                val deleteResult = resultOf { File(pluginsDir, id).deleteRecursively() }
                    .onError { msg, t -> Logger.e(TAG, "插件目录删除失败: $id ($msg)", t) }
                if (deleteResult.isError || deleteResult.getOrNull() == false) {
                    // 注册表已移除,目录删除失败只遗留孤儿文件,不影响卸载语义
                    Logger.w(TAG, "插件目录删除失败但注册表已移除(遗留孤儿文件,可手动清理): $id")
                }
                // P0: 删除范围为注册表记录的全部 skill + 当前 manifest 推导的 skill（含旧格式），
                // 不再只依赖当前 manifest，避免历史版本残留。
                skillIdsOwnedBy(plugin).forEach { skillId -> deleteSkillIfOwned(id, skillId) }
                // 卸载即失去回滚目标：历史副本不保留（避免卸载后仍能恢复出用户以为已删除的代码）。
                versionStore.deleteAll(id)
                markRegistryChanged()
                Logger.i(TAG, "插件已卸载: $id")
            }
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val plugin = findPlugin(id) ?: return@withContext
            if (enabled && !plugin.installationConfirmed) {
                // 外部代码必须先经过明确的安装确认；保留旧 Unit API，但拒绝静默启用。
                Logger.w(TAG, "拒绝启用未经确认的外部插件: $id")
                return@withContext
            }
            registryMutex.withLock {
                cached = cached.map { if (it.id == id) it.copy(enabled = enabled) else it }
                persistRegistry()
            }
            skillIdsOwnedBy(plugin).forEach { skillId ->
                runCatching { skillRepository.setEnabled(skillId, enabled) }
            }
            markRegistryChanged()
        }
    }

    fun list(): List<InstalledPlugin> = cached

    fun findPlugin(id: String): InstalledPlugin? = cached.firstOrNull { it.id == id }

    /** P0-9: 当前信任的发行者列表(与安装校验共用同一 trust store 实例)。 */
    fun trustedPublishers(): List<PluginTrustStore.TrustedPublisher> = trustStore.list()

    /**
     * P0-9: 撤销一个发行者的信任根。
     *
     * 撤销后该发行者签名的插件不再可通过校验(下次执行/更新前重新验证),并同步禁用
     * 该发行者所有已安装且处于启用状态的插件,避免"插件执行正常但信任已回收"的错觉。
     *
     * @return 被撤销的发行者 id;发行者不存在返回 null
     */
    suspend fun revokePublisher(publisherId: String): String? {
        val revoked = trustStore.revoke(publisherId)
        if (!revoked) return null
        cached.filter { it.publisherId == publisherId && it.enabled }.forEach { plugin ->
            setEnabled(plugin.id, false)
            Logger.w(TAG, "发行者 $publisherId 信任已撤销,自动禁用插件 ${plugin.id}")
        }
        return publisherId
    }

    /** 注册表变更信号:安装/卸载/确认/启停成功后自增,驱动皮肤列表重新读取。 */
    private val revision = MutableStateFlow(0)
    override val revisionFlow: StateFlow<Int> = revision.asStateFlow()

    /** 已保留的历史版本（含当前生效版本），新版本优先。 */
    fun listRetainedVersions(id: String): List<RetainedPluginVersion> = versionStore.list(id)

    /**
     * 回滚到已保留的历史版本。
     *
     * 回滚不是「信任旧文件」：历史副本会重新走一遍包读取、清单策略、发行者信任与签名校验，
     * 并比对保留时记录的内容摘要，任何一步不通过都不会写盘。校验通过后复用安装提交路径
     * （显式允许降级），因此技能注册、活跃目录切换与失败恢复都与正常安装完全一致。
     */
    suspend fun rollbackTo(id: String, version: String): Result<InstalledPlugin> =
        withContext(Dispatchers.IO) {
            val retained = versionStore.find(id, version)
                ?: return@withContext Result.failure(
                    IllegalStateException("没有可回滚的版本: $id@$version"),
                )
            val loaded = loadInstalledPackage(retained.directory)
                ?: return@withContext Result.failure(IllegalStateException("历史版本内容不可读"))
            if (loaded.manifest.id != id) {
                return@withContext Result.failure(IllegalStateException("历史版本插件 id 与目标不一致"))
            }
            if (PluginSecurityGate.contentSha256(loaded) != retained.record.contentSha256) {
                return@withContext Result.failure(IllegalStateException("历史版本内容已被改动，拒绝回滚"))
            }
            val decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
            if (!decision.isInstallable) {
                return@withContext Result.failure(
                    IllegalStateException(decision.reason ?: "历史版本签名校验失败，拒绝回滚"),
                )
            }
            val signature = decision.signature
            if (signature.publisherId != retained.record.publisherId ||
                !signature.fingerprint.equals(retained.record.publisherKeyFingerprint, ignoreCase = true)
            ) {
                return@withContext Result.failure(IllegalStateException("历史版本发行者与保留记录不一致"))
            }
            commitInstall(
                loaded,
                installationConfirmed = true,
                signature = signature,
                allowDowngrade = true,
            )
        }

    /**
     * 已安装插件提供的声明式气泡皮肤。
     *
     * 与 [loadVerifiedPlugin] 同级的安全路径:每次都重新从插件目录重建包、复核清单策略与
     * 发行者信任、比对内容摘要,并且只接受 `kind == "ui-skin"` 的包;皮肤本身还要通过
     * [BubbleSkinValidator] 才算合法。任一环节失败都不会返回可用条目,消费侧因此回退内置
     * default;皮肤始终只是声明式数据,插件代码不可能进入 Compose。
     *
     * @param includeDisabled true 时同时返回已安装但当前不可用(未确认/已禁用/信任失效)的皮肤,
     * 其 [InstalledSkin.enabled] 为 false;渲染路径必须使用默认值,只消费可用皮肤。
     */
    override fun listInstalledSkins(includeDisabled: Boolean): List<InstalledSkin> =
        cached.asSequence()
            .filter { it.kind == PluginSecurityGate.UI_SKIN_KIND }
            .mapNotNull { plugin -> skinEntry(plugin, includeDisabled) }
            // 皮肤 id 是选择与回退的唯一键:同一 id 只保留一个来源,避免选择语义歧义。
            .distinctBy { it.skin.id }
            .toList()

    /**
     * 读取单个插件的皮肤条目。
     *
     * 读取只走 [loadInstalledPackage] 的路径约束(清单/资源都在插件目录内,拒绝越界与
     * 不支持的文件类型);[includeDisabled] 只影响可用性标记,不影响格式与校验严格度。
     */
    private fun skinEntry(plugin: InstalledPlugin, includeDisabled: Boolean): InstalledSkin? {
        val loaded = loadInstalledPackage(File(pluginsDir, plugin.id)) ?: run {
            Logger.w(TAG, "插件皮肤读取失败,无法重建插件包: ${plugin.id}")
            return null
        }
        if (loaded.manifest.id != plugin.id) {
            Logger.w(TAG, "插件目录与注册表 id 不一致,拒绝皮肤: ${plugin.id}")
            return null
        }
        val skin = PluginPackageLoader.extractUiSkin(loaded) ?: return null
        if (!BubbleSkinValidator.isValid(skin)) {
            Logger.w(TAG, "插件皮肤未通过校验,已忽略: ${plugin.id}/${skin.id}")
            return null
        }
        val usable = plugin.installationConfirmed && plugin.enabled && verifyInstalledPackage(plugin, loaded)
        if (!usable && !includeDisabled) return null
        return InstalledSkin(
            pluginId = plugin.id,
            pluginName = plugin.name,
            version = plugin.version,
            enabled = usable,
            skin = skin,
        )
    }

    /**
     * 复核已安装插件的信任链与内容摘要,与 [loadVerifiedPlugin] 使用同一套判定。
     *
     * 不在这里检查 [InstalledPlugin.enabled]/[InstalledPlugin.installationConfirmed],
     * 由调用方区分「不可用但仍可展示」与「拒绝加载」。
     */
    private fun verifyInstalledPackage(
        plugin: InstalledPlugin,
        loaded: PluginPackageLoader.LoadedPluginPackage,
    ): Boolean = runCatching {
        val decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
        check(decision.isInstallable) {
            decision.reason ?: "插件必须具有受本机信任根信任的有效签名"
        }
        check(PluginSecurityGate.contentSha256(loaded) == plugin.contentSha256) {
            "插件内容摘要不匹配"
        }
    }.onFailure { error ->
        Logger.w(TAG, "插件皮肤包复核失败,已标记为不可用: ${plugin.id}", error)
    }.isSuccess

    private fun markRegistryChanged() {
        revision.update { it + 1 }
    }

    /**
     * 兼容旧调用方的薄包装：等价于 [loadVerifiedPlugin] 的入口代码。
     *
     * 新的执行路径应直接使用 [loadVerifiedPlugin]/[loadVerifiedFunction]，以便同时拿到
     * 重新验证后的 capabilities 与工具声明。
     */
    fun loadEntryCode(id: String): String? = loadVerifiedPlugin(id)?.entryCode

    /**
     * P0: 重新验证插件并返回可执行视图。
     *
     * 每次调用都重新读取磁盘上的完整包、复核安全门（签名/信任根/清单策略）和内容摘要；
     * 返回的 [VerifiedPlugin.capabilities] 与 [VerifiedPlugin.tools] 来自该次复核的
     * manifest，而不是 [InstalledPlugin.capabilities] 等注册表缓存字段。
     */
    fun loadVerifiedPlugin(id: String): VerifiedPlugin? {
        val plugin = findPlugin(id) ?: return null
        if (!plugin.installationConfirmed || !plugin.enabled) {
            Logger.w(TAG, "拒绝加载未确认或已禁用的外部插件: $id")
            return null
        }
        val directory = File(pluginsDir, plugin.id)
        return runCatching {
            val loaded = loadInstalledPackage(directory)
                ?: error("插件内容无法完整读取")
            val decision = PluginSecurityGate.review(loaded, trustedPublisherKeys())
            check(decision.isInstallable) {
                decision.reason ?: "插件必须具有受本机信任根信任的有效签名"
            }
            val actual = PluginSecurityGate.contentSha256(loaded)
            check(actual == plugin.contentSha256) { "插件内容摘要不匹配" }
            VerifiedPlugin(
                id = plugin.id,
                manifest = loaded.manifest,
                capabilities = loaded.manifest.capabilities,
                tools = loaded.manifest.tools,
                entryCode = loaded.entryCode,
            )
        }.onFailure { error ->
            Logger.e(TAG, "插件签名或完整性校验失败，拒绝加载: ${plugin.id}", error)
        }.getOrNull()
    }

    /**
     * P0: 执行前验证被调用的 [functionName] 仍存在于当前磁盘 manifest。
     *
     * 旧 skill 记录可能在插件更新后指向已被移除/改名的函数，必须在进入 JS 沙盒前拒绝。
     */
    fun loadVerifiedFunction(id: String, functionName: String): VerifiedPlugin? {
        val verified = loadVerifiedPlugin(id) ?: return null
        if (verified.tools.none { it.functionName == functionName }) {
            Logger.w(TAG, "拒绝执行 manifest 未声明的函数: $id/$functionName")
            return null
        }
        return verified
    }

    /** 从已安装目录重建完整包，仅读取 loader 支持的安全相对路径。 */
    private fun loadInstalledPackage(directory: File): PluginPackageLoader.LoadedPluginPackage? = runCatching {
        require(directory.isDirectory) { "插件目录不存在" }
        val canonicalRoot = directory.canonicalFile
        val manifestFile = File(canonicalRoot, "manifest.json").canonicalFile
        require(manifestFile.parentFile == canonicalRoot) { "插件清单路径非法" }
        val manifest = AppJson.decodeFromString<PluginManifest>(manifestFile.readText())
        val entryFile = File(canonicalRoot, manifest.entry).canonicalFile
        require(isWithin(canonicalRoot, entryFile)) { "插件入口路径越界" }
        // ui-skin 皮肤包没有 JS 入口(包内不得包含任何 .js,只分发 skin.json),
        // 入口内容与 zip 载入时一致固定为空串;其它类型缺少入口文件仍是错误,不能放行。
        val entryCode = if (entryFile.isFile) {
            entryFile.readText()
        } else {
            require(manifest.kind == PluginSecurityGate.UI_SKIN_KIND) {
                "插件缺少入口文件: ${manifest.entry}"
            }
            ""
        }
        val extraFiles = linkedMapOf<String, String>()
        directory.walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val canonicalFile = file.canonicalFile
                require(isWithin(canonicalRoot, canonicalFile)) { "插件文件路径越界" }
                if (canonicalFile == manifestFile || canonicalFile == entryFile) return@forEach
                val relative = canonicalFile.relativeTo(canonicalRoot).invariantSeparatorsPath
                require(relative.isNotBlank() && !relative.contains("..") && !relative.contains('\\')) {
                    "插件额外文件路径非法"
                }
                require(relative.endsWith(".js") || relative.endsWith(".json") || relative.endsWith(".md")) {
                    "插件包含不支持的文件类型"
                }
                extraFiles[relative] = canonicalFile.readText()
            }
        PluginPackageLoader.LoadedPluginPackage(
            manifest = manifest,
            entryCode = entryCode,
            extraFiles = extraFiles,
        )
    }.getOrNull()

    private fun isWithin(root: File, file: File): Boolean =
        file == root || file.path.startsWith(root.path + File.separator)

    private suspend fun registerSkills(plugin: InstalledPlugin) {
        plugin.tools.forEach { tool ->
            val entity = SkillEntity(
                id = skillId(plugin.id, tool.name),
                name = "${plugin.name} · ${tool.name}",
                description = tool.description,
                parametersJson = tool.parametersJson.ifBlank { "{}" },
                requiredJson = tool.requiredJson.ifBlank { "[]" },
                implementationKotlin = "plugin:${plugin.id}:${tool.functionName}",
                enabled = plugin.enabled,
                category = "plugin",
            )
            runCatching { skillRepository.upsert(entity) }
                .onFailure { e -> Logger.w(TAG, "插件工具注册失败: ${entity.id}", e) }
        }
    }

    /**
     * P0 安装/更新 skill 对账：
     * 1. 删除旧版本注册过、但新 manifest 已移除的 skill（含长度前缀编码前的旧格式 id）；
     * 2. 重新注册当前 manifest 声明的 skill。
     */
    private suspend fun syncSkills(previous: InstalledPlugin?, installed: InstalledPlugin) {
        val currentIds = installed.tools.map { skillId(installed.id, it.name) }.toSet()
        (skillIdsOwnedBy(previous) - currentIds).forEach { staleId ->
            deleteSkillIfOwned(installed.id, staleId)
        }
        registerSkills(installed)
    }

    /**
     * 推导某个插件可能拥有过的全部 skill id：注册表记录 ∪ 当前/历史 manifest 工具
     * （含旧格式 id）。卸载与状态切换据此覆盖历史版本残留。
     */
    private fun skillIdsOwnedBy(plugin: InstalledPlugin?): Set<String> {
        if (plugin == null) return emptySet()
        return buildSet {
            addAll(plugin.registeredSkillIds)
            plugin.tools.forEach { tool ->
                add(skillId(plugin.id, tool.name))
                add(legacySkillId(plugin.id, tool.name))
            }
        }
    }

    /**
     * 只删除确属该插件的 skill：实现路由必须指向 `plugin:<pluginId>:`。
     *
     * 长度前缀编码已消除跨插件 id 碰撞，但用户/内置 skill 仍可能恰好占用同一 id；
     * 所有权校验保证卸载不会误删非本插件数据。
     */
    private suspend fun deleteSkillIfOwned(pluginId: String, skillId: String) {
        runCatching {
            val existing = skillRepository.getById(skillId) ?: return@runCatching
            if (!existing.implementationKotlin.startsWith("plugin:$pluginId:")) {
                Logger.w(TAG, "跳过非本插件 skill 清理: $skillId")
                return@runCatching
            }
            skillRepository.delete(skillId)
        }.onFailure { error -> Logger.w(TAG, "插件 skill 清理失败: $skillId", error) }
    }

    /**
     * 计算本次安装后注册表应记录的 skill id 集合（累积历史，供卸载兜底清理）。
     */
    private fun collectRegisteredSkillIds(
        previous: InstalledPlugin?,
        manifest: PluginManifest,
    ): List<String> = buildList {
        if (previous != null) {
            addAll(previous.registeredSkillIds)
            previous.tools.forEach { tool ->
                add(skillId(previous.id, tool.name))
                add(legacySkillId(previous.id, tool.name))
            }
        }
        manifest.tools.forEach { add(skillId(manifest.id, it.name)) }
    }.distinct()

    /**
     * 安装前检测 skill id 冲突。
     *
     * 返回拒绝原因；null 表示无冲突。包含两类：
     * 1. 同一 manifest 内工具名重复（会映射到同一 skill id）；
     * 2. 既有 skill 占用了同一 id 且不属于本插件（用户/内置 skill 或损坏数据）。
     */
    private suspend fun findSkillConflict(manifest: PluginManifest): String? {
        val ids = manifest.tools.map { skillId(manifest.id, it.name) }
        if (ids.size != ids.toSet().size) {
            return "插件工具名重复，无法映射唯一 skill id: ${manifest.id}"
        }
        ids.forEach { id ->
            val existing = skillRepository.getById(id) ?: return@forEach
            if (!existing.implementationKotlin.startsWith("plugin:${manifest.id}:")) {
                return "插件 skill id 与既有 skill 冲突: $id"
            }
        }
        return null
    }

    private fun writePluginDirectory(
        directory: File,
        manifest: PluginManifest,
        pluginPackage: PluginPackageLoader.LoadedPluginPackage,
    ) {
        check(directory.mkdirs() || directory.isDirectory) { "无法创建插件 staging 目录" }
        File(directory, "manifest.json").writeText(
            AppJson.encodeToString(PluginManifest.serializer(), manifest),
        )
        val canonicalRoot = directory.canonicalFile
        val entryFile = File(directory, manifest.entry).canonicalFile
        require(isWithin(canonicalRoot, entryFile)) { "插件入口路径越界" }
        entryFile.parentFile?.mkdirs()
        entryFile.writeText(pluginPackage.entryCode)
        pluginPackage.extraFiles.forEach { (relative, content) ->
            val out = File(directory, relative).canonicalFile
            require(isWithin(canonicalRoot, out)) { "插件额外文件路径越界" }
            out.parentFile?.mkdirs()
            out.writeText(content)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun replacePluginDirectory(stagingDir: File, targetDir: File): File? {
        val backupDir = File(pluginsDir, ".backup_${targetDir.name}_${System.nanoTime()}")
        var oldMoved = false
        try {
            if (targetDir.exists()) {
                check(targetDir.renameTo(backupDir)) { "无法保留旧插件版本" }
                oldMoved = true
            }
            check(stagingDir.renameTo(targetDir)) { "无法激活插件 staging 目录" }
            // 保留旧目录直到注册表原子提交成功，由调用方完成最终清理。
            return backupDir.takeIf { oldMoved }
        } catch (error: Exception) {
            if (targetDir.exists()) targetDir.deleteRecursively()
            if (oldMoved && backupDir.exists()) backupDir.renameTo(targetDir)
            throw error
        } finally {
            if (stagingDir.exists()) stagingDir.deleteRecursively()
            if (backupDir.exists() && targetDir.exists() && !oldMoved) backupDir.deleteRecursively()
        }
    }

    private fun rollbackActivatedPlugin(targetDir: File, backupDir: File?) {
        if (targetDir.exists()) targetDir.deleteRecursively()
        if (backupDir != null && backupDir.exists()) {
            check(backupDir.renameTo(targetDir)) { "无法恢复旧插件版本" }
        }
    }

    private fun persistRegistryOrThrow() {
        val json = AppJson.encodeToString(PluginRegistry.serializer(), PluginRegistry(cached))
        // 注册表属于可恢复状态：写入、flush、fsync、同目录原子替换，避免进程被杀留下半个 JSON。
        AtomicFileStore.writeText(registryFile, json)
        // 替换后回读验证，失败时让调用方执行目录/内存回滚。
        AppJson.decodeFromString<PluginRegistry>(registryFile.readText())
    }

    private fun persistRegistry() {
        runCatching { persistRegistryOrThrow() }
            .onFailure { e -> Logger.w(TAG, "插件注册表写入失败", e) }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadRegistry(): List<InstalledPlugin> {
        if (!registryFile.exists()) return emptyList()
        return runCatching {
            AppJson.decodeFromString<PluginRegistry>(registryFile.readText()).plugins
        }.getOrElse { e ->
            // 隔离损坏注册表，避免每次启动重复解析同一坏文件；插件目录仍保留供诊断/人工恢复。
            AtomicFileStore.quarantine(registryFile, "plugin_registry_parse")
            Logger.w(TAG, "插件注册表解析失败,已隔离损坏文件", e)
            emptyList()
        }
    }

    private fun readPluginPackage(file: File): Result<ByteArray> = runCatching {
        require(file.isFile) { "插件包不是普通文件" }
        require(file.length() <= MAX_PLUGIN_PACKAGE_BYTES) {
            "插件包压缩后大小超过限制(${MAX_PLUGIN_PACKAGE_BYTES / 1024 / 1024}MB)"
        }
        file.readBytes()
    }

    private fun copyLimited(input: InputStream, output: java.io.OutputStream, maxBytes: Long) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            copied += read
            require(copied <= maxBytes) {
                "插件包压缩后大小超过限制(${maxBytes / 1024 / 1024}MB)"
            }
            output.write(buffer, 0, read)
        }
    }

    companion object {
        private const val TAG = "PluginManager"
        /** 压缩包原始大小上限；与 loader 的解压后上限配合，防止 readBytes/copyTo 无界增长。 */
        internal const val MAX_PLUGIN_PACKAGE_BYTES = 20L * 1024 * 1024

        /**
         * P0: 生成插件 skill id。
         *
         * 使用长度前缀而不是裸下划线拼接：`pluginId` 与 `toolName` 都允许包含下划线，
         * 旧的 `plugin_<id>_<tool>` 会让不同 (pluginId, toolName) 组合碰撞（例如
         * `a_b`+`c` 与 `a`+`b_c`）。长度前缀让 id 可无歧义解码，任何组合都不会碰撞。
         */
        internal fun skillId(pluginId: String, toolName: String): String =
            "plugin_${pluginId.length}_${pluginId}_$toolName"

        /**
         * 长度前缀编码引入前的旧格式 id。
         *
         * 仅用于迁移清理历史安装留下的 skill，不再用于注册新 skill。
         */
        internal fun legacySkillId(pluginId: String, toolName: String): String =
            "plugin_${pluginId}_$toolName"

        /**
         * v2.0: 解析插件 skill id(`plugin_<len>_<pluginId>_<toolName>`)。
         *
         * 与 [skillId] 的编码互为逆操作;非插件工具(不含前缀/长度段非法)返回 null。
         */
        internal fun parsePluginToolId(id: String): Pair<String, String>? {
            if (!id.startsWith("plugin_")) return null
            val rest = id.removePrefix("plugin_")
            val sep = rest.indexOf('_')
            if (sep <= 0) return null
            val len = rest.substring(0, sep).toIntOrNull() ?: return null
            val after = rest.substring(sep + 1)
            if (len <= 0 || after.length <= len) return null
            val pluginId = after.substring(0, len)
            val toolName = after.substring(len).removePrefix("_")
            if (pluginId.isBlank() || toolName.isBlank()) return null
            return pluginId to toolName
        }
    }

    /**
     * v1.0.92: 读取插件声明的 UI 面板(manifest.uiPanel 指向的 HTML 文件)。
     *
     * 路径经 canonical 越界校验;未确认安装/未启用/无声明/文件缺失均返回 null。
     */
    fun loadPluginPanel(pluginId: String): String? {
        val plugin = findPlugin(pluginId) ?: return null
        if (!plugin.installationConfirmed || !plugin.enabled) return null
        return runCatching {
            val root = File(pluginsDir, pluginId).canonicalFile
            val manifest = AppJson.decodeFromString<PluginManifest>(
                File(root, "manifest.json").readText(),
            )
            val rel = manifest.uiPanel?.trim().orEmpty()
            if (rel.isBlank()) return null
            val panelFile = File(root, rel).canonicalFile
            if (!isWithin(root, panelFile) || !panelFile.isFile) return null
            panelFile.readText()
        }.onFailure { error ->
            Logger.w(TAG, "读取插件面板失败: ${pluginId} — ${error.message}")
        }.getOrNull()
    }

    /**
     * v2.0: 读取插件工具卡 HTML(工具名 → manifest.toolCards 声明)。
     *
     * [toolNameOrSkillId] 支持两种形式:插件 skill id(`plugin_<len>_<id>_<tool>`,
     * 见 [skillId])或裸工具名(遍历全部已启用插件匹配)。
     * 路径经 canonical 越界校验;未确认安装/未启用/无声明/文件缺失均返回 null。
     */
    fun loadToolCardHtml(toolNameOrSkillId: String): String? {
        val key = toolNameOrSkillId.trim()
        if (key.isBlank()) return null
        val parsed = parsePluginToolId(key)
        val toolName = parsed?.second ?: key
        val candidates = if (parsed != null) {
            listOfNotNull(findPlugin(parsed.first))
        } else {
            cached.filter { it.installationConfirmed && it.enabled }
        }
        for (plugin in candidates) {
            if (!plugin.installationConfirmed || !plugin.enabled) continue
            val html = runCatching {
                val root = File(pluginsDir, plugin.id).canonicalFile
                val manifest = AppJson.decodeFromString<PluginManifest>(
                    File(root, "manifest.json").readText(),
                )
                val rel = manifest.toolCards[toolName]?.trim().orEmpty()
                if (rel.isBlank()) return@runCatching null
                val cardFile = File(root, rel).canonicalFile
                if (!isWithin(root, cardFile) || !cardFile.isFile) return@runCatching null
                cardFile.readText()
            }.onFailure { error ->
                Logger.w(TAG, "读取插件工具卡失败: ${plugin.id}/$toolName — ${error.message}")
            }.getOrNull()
            if (html != null) return html
        }
        return null
    }

    // ── 插件配置 API ──────────────────────────────────────────────────

    /**
     * 读取插件配置项的值。
     *
     * @param pluginId 插件 id
     * @param key      配置项 key
     * @param defaultVal 默认值（来自 manifest 声明）
     * @return 用户配置值或默认值
     */
    fun getPluginConfig(pluginId: String, key: String, defaultVal: JsonElement? = null): JsonElement? {
        val pluginConfigs = configStore.configs[pluginId] ?: return defaultVal
        return pluginConfigs[key] ?: defaultVal
    }

    /**
     * 保存插件配置项的值。
     *
     * @param pluginId 插件 id
     * @param key      配置项 key
     * @param value    用户配置值
     */
    suspend fun setPluginConfig(pluginId: String, key: String, value: JsonElement?) = withContext(Dispatchers.IO) {
        val current = configStore.configs.toMutableMap()
        val pluginConfigs = current[pluginId]?.toMutableMap() ?: mutableMapOf()
        if (value != null) {
            pluginConfigs[key] = value
        } else {
            pluginConfigs.remove(key)
        }
        current[pluginId] = pluginConfigs
        configStore = PluginConfigStore(current)
        PluginConfigStore.toFile(configStoreFile, configStore)
    }

    /**
     * 获取插件的所有配置项（含默认值）。
     *
     * 用于 UI 渲染配置表单：遍历 manifest 中的 configuration 列表，
     * 对每个 key 调用 [getPluginConfig] 得到当前值。
     */
    fun getPluginConfigsWithDefaults(manifest: PluginManifest): List<Pair<ConfigItem, JsonElement?>> {
        val configList = manifest.contributes?.configuration ?: return emptyList()
        return configList.map { item ->
            item to getPluginConfig(manifest.id, item.key, item.defaultVal)
        }
    }

    /**
     * 获取插件的完整配置（含 schema 和当前值），供 JS 桥接使用。
     *
     * 返回 JSON 字符串，格式：{"key": value, ...}
     */
    fun getPluginConfigJson(manifest: PluginManifest): String {
        val configList = manifest.contributes?.configuration ?: return "{}"
        val obj = buildJsonObject {
            for (item in configList) {
                val value = getPluginConfig(manifest.id, item.key, item.defaultVal)
                if (value != null) put(item.key, value)
            }
        }
        return AppJson.encodeToString(JsonObject.serializer(), obj)
    }
}
