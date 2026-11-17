package io.zer0.muse.runtime

import io.zer0.common.AppJson
import kotlinx.serialization.Serializable

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
        /** 解析清单文本；格式不对返回 null，调用方按“无可用包”处理。 */
        fun parse(raw: String): RuntimeManifest? = runCatching {
            AppJson.decodeFromString<RuntimeManifest>(raw)
        }.getOrNull()
    }
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
