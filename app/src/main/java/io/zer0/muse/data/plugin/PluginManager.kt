package io.zer0.muse.data.plugin

import android.content.Context
import android.net.Uri
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/**
 * B6-01: 外部插件管理器。
 *
 * 插件包安装到 `filesDir/plugins/<id>/`，注册表保存在 `filesDir/plugin_registry.json`；
 * 插件工具以 `plugin_<pluginId>_<toolName>` 写入 skills 表，执行时由 SkillExecutor
 * 按 `plugin:<pluginId>:<functionName>` 路由到 JS 沙盒。
 */
class PluginManager(
    private val context: Context,
    private val skillRepository: SkillRepository,
) {

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
        val enabled: Boolean = true,
        val installedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    private data class PluginRegistry(val plugins: List<InstalledPlugin> = emptyList())

    private val pluginsDir = File(context.filesDir, "plugins")
    private val registryFile = File(context.filesDir, "plugin_registry.json")

    @Volatile
    private var cached: List<InstalledPlugin> = loadRegistry()

    suspend fun installFromUri(uri: Uri): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.muse-plugin")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext Result.failure(IllegalStateException("无法打开所选文件"))
            installFromFile(temp)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    suspend fun installFromFile(file: File): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val bytes = runCatching { file.readBytes() }
            .getOrElse { e -> return@withContext Result.failure(e) }
        when (val loaded = PluginPackageLoader.loadFromZip(bytes)) {
            is PluginPackageLoader.Result.Err -> Result.failure(IllegalStateException(loaded.reason))
            is PluginPackageLoader.Result.Ok -> {
                val manifest = loaded.package_.manifest
                val invalidCapability = (manifest.capabilities + manifest.permissions)
                    .firstOrNull { it !in ALLOWED_CAPABILITIES }
                if (invalidCapability != null) {
                    return@withContext Result.failure(
                        IllegalStateException("插件声明了不允许的能力: $invalidCapability"),
                    )
                }

                val targetDir = File(pluginsDir, manifest.id)
                runCatching {
                    if (targetDir.exists()) targetDir.deleteRecursively()
                    targetDir.mkdirs()
                    File(targetDir, "manifest.json").writeText(
                        AppJson.encodeToString(PluginManifest.serializer(), manifest),
                    )
                    File(targetDir, manifest.entry).writeText(loaded.package_.entryCode)
                    loaded.package_.extraFiles.forEach { (relative, content) ->
                        val out = File(targetDir, relative)
                        out.parentFile?.mkdirs()
                        out.writeText(content)
                    }
                }.onFailure { e ->
                    return@withContext Result.failure(e)
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
                    enabled = manifest.enabled,
                    installedAt = System.currentTimeMillis(),
                )
                cached = cached.filterNot { it.id == installed.id } + installed
                persistRegistry()
                registerSkills(installed)
                Logger.i(TAG, "插件已安装: ${installed.id} v${installed.version}")
                Result.success(installed)
            }
        }
    }

    suspend fun uninstall(id: String) {
        withContext(Dispatchers.IO) {
            val plugin = findPlugin(id) ?: return@withContext
            runCatching { File(pluginsDir, id).deleteRecursively() }
            cached = cached.filterNot { it.id == id }
            persistRegistry()
            plugin.tools.forEach { tool ->
                runCatching { skillRepository.delete(skillId(id, tool.name)) }
            }
            Logger.i(TAG, "插件已卸载: $id")
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val plugin = findPlugin(id) ?: return@withContext
            cached = cached.map { if (it.id == id) it.copy(enabled = enabled) else it }
            persistRegistry()
            plugin.tools.forEach { tool ->
                runCatching { skillRepository.setEnabled(skillId(id, tool.name), enabled) }
            }
        }
    }

    fun list(): List<InstalledPlugin> = cached

    fun findPlugin(id: String): InstalledPlugin? = cached.firstOrNull { it.id == id }

    fun loadEntryCode(id: String): String? {
        val plugin = findPlugin(id) ?: return null
        return runCatching { File(pluginsDir, plugin.id).resolve(plugin.entry).readText() }.getOrNull()
    }

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

    private fun persistRegistry() {
        runCatching {
            registryFile.parentFile?.mkdirs()
            registryFile.writeText(AppJson.encodeToString(PluginRegistry.serializer(), PluginRegistry(cached)))
        }.onFailure { e -> Logger.w(TAG, "插件注册表写入失败", e) }
    }

    private fun loadRegistry(): List<InstalledPlugin> {
        if (!registryFile.exists()) return emptyList()
        return runCatching {
            AppJson.decodeFromString<PluginRegistry>(registryFile.readText()).plugins
        }.getOrElse { e ->
            Logger.w(TAG, "插件注册表解析失败,按空列表处理", e)
            emptyList()
        }
    }

    private fun skillId(pluginId: String, toolName: String): String = "plugin_${pluginId}_${toolName}"

    companion object {
        private const val TAG = "PluginManager"

        private val ALLOWED_CAPABILITIES = setOf(
            "resource.read",
            "resource.write",
            "network",
            "ui",
            "ui.mood",
        )
    }
}
