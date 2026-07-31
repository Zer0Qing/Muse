package io.zer0.muse.tools.script

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.serialization.decodeFromString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * Skill 包加载器 (P3-1)。
 *
 * 支持两种加载方式：
 *  1. **.skillpkg ZIP 包**：包含 manifest.json + main.js + 可选辅助文件
 *  2. **单文件 JS**：通过 [MetadataParser] 解析顶部 METADATA 注释块
 *
 * 安全限制：
 *  - ZIP 解压单文件上限 [MAX_ENTRY_SIZE]（防 ZIP 炸弹）
 *  - 入口文件名必须在 ZIP 中存在（防止 manifest 指向任意路径）
 *  - 入口文件名禁止包含 `..` 路径遍历
 *  - 总解压大小上限 [MAX_TOTAL_SIZE]
 *
 * 用法：
 * ```kotlin
 * // 从 ZIP 字节加载
 * val result = SkillPackageLoader.loadFromZip(zipBytes)
 * when (result) {
 *     is SkillPackageLoader.Result.Ok -> { val pkg = result.package_; // 注册到 SkillExecutor
 *     }
 *     is SkillPackageLoader.Result.Err -> { Logger.w(TAG, result.reason) }
 * }
 *
 * // 从单文件 JS 加载
 * val result = SkillPackageLoader.loadFromJs(jsSource, "my_tool.js")
 * ```
 *
 * 参考: Operit SkillPackageLoader 设计，适配 Muse 的 ZIP 防炸弹工具。
 */
object SkillPackageLoader {

    private const val TAG = "SkillPackageLoader"

    /** 单个 ZIP entry 最大解压大小：2MB（防 ZIP 炸弹）。 */
    private const val MAX_ENTRY_SIZE = 2L * 1024 * 1024

    /** 总解压大小上限：10MB。 */
    private const val MAX_TOTAL_SIZE = 10L * 1024 * 1024

    /** manifest.json 在 ZIP 中的固定路径。 */
    private const val MANIFEST_ENTRY = "manifest.json"

    /** 加载结果密封类（符合 AGENTS.md §5 类型安全规范）。 */
    sealed class Result {
        /** 加载成功。 */
        data class Ok(val package_: LoadedSkillPackage) : Result()

        /** 加载失败。 */
        data class Err(val reason: String) : Result()
    }

    /**
     * 已加载的 Skill 包。
     *
     * @param manifest 包清单
     * @param entryCode 入口 JS 文件源码
     * @param extraFiles 辅助文件（key 为相对路径，value 为文件内容；不含 entry 和 manifest）
     */
    data class LoadedSkillPackage(
        val manifest: SkillPackageManifest,
        val entryCode: String,
        val extraFiles: Map<String, String> = emptyMap(),
    )

    /**
     * 从 ZIP 字节数组加载 .skillpkg。
     *
     * ZIP 内文件顺序不保证，因此先读全部 entry 到 map，再按 manifest 查找入口文件。
     *
     * @param zipBytes ZIP 文件字节
     * @return 加载结果
     */
    fun loadFromZip(zipBytes: ByteArray): Result {
        var totalSize = 0L
        var manifestJson: String? = null
        val allFiles = mutableMapOf<String, String>()

        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    // 跳过目录
                    if (entry.isDirectory) {
                        entry = zis.nextEntry
                        continue
                    }
                    // 路径遍历防护
                    if (name.contains("..") || name.startsWith("/")) {
                        return Result.Err("非法路径: $name")
                    }
                    // 只读取文本类文件（js/json/md），其他类型跳过
                    if (!name.endsWith(".js") && !name.endsWith(".json") && !name.endsWith(".md")) {
                        entry = zis.nextEntry
                        continue
                    }

                    val bytes = readZipEntryWithLimit(zis, MAX_ENTRY_SIZE, name)
                    totalSize += bytes.size
                    if (totalSize > MAX_TOTAL_SIZE) {
                        return Result.Err("解压总大小超过限制(${MAX_TOTAL_SIZE / 1024 / 1024}MB)")
                    }

                    val content = bytes.toString(Charsets.UTF_8)
                    if (name == MANIFEST_ENTRY) {
                        manifestJson = content
                    } else {
                        allFiles[name] = content
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: IOException) {
            return Result.Err("ZIP 读取失败: ${e.message}")
        }

        // 校验 manifest 存在并解析
        val json = manifestJson ?: return Result.Err("缺少 manifest.json")
        val pkg = runCatching {
            AppJson.decodeFromString<SkillPackageManifest>(json)
        }.getOrElse { e ->
            return Result.Err("manifest.json 解析失败: ${e.message}")
        }

        // 校验至少声明一个工具
        if (pkg.tools.isEmpty()) {
            return Result.Err("manifest.json 未声明任何工具")
        }

        // 查找入口文件
        val entryCode = allFiles.remove(pkg.entry)
            ?: return Result.Err("缺少入口文件: ${pkg.entry}")

        Logger.i(TAG, "已加载 Skill 包: ${pkg.id} v${pkg.version} (${pkg.tools.size} 工具)")
        return Result.Ok(
            LoadedSkillPackage(
                manifest = pkg,
                entryCode = entryCode,
                extraFiles = allFiles.toMap(),
            ),
        )
    }

    /**
     * 从单文件 JS 加载 Skill（通过 METADATA 注释块）。
     *
     * @param jsSource JS 源码
     * @param fileName 文件名（作为默认 entry）
     * @return 加载结果
     */
    fun loadFromJs(jsSource: String, fileName: String = "main.js"): Result {
        val manifest = MetadataParser.parse(jsSource, defaultEntry = fileName)
            ?: return Result.Err("JS 文件缺少 METADATA 注释块（需包含 @skillpkg 标签）")

        Logger.i(TAG, "已加载单文件 Skill: ${manifest.id} v${manifest.version} (${manifest.tools.size} 工具)")
        return Result.Ok(
            LoadedSkillPackage(
                manifest = manifest,
                entryCode = jsSource,
                extraFiles = emptyMap(),
            ),
        )
    }
}
