package io.zer0.muse.license

import kotlinx.serialization.Serializable

/**
 * Phase 13: 开源许可数据模型。
 *
 * 从 `assets/licenses/manifest.json` 解析,字段名与 JSON 一一对应。
 * 注意:全部字段都保留 null-safe,因为某些依赖可能缺版权/链接/notes。
 *
 * 序列化:
 *  - 字段名 = JSON key(直接对得上,无需 @SerialName)
 *  - 用 kotlinx.serialization(已在 Phase 6 引入,本项目内 Koin / OkHttp 已用)
 */

/** 单个依赖的许可条目。 */
@Serializable
data class LicenseEntry(
    val groupId: String,
    val name: String,
    val version: String,
    val licenseId: String,
    // M9: copyright 缺省空串,避免部分依赖缺版权字段时反序列化失败
    val copyright: String = "",
    val url: String = "",
    val notes: String = "",
) {
    /** 用于列表显示的简短标题:`groupId:name` 截断到只剩冒号后部分。 */
    val displayName: String get() = name

    /** 用于列表显示的版本号。 */
    val versionLabel: String get() = version

    /** 用于列表显示的协议简称(取自 licenseId,如 "apache-2.0" → "Apache 2.0")。 */
    val licenseLabel: String get() = licenseId.toLicenseLabel()

    /** 用于跳转的开源项目 URL(若为空,UI 不显示链接按钮)。 */
    val hasUrl: Boolean get() = url.isNotBlank()
}

/** 顶层 manifest 容器。 */
@Serializable
data class LicenseManifest(
    val schemaVersion: Int,
    val generatedAt: String,
    val appName: String,
    val appVersion: String,
    /** licenseId → assets 内相对路径(如 "licenses/apache-2.0.txt")。 */
    val licenseTexts: Map<String, String>,
    val dependencies: List<LicenseEntry>,
) {
    /** 按 licenseId 分组,用于在 UI 上"按协议聚合显示"。 */
    fun groupedByLicense(): Map<String, List<LicenseEntry>> =
        dependencies.groupBy { it.licenseId }
            .toSortedMap(compareBy { it })

    /** 总数,UI 顶部 "共 N 个依赖" 用。 */
    val totalCount: Int get() = dependencies.size
}

/** 把协议 id 渲染成人类可读标签。 */
fun String.toLicenseLabel(): String = when (this) {
    "apache-2.0" -> "Apache 2.0"
    "mit" -> "MIT"
    "bsd-3-clause" -> "BSD 3-Clause"
    "bsd-2-clause" -> "BSD 2-Clause"
    "epl-1.0" -> "EPL 1.0"
    "lgpl-2.1" -> "LGPL 2.1"
    "lgpl-3.0" -> "LGPL 3.0"
    "gpl-2.0" -> "GPL 2.0"
    "gpl-3.0" -> "GPL 3.0"
    "agpl-3.0" -> "AGPL 3.0"
    "mpl-2.0" -> "MPL 2.0"
    "unlicense" -> "Unlicense"
    "cc0-1.0" -> "CC0 1.0"
    else -> this
}
