package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import kotlinx.serialization.Serializable
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 外部插件安装安全门。
 *
 * 该类只处理可测试的纯逻辑：复核外部插件必须使用 sandboxed 信任级别、能力白名单，
 * 验证发行者签名，并生成不包含源码内容的安装预览。预览摘要会在确认时再次计算，
 * 用于阻止用户确认后包内容被替换。
 *
 * 签名格式是 P-256 ECDSA (`SHA256withECDSA`)。签名覆盖规范化的 manifest（仅把签名值
 * 置空，仍覆盖发行者 ID、公钥和算法）以及按路径排序的全部可加载文件。清单中的公钥
 * 只是身份声明；本机信任根由 [PluginTrustStore] 提供，未知发行者必须经过明确的用户信任决定。
 */
object PluginSecurityGate {

    /** 外部插件允许声明的最小能力集合。 */
    val allowedCapabilities: Set<String> = setOf(
        "resource.read",
        "ui",
        "ui.mood",
        // Phase 4: 声明式气泡皮肤包(只分发 JSON,不执行 JS)。
        "ui.skin",
    )

    /** 声明式 UI 皮肤插件类型;该类插件允许无 tools、且不得携带可执行 JS。 */
    const val UI_SKIN_KIND = "ui-skin"

    /** ui-skin 插件必须声明的能力,用于限定其只能分发皮肤资源。 */
    const val UI_SKIN_CAPABILITY = "ui.skin"

    /** 外部插件的唯一允许信任级别。 */
    const val EXTERNAL_TRUST = "sandboxed"

    /** 发行者公钥算法；公钥编码为 X.509 SubjectPublicKeyInfo。 */
    const val KEY_ALGORITHM = "EC"

    /** 发行者签名算法；签名编码为 DER。 */
    const val SIGNATURE_ALGORITHM = "SHA256withECDSA"

    /** 签名验证状态。 */
    @Serializable
    enum class SignatureStatus {
        /** 签名有效且发行者 ID 与本机信任根中的公钥绑定。 */
        VALID_TRUSTED,
        /** 签名有效，但发行者公钥尚未由用户加入本机信任根。 */
        VALID_UNTRUSTED,
        /** 旧包或恶意包没有发行者签名。 */
        UNSIGNED,
        /** 签名、清单或包内容不匹配。 */
        INVALID,
        /** 使用了不支持的签名算法或公钥格式。 */
        UNSUPPORTED,
    }

    /** 发行者签名验证结果；仅包含公钥指纹，不保留私钥或源码。 */
    data class SignatureVerification(
        val status: SignatureStatus,
        val publisherId: String = "",
        val fingerprint: String = "",
        val reason: String? = null,
    ) {
        /** 签名在密码学上有效，可供用户作出是否信任发行者的决定。 */
        val isCryptographicallyValid: Boolean
            get() = status == SignatureStatus.VALID_TRUSTED || status == SignatureStatus.VALID_UNTRUSTED
    }

    /** 安装确认前展示的单个工具摘要。 */
    data class ToolPreview(
        val name: String,
        val functionName: String,
        val description: String,
    )

    /**
     * 外部插件安装预览。
     *
     * 预览只保存元数据和摘要，不保存可执行源码；[contentSha256] 是确认时的绑定值。
     */
    data class InstallPreview(
        val id: String,
        val name: String,
        val version: String,
        val author: String,
        val description: String,
        val entry: String,
        val trust: String,
        val capabilities: List<String>,
        val permissions: List<String>,
        val tools: List<ToolPreview>,
        val entrySha256: String,
        val contentSha256: String,
        val entryBytes: Int,
        val extraFileCount: Int,
        val totalCodeBytes: Int,
        val signatureStatus: SignatureStatus,
        val publisherId: String,
        val publisherKeyFingerprint: String,
        val requiresPublisherTrust: Boolean,
    )

    /**
     * 安全审查结果。
     *
     * [allowed] 表示包通过结构、能力和密码学签名检查，可以进入安装确认预览；
     * [requiresPublisherTrust] 表示签名有效但发行者尚未受信，确认按钮必须执行一次
     * 明确的“信任发行者并安装”，不能静默加入信任根。真正可安装执行的条件由 [isInstallable]
     * 表示。未签名、篡改或不支持算法的包不会进入确认流程。
     */
    data class Decision(
        val allowed: Boolean,
        val requiresConfirmation: Boolean,
        val reason: String?,
        val preview: InstallPreview,
        val signature: SignatureVerification,
        val requiresPublisherTrust: Boolean,
    ) {
        /** 只有签名有效且发行者已在本机信任根中绑定时，才可提交为可执行插件。 */
        val isInstallable: Boolean
            get() = allowed && signature.status == SignatureStatus.VALID_TRUSTED && !requiresPublisherTrust
    }

    /**
     * 审查已通过 ZIP 结构校验的插件包，并生成安装预览。
     *
     * [trustedPublisherKeys] 只应来自 Android 私有信任根，键为发行者 ID，值为 X.509
     * 公钥的标准 Base64。未知但签名有效的发行者仍可展示预览，但必须由用户显式作出信任决定。
     */
    fun review(
        pluginPackage: PluginPackageLoader.LoadedPluginPackage,
        trustedPublisherKeys: Map<String, String> = emptyMap(),
    ): Decision {
        val signature = verifySignature(pluginPackage, trustedPublisherKeys)
        val preview = summarize(pluginPackage, signature)
        val policyReason = rejectionReason(pluginPackage.manifest)
        // 无签名包可以进入“隔离草稿/预览”流程，便于 AI 生成插件后先审阅；但
        // isInstallable 会保持 false，只有受本机信任根信任的有效签名才能执行。
        // 篡改签名、错误格式和不支持算法仍在预览阶段 fail-closed。
        val signatureReason = when {
            signature.status == SignatureStatus.UNSIGNED -> null
            signature.isCryptographicallyValid -> null
            else -> signature.reason
        }
        val reason = policyReason ?: signatureReason
        // 未知发行者的签名在密码学上有效，可以展示给用户作出信任决定；
        // 无签名仅能作为隔离草稿，不能直接确认启用。
        val allowed = reason == null
        val requiresPublisherTrust = allowed && signature.status == SignatureStatus.VALID_UNTRUSTED
        return Decision(
            allowed = allowed,
            // 任何可预览的外部代码都必须经过一次明确的用户确认；未知发行者还要明确加入信任根。
            requiresConfirmation = allowed,
            reason = reason,
            preview = preview.copy(requiresPublisherTrust = requiresPublisherTrust),
            signature = signature,
            requiresPublisherTrust = requiresPublisherTrust,
        )
    }

    /**
     * 验证发行者签名和本机信任绑定。
     *
     * 验证只使用内存中的 [pluginPackage]，调用方应在预览和确认阶段分别重新加载包。
     */
    fun verifySignature(
        pluginPackage: PluginPackageLoader.LoadedPluginPackage,
        trustedPublisherKeys: Map<String, String> = emptyMap(),
    ): SignatureVerification {
        val envelope = pluginPackage.manifest.signature
            ?: return SignatureVerification(
                status = SignatureStatus.UNSIGNED,
                reason = "外部插件缺少发行者签名",
            )
        val publisherId = envelope.publisherId
        if (publisherId != publisherId.trim() || !isValidPublisherId(publisherId)) {
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId.trim(),
                reason = "发行者 ID 非法",
            )
        }
        if (envelope.algorithm != SIGNATURE_ALGORITHM) {
            return SignatureVerification(
                status = SignatureStatus.UNSUPPORTED,
                publisherId = publisherId,
                reason = "不支持的发行者签名算法",
            )
        }

        val publicKeyBytes = publicKeyBytes(envelope.publicKey).getOrElse { error ->
            return SignatureVerification(
                status = SignatureStatus.UNSUPPORTED,
                publisherId = publisherId,
                reason = "发行者公钥格式非法: ${error.message}",
            )
        }
        val canonicalPublicKey = Base64.getEncoder().encodeToString(publicKeyBytes)
        if (envelope.publicKey != canonicalPublicKey) {
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId,
                fingerprint = publicKeyFingerprint(publicKeyBytes),
                reason = "发行者公钥编码不是标准 Base64",
            )
        }
        val signatureBytes = decodeBase64(envelope.signature, MAX_SIGNATURE_BYTES).getOrElse { error ->
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId,
                fingerprint = publicKeyFingerprint(publicKeyBytes),
                reason = "发行者签名格式非法: ${error.message}",
            )
        }
        val canonicalSignature = Base64.getEncoder().encodeToString(signatureBytes)
        if (envelope.signature != canonicalSignature) {
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId,
                fingerprint = publicKeyFingerprint(publicKeyBytes),
                reason = "发行者签名编码不是标准 Base64",
            )
        }

        val key = publicKey(publicKeyBytes).getOrElse { error ->
            return SignatureVerification(
                status = SignatureStatus.UNSUPPORTED,
                publisherId = publisherId,
                fingerprint = publicKeyFingerprint(publicKeyBytes),
                reason = "发行者公钥不受支持: ${error.message}",
            )
        }
        val fingerprint = publicKeyFingerprint(publicKeyBytes)
        val valid = runCatching {
            Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(key)
                update(signaturePayload(pluginPackage))
            }.verify(signatureBytes)
        }.getOrElse { return SignatureVerification(
            status = SignatureStatus.UNSUPPORTED,
            publisherId = publisherId,
            fingerprint = fingerprint,
            reason = "无法验证发行者签名: ${it.message}",
        ) }
        if (!valid) {
            return SignatureVerification(
                status = SignatureStatus.INVALID,
                publisherId = publisherId,
                fingerprint = fingerprint,
                reason = "发行者签名与插件内容不匹配",
            )
        }

        val trusted = trustedPublisherKeys[publisherId]
            ?.let { trustedKey ->
                publicKeyBytes(trustedKey).getOrNull()?.contentEquals(publicKeyBytes) == true
            } == true
        return SignatureVerification(
            status = if (trusted) SignatureStatus.VALID_TRUSTED else SignatureStatus.VALID_UNTRUSTED,
            publisherId = publisherId,
            fingerprint = fingerprint,
            reason = if (trusted) null else "发行者未受本机信任根信任",
        )
    }

    /**
     * 复核已经落盘的 manifest。
     *
     * 安装确认不仅校验内容摘要，也必须重新应用同一份能力/命名规则；签名验证需要完整包，
     * 因此由调用方使用 [verifySignature] 或 [review] 复核包内容。
     */
    fun rejectionReason(manifest: PluginManifest): String? {
        if (manifest.id.isBlank() || !PLUGIN_ID_REGEX.matches(manifest.id)) {
            return "插件 id 非法"
        }
        val isUiSkin = manifest.kind == UI_SKIN_KIND
        if (isUiSkin) {
            // 声明式皮肤包:允许没有 tools,但必须声明 ui.skin 能力,且不得携带可执行工具/JS。
            if (manifest.tools.isNotEmpty()) {
                return "ui-skin 插件不得声明可执行工具"
            }
            if (UI_SKIN_CAPABILITY !in manifest.capabilities) {
                return "ui-skin 插件必须声明 $UI_SKIN_CAPABILITY 能力"
            }
        } else {
            if (!isSafeRelativePath(manifest.entry) || !manifest.entry.lowercase().endsWith(".js")) {
                return "插件入口文件路径非法"
            }
            if (manifest.tools.isEmpty()) {
                return "插件未声明任何工具"
            }
        }
        if (manifest.trust != EXTERNAL_TRUST) {
            return "外部插件必须使用 $EXTERNAL_TRUST 信任级别"
        }
        val invalidCapability = (manifest.capabilities + manifest.permissions)
            .firstOrNull { it !in allowedCapabilities }
        if (invalidCapability != null) {
            return "插件声明了不允许的能力: $invalidCapability"
        }
        val invalidTool = manifest.tools.firstOrNull { tool ->
            !TOOL_NAME_REGEX.matches(tool.name) || !FUNCTION_NAME_REGEX.matches(tool.functionName)
        }
        return invalidTool?.let {
            "插件工具名称或函数名非法: ${it.name}/${it.functionName}"
        }
    }

    /** 从已加载包生成安装预览。 */
    fun summarize(
        pluginPackage: PluginPackageLoader.LoadedPluginPackage,
        signature: SignatureVerification = verifySignature(pluginPackage),
    ): InstallPreview {
        val entryBytes = pluginPackage.entryCode.toByteArray(Charsets.UTF_8)
        val extraBytes = pluginPackage.extraFiles.values.sumOf { it.toByteArray(Charsets.UTF_8).size }
        return InstallPreview(
            id = pluginPackage.manifest.id,
            name = pluginPackage.manifest.name,
            version = pluginPackage.manifest.version,
            author = pluginPackage.manifest.author,
            description = pluginPackage.manifest.description,
            entry = pluginPackage.manifest.entry,
            trust = pluginPackage.manifest.trust,
            capabilities = pluginPackage.manifest.capabilities,
            permissions = pluginPackage.manifest.permissions,
            tools = pluginPackage.manifest.tools.map { tool ->
                ToolPreview(
                    name = tool.name,
                    functionName = tool.functionName,
                    description = tool.description,
                )
            },
            entrySha256 = sha256(entryBytes),
            contentSha256 = contentSha256(pluginPackage),
            entryBytes = entryBytes.size,
            extraFileCount = pluginPackage.extraFiles.size,
            totalCodeBytes = entryBytes.size + extraBytes,
            signatureStatus = signature.status,
            publisherId = signature.publisherId,
            publisherKeyFingerprint = signature.fingerprint,
            requiresPublisherTrust = signature.status == SignatureStatus.VALID_UNTRUSTED,
        )
    }

    /** 比较两个预览是否仍绑定同一个插件内容和发行者身份。 */
    fun hasSameInstallIdentity(expected: InstallPreview, actual: InstallPreview): Boolean =
        hasSameContentIdentity(expected, actual) && expected.signatureStatus == actual.signatureStatus

    /**
     * 比较预览绑定的内容身份，但忽略信任状态。
     *
     * 用户在确认对话框中明确“信任发行者”后，签名状态会从 VALID_UNTRUSTED 变为
     * VALID_TRUSTED；确认仍必须绑定同一份内容和同一把公钥，不能因为状态变化被误判为
     * TOCTOU。此方法不忽略 publisherId/fingerprint，只忽略本机信任状态。
     */
    fun hasSameContentIdentity(expected: InstallPreview, actual: InstallPreview): Boolean =
        expected.id == actual.id &&
            expected.entry == actual.entry &&
            expected.entrySha256 == actual.entrySha256 &&
            expected.contentSha256 == actual.contentSha256 &&
            expected.publisherId == actual.publisherId &&
            expected.publisherKeyFingerprint == actual.publisherKeyFingerprint

    /** 计算入口代码摘要，供 UI 展示和单测断言。 */
    fun entrySha256(entryCode: String): String = sha256(entryCode.toByteArray(Charsets.UTF_8))

    /**
     * 生成发行者签名输入。
     *
     * 签名 envelope 保留 publisherId、公钥和算法，但把 signature 字段置空，避免自引用；
     * 随后追加 manifest、入口和按路径排序的额外文件，所有部分都带路径和长度边界。
     * 包作者可使用此方法生成签名，应用只接受 [SIGNATURE_ALGORITHM]。
     *
     * 跨端契约：manifest 的 JSON 字节序列必须与 `tools/market-signer/sign.py` 逐字一致。
     * 可选字段（contributes / uiPanel / toolCards）在默认值时不参与序列化（见
     * [PluginManifest.toolCards] 的签名兼容约束）；若改动 manifest 字段集或默认值行为，
     * 必须同步更新签名工具并重跑市场夹具测试（MarketSigningFixtureTest）。
     */
    fun signaturePayload(pluginPackage: PluginPackageLoader.LoadedPluginPackage): ByteArray {
        val manifestForSignature = pluginPackage.manifest.signature?.let { envelope ->
            pluginPackage.manifest.copy(signature = envelope.copy(signature = ""))
        } ?: pluginPackage.manifest
        val parts = buildList {
            add("manifest.json" to AppJson.encodeToString(
                PluginManifest.serializer(),
                manifestForSignature,
            ).toByteArray(Charsets.UTF_8))
            add(pluginPackage.manifest.entry to pluginPackage.entryCode.toByteArray(Charsets.UTF_8))
            pluginPackage.extraFiles.toSortedMap().forEach { (path, content) ->
                add(path to content.toByteArray(Charsets.UTF_8))
            }
        }
        return parts.fold(ByteArray(0)) { current, (path, bytes) ->
            current + signatureHashPart(path, bytes)
        }
    }

    /** 计算插件内容摘要；签名 envelope 作为 manifest 内容的一部分参与完整性绑定。 */
    fun contentSha256(pluginPackage: PluginPackageLoader.LoadedPluginPackage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appendHashPart(
            digest,
            "manifest.json",
            AppJson.encodeToString(
                PluginManifest.serializer(),
                pluginPackage.manifest,
            ).toByteArray(),
        )
        appendHashPart(
            digest,
            pluginPackage.manifest.entry,
            pluginPackage.entryCode.toByteArray(Charsets.UTF_8),
        )
        pluginPackage.extraFiles.toSortedMap().forEach { (path, content) ->
            appendHashPart(digest, path, content.toByteArray(Charsets.UTF_8))
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    /** 将 X.509 公钥规范化为无换行标准 Base64，供信任根持久化。 */
    internal fun canonicalPublicKey(encoded: String): Result<String> =
        publicKeyBytes(encoded).map { Base64.getEncoder().encodeToString(it) }

    /** 计算 X.509 公钥的 SHA-256 指纹。 */
    internal fun publicKeyFingerprint(encoded: ByteArray): String = sha256(encoded)

    /** 校验发行者 ID，避免信任根中出现路径、空白或超长标识。 */
    internal fun isValidPublisherId(publisherId: String): Boolean = PUBLISHER_ID_REGEX.matches(publisherId)

    private fun publicKeyBytes(encoded: String): Result<ByteArray> =
        decodeBase64(encoded, MAX_PUBLIC_KEY_BYTES).map { bytes ->
            require(bytes.isNotEmpty()) { "公钥为空" }
            bytes
        }

    private fun publicKey(encoded: ByteArray): Result<PublicKey> = runCatching {
        val key = KeyFactory.getInstance(KEY_ALGORITHM)
            .generatePublic(X509EncodedKeySpec(encoded))
        val ecKey = key as? ECPublicKey ?: error("公钥不是 EC 公钥")
        require(ecKey.params.curve.field.fieldSize == P256_FIELD_SIZE) { "仅支持 P-256 公钥" }
        key
    }

    private fun decodeBase64(encoded: String, maxBytes: Int): Result<ByteArray> = runCatching {
        require(encoded.isNotBlank()) { "Base64 值为空" }
        require(encoded.length <= maxBytes * 2) { "Base64 值过大" }
        Base64.getDecoder().decode(encoded).also { bytes ->
            require(bytes.size <= maxBytes) { "Base64 解码结果过大" }
        }
    }

    private val PLUGIN_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val PUBLISHER_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
    private val TOOL_NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    private val FUNCTION_NAME_REGEX = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]{0,63}$")

    private const val P256_FIELD_SIZE = 256
    private const val MAX_PUBLIC_KEY_BYTES = 512
    private const val MAX_SIGNATURE_BYTES = 256

    private fun isSafeRelativePath(path: String): Boolean {
        val normalized = path.trimEnd('/')
        return normalized.isNotBlank() &&
            !normalized.startsWith("/") &&
            !normalized.startsWith("\\") &&
            !normalized.contains("..") &&
            !normalized.contains('\\') &&
            normalized.split('/').none { it.isBlank() || it == "." }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun appendHashPart(digest: MessageDigest, path: String, bytes: ByteArray) {
        digest.update(path.toByteArray())
        digest.update(0.toByte())
        digest.update(bytes.size.toString().toByteArray())
        digest.update(0.toByte())
        digest.update(bytes)
        digest.update(0.toByte())
    }

    private fun signatureHashPart(path: String, bytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        appendHashPart(digest, path, bytes)
        return digest.digest()
    }
}
