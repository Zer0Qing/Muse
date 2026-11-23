package io.zer0.muse.runtime

import io.zer0.common.AppJson
import kotlinx.serialization.Serializable
import java.net.URI
import java.security.MessageDigest

/**
 * 运行环境包清单。
 *
 * 这份清单由 `runtime/build.sh` 生成，发布时随包上传；App 端只负责读它、校验它、按它下载。
 * 字段故意做得窄：够用来下载与校验即可，不承载业务能力描述 —— 能力由 [RuntimeId] 那层决定。
 */
@Serializable
data class RuntimeManifest(
    /** 组件标识，与 [RuntimeId] 对应。 */
    val runtimeId: String,
    /** 我们的打包版本（不是上游版本），升级包时自增。 */
    val revision: String,
    /** 目标 ABI，如 arm64-v8a。 */
    val abi: String,
    /** 上游版本，方便排查“这版带的是哪个 Node”。 */
    val upstream: Map<String, String> = emptyMap(),
    /** 压缩包信息。 */
    val archive: Archive,
    /** 解压后占用的字节数，用于安装前检查磁盘与界面展示。 */
    val installedBytes: Long,
    /** proot 那套小文件的 sha256（它们随 APK 的 jniLibs 走，不在此包里）。 */
    val bins: Map<String, String> = emptyMap(),
    val sourceTrust: RuntimeSourceTrust = RuntimeSourceTrust.CHECKSUM_PINNED,
) {
    @Serializable
    data class Archive(
        val name: String,
        val sizeBytes: Long,
        val sha256: String,
        /** 主源在前，失败按顺序重试。 */
        val urls: List<String>,
    )

    companion object {
        /** 解析新旧清单文本；格式不对返回 null，调用方按“无可用包”处理。 */
        fun parse(raw: String): RuntimeManifest? = RuntimeManifestDocument.parse(raw)?.let { document ->
            document.versionsOrLegacy().firstOrNull()?.let(document::toManifest)
        }
    }
}

@Serializable
data class RuntimeVersion(
    val revision: String,
    val label: String = revision,
    val releasedAt: String = "",
    val upstream: Map<String, String> = emptyMap(),
    val archive: RuntimeManifest.Archive,
    val installedBytes: Long,
    val bins: Map<String, String> = emptyMap(),
    val sourceTrust: RuntimeSourceTrust = RuntimeSourceTrust.CHECKSUM_PINNED,
)

@Serializable
enum class RuntimeSourceTrust {
    CHECKSUM_PINNED,
    UNVERIFIED,
}

@Serializable
data class RuntimeManifestDocument(
    val runtimeId: String,
    val abi: String,
    val versions: List<RuntimeVersion> = emptyList(),
    val revision: String? = null,
    val upstream: Map<String, String> = emptyMap(),
    val archive: RuntimeManifest.Archive? = null,
    val installedBytes: Long? = null,
    val bins: Map<String, String> = emptyMap(),
) {
    fun versionsOrLegacy(): List<RuntimeVersion> = versions.ifEmpty {
        val legacyRevision = revision ?: return emptyList()
        val legacyArchive = archive ?: return emptyList()
        val legacyInstalledBytes = installedBytes ?: return emptyList()
        listOf(
            RuntimeVersion(
                revision = legacyRevision,
                upstream = upstream,
                archive = legacyArchive,
                installedBytes = legacyInstalledBytes,
                bins = bins,
            ),
        )
    }

    fun toManifest(version: RuntimeVersion): RuntimeManifest = RuntimeManifest(
        runtimeId = runtimeId,
        revision = version.revision,
        abi = abi,
        upstream = version.upstream,
        archive = version.archive,
        installedBytes = version.installedBytes,
        bins = version.bins,
        sourceTrust = version.sourceTrust,
    )

    fun validateFor(targetAbi: String): ManifestValidation {
        val errors = mutableListOf<String>()
        if (abi != targetAbi) errors += "ABI 不匹配：清单 $abi，设备 $targetAbi"
        val resolved = versionsOrLegacy()
        if (resolved.isEmpty()) errors += "清单没有可用版本"
        resolved.forEachIndexed { index, version ->
            val label = "versions[$index]"
            if (!REVISION.matches(version.revision) || ".." in version.revision) {
                errors += "$label revision 非法"
            }
            if (version.archive.name.isBlank() || !ARCHIVE_NAME.matches(version.archive.name)) {
                errors += "$label archive name 非法"
            }
            if (version.archive.sizeBytes <= 0L) errors += "$label size 必须大于 0"
            if (version.installedBytes <= 0L) errors += "$label installedBytes 必须大于 0"
            if (!SHA256.matches(version.archive.sha256)) errors += "$label archive sha256 非法"
            if (version.archive.urls.isEmpty()) {
                errors += "$label urls 不能为空"
            } else {
                version.archive.urls.forEach { url ->
                    if (!isPublicHttps(url)) errors += "$label URL 必须是公开 HTTPS 地址"
                }
                if (!isGithubRelease(version.archive.urls.last())) {
                    errors += "$label 最后一个 URL 必须是 GitHub Release 原始地址"
                }
            }
            REQUIRED_BIN_HASHES.forEach { binary ->
                val hash = version.bins[binary]
                if (hash == null || !SHA256.matches(hash)) errors += "$label bins[$binary] sha256 缺失或非法"
            }
        }
        return if (errors.isEmpty()) ManifestValidation.Valid else ManifestValidation.Invalid(errors)
    }

    fun fingerprint(): String {
        val payload = AppJson.encodeToString(serializer(), this)
        val digest = MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    companion object {
        fun parse(raw: String): RuntimeManifestDocument? = runCatching {
            AppJson.decodeFromString<RuntimeManifestDocument>(raw)
        }.getOrNull()

        fun isAllowedCustomUrl(raw: String): Boolean = isPublicHttps(raw)

        fun isValidSha256(raw: String): Boolean = SHA256.matches(raw)

        private val REVISION = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
        private val ARCHIVE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*\\.tar\\.gz$")
        private val SHA256 = Regex("^[a-fA-F0-9]{64}$")
        private val REQUIRED_BIN_HASHES = setOf(
            "proot",
            "loader",
            "libtalloc.so.2",
            "libandroid-shmem.so",
        )

        private fun isPublicHttps(raw: String): Boolean = runCatching {
            val uri = URI(raw)
            uri.scheme.equals("https", ignoreCase = true) &&
                !uri.host.isNullOrBlank() &&
                !isLocalHost(uri.host)
        }.getOrDefault(false)

        private fun isGithubRelease(raw: String): Boolean = runCatching {
            val uri = URI(raw)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("github.com", ignoreCase = true) &&
                uri.path.orEmpty().contains("/releases/download/")
        }.getOrDefault(false)

        private fun isLocalHost(host: String): Boolean {
            val value = host.lowercase().removePrefix("[").removeSuffix("]")
            if (value == "localhost" || value == "::1" || value == "0.0.0.0" || value.endsWith(".local")) {
                return true
            }
            if (value.startsWith("127.") || value.startsWith("10.") || value == "10.0.2.2" || value.startsWith("192.168.")) {
                return true
            }
            val octets = value.split('.')
            return octets.size == 4 && octets[0] == "172" && octets[1].toIntOrNull() in 16..31
        }
    }
}

sealed interface ManifestValidation {
    data object Valid : ManifestValidation
    data class Invalid(val reasons: List<String>) : ManifestValidation
}

data class ManifestCandidate(
    val document: RuntimeManifestDocument,
    val source: String,
) {
    val fingerprint: String get() = document.fingerprint()
}

/**
 * 运行环境组件。
 *
 * 现在只有一个；分拆成枚举而不是字符串，是为了让「哪条工具需要哪个环境」在编译期就能对上，
 * 而不是靠运行时拼字符串猜。
 */
enum class RuntimeId(val manifestKey: String, val displayName: String) {
    UBUNTU_NODE("ubuntu-node", "运行环境（Ubuntu + Node）"),
    ;

    companion object {
        fun fromManifestKey(key: String): RuntimeId? = entries.firstOrNull { it.manifestKey == key }
    }
}

/**
 * 运行环境状态。UI 与工具层共用同一套状态，避免「界面显示已装、工具说没装」。
 */
sealed interface RuntimeState {
    /** 没装。 */
    data object NotInstalled : RuntimeState

    /** 装了且自检通过。 */
    data class Ready(val revision: String, val installedBytes: Long) : RuntimeState

    /** 装了但指针指向的目录不完整（被清理、被中断），需要重装。 */
    data class Broken(val reason: String) : RuntimeState
}
