package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.util.readZipEntryWithLimit
import kotlinx.serialization.decodeFromString
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * B6-01: 外部插件包加载器。
 *
 * 支持 `.muse-plugin` / ZIP 插件包：
 * ```
 * plugin.muse-plugin (ZIP)
 * ├── manifest.json   (必需，PluginManifest)
 * ├── main.js         (必需，JS 入口)
 * └── assets/         (可选，插件资源)
 * ```
 *
 * 安全限制与 SkillPackageLoader 一致：ZIP 炸弹防护、路径遍历拦截、入口文件必须存在。
 */
object PluginPackageLoader {

    private const val TAG = "PluginPackageLoader"

    private const val MAX_ENTRY_SIZE = 2L * 1024 * 1024
    private const val MAX_TOTAL_SIZE = 10L * 1024 * 1024
    private const val MANIFEST_ENTRY = "manifest.json"

    sealed class Result {
        data class Ok(val package_: LoadedPluginPackage) : Result()
        data class Err(val reason: String) : Result()
    }

    data class LoadedPluginPackage(
        val manifest: PluginManifest,
        val entryCode: String,
        val extraFiles: Map<String, String> = emptyMap(),
    )

    fun loadFromZip(zipBytes: ByteArray): Result {
        var totalSize = 0L
        var manifestJson: String? = null
        val allFiles = mutableMapOf<String, String>()

        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (entry.isDirectory) {
                        entry = zis.nextEntry
                        continue
                    }
                    if (name.contains("..") || name.startsWith("/")) {
                        return Result.Err("非法路径: $name")
                    }
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

        val json = manifestJson ?: return Result.Err("缺少 manifest.json")
        val manifest = runCatching {
            AppJson.decodeFromString<PluginManifest>(json)
        }.getOrElse { e ->
            return Result.Err("manifest.json 解析失败: ${e.message}")
        }

        if (manifest.id.isBlank() || manifest.name.isBlank()) {
            return Result.Err("manifest.json 缺少 id/name")
        }
        if (!PLUGIN_ID_REGEX.matches(manifest.id)) {
            return Result.Err("插件 id 只能包含小写字母、数字、下划线和连字符: ${manifest.id}")
        }
        if (manifest.tools.isEmpty()) {
            return Result.Err("manifest.json 未声明任何工具")
        }

        val entryCode = allFiles.remove(manifest.entry)
            ?: return Result.Err("缺少入口文件: ${manifest.entry}")

        Logger.i(TAG, "已加载插件包: ${manifest.id} v${manifest.version} (${manifest.tools.size} 工具)")
        return Result.Ok(
            LoadedPluginPackage(
                manifest = manifest,
                entryCode = entryCode,
                extraFiles = allFiles.toMap(),
            ),
        )
    }

    private val PLUGIN_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
}
