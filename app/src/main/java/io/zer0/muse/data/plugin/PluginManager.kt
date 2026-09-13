package io.zer0.muse.data.plugin

import android.content.Context
import android.net.Uri
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.ui.common.feedback.MuseToast
import io.zer0.muse.data.AtomicFileStore
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.skill.SkillRepository
import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * B6-01: 外部插件管理器。
 *
 * 插件包安装到 `filesDir/plugins/<id>/`，注册表保存在 `filesDir/plugin_registry.json`；
 * 插件工具以 `plugin_<pluginId>_<toolName>` 写入 skills 表，执行时由 SkillExecutor
 * 按 `plugin:<pluginId>:<functionName>` 路由到 JS 沙盒。
 */
@Suppress("TooGenericExceptionCaught", "FunctionParameterNaming")
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
        /** 已安装插件内容的 SHA-256，用于检测插件文件被替换。旧注册表缺失时为空。 */
        val contentSha256: String = "",
        val installedAt: Long = System.currentTimeMillis(),
    )

    @Serializable
    private data class PluginRegistry(val plugins: List<InstalledPlugin> = emptyList())

    private val pluginsDir = File(context.filesDir, "plugins")
    private val registryFile = File(context.filesDir, "plugin_registry.json")

    @Volatile
    private var cached: List<InstalledPlugin> = loadRegistry()

    /** R-SVC-07: registry 读改写互斥,避免并发安装/卸载写坏 JSON。 */
    private val registryMutex = Mutex()

    suspend fun installFromUri(uri: Uri): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.muse-plugin")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temp.outputStream().use { output -> copyLimited(input, output, MAX_PLUGIN_PACKAGE_BYTES) }
            } ?: return@withContext Result.failure(IllegalStateException("无法打开所选文件"))
            installFromFile(temp)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            runCatching { if (temp.exists()) temp.delete() }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun installFromFile(file: File): Result<InstalledPlugin> = withContext(Dispatchers.IO) {
        // C-30 注记: 当前安装流程仅做「结构/能力」校验(见 PluginPackageLoader: ZIP 炸弹、路径遍历、
        // 入口文件存在、id/name/capabilities 合法),**不包含签名校验** —— 插件包格式无 hash/signature 字段,
        // 安装的 JS 代码是未签名的信任即用(trust-on-first-use)模型,靠 JsSandbox 沙盒(禁用网络/导航)兜底运行时风险。
        // 若要补真签名校验,需在 manifest 增加签名/hash 字段并在本方法与 PluginPackageLoader 中校验;在此之前,
        // 已在 UI 导入入口预留「安装二次确认」提示位(见 PluginManagePage.importFromUri)。
        val bytes = readPluginPackage(file)
            .getOrElse { e -> return@withContext Result.failure(e) }
        when (val loaded = PluginPackageLoader.loadFromZip(bytes)) {
            is PluginPackageLoader.Result.Err -> Result.failure(IllegalStateException(loaded.reason))
            is PluginPackageLoader.Result.Ok -> {
                val manifest = loaded.package_.manifest
                // 纵深防御：外部包不能靠 manifest 自报 full-access；作者签名信任根尚未启用。
                if (manifest.trust != "sandboxed") {
                    return@withContext Result.failure(
                        IllegalStateException("外部插件必须使用 sandboxed 信任级别"),
                    )
                }
                val invalidCapability = (manifest.capabilities + manifest.permissions)
                    .firstOrNull { it !in ALLOWED_CAPABILITIES }
                if (invalidCapability != null) {
                    return@withContext Result.failure(
                        IllegalStateException("插件声明了不允许的能力: $invalidCapability"),
                    )
                }

                val contentSha256 = contentSha256(loaded.package_)
                val targetDir = File(pluginsDir, manifest.id)
                val stagingDir = File(pluginsDir, ".staging_${manifest.id}_${System.nanoTime()}")
                val previousCached = cached
                var backupDir: File? = null
                val installResult = runCatching {
                    // 先完整写入同一文件系统下的 staging 目录，再切换 active 目录。
                    // 旧版本保留到临时备份，切换失败时恢复，避免安装中断留下半安装插件。
                    writePluginDirectory(stagingDir, manifest, loaded.package_)
                    backupDir = replacePluginDirectory(stagingDir, targetDir)
                }
                if (installResult.isFailure) {
                    if (stagingDir.exists()) stagingDir.deleteRecursively()
                    installResult.exceptionOrNull()?.let { error ->
                        Logger.e(TAG, "插件安装提交失败: ${manifest.id}", error)
                    }
                    return@withContext Result.failure(
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
                    enabled = manifest.enabled,
                    contentSha256 = contentSha256,
                    installedAt = System.currentTimeMillis(),
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
                    return@withContext Result.failure(error)
                }
                backupDir?.deleteRecursively()
                registerSkills(installed)
                Logger.i(TAG, "插件已安装: ${installed.id} v${installed.version}")
                Result.success(installed)
            }
        }
    }

    suspend fun uninstall(id: String) {
        withContext(Dispatchers.IO) {
            val plugin = findPlugin(id) ?: return@withContext
            val deleteResult = resultOf { File(pluginsDir, id).deleteRecursively() }
                .onError { msg, t -> Logger.e(TAG, "插件目录删除失败: $id ($msg)", t) }
            if (deleteResult.isError || deleteResult.getOrNull() == false) {
                MuseToast.show(context.getString(R.string.error_operation_failed), 2500)
                return@withContext
            }
            registryMutex.withLock {
                cached = cached.filterNot { it.id == id }
                persistRegistry()
            }
            plugin.tools.forEach { tool ->
                runCatching { skillRepository.delete(skillId(id, tool.name)) }
            }
            Logger.i(TAG, "插件已卸载: $id")
        }
    }

    suspend fun setEnabled(id: String, enabled: Boolean) {
        withContext(Dispatchers.IO) {
            val plugin = findPlugin(id) ?: return@withContext
            registryMutex.withLock {
                cached = cached.map { if (it.id == id) it.copy(enabled = enabled) else it }
                persistRegistry()
            }
            plugin.tools.forEach { tool ->
                runCatching { skillRepository.setEnabled(skillId(id, tool.name), enabled) }
            }
        }
    }

    fun list(): List<InstalledPlugin> = cached

    fun findPlugin(id: String): InstalledPlugin? = cached.firstOrNull { it.id == id }

    fun loadEntryCode(id: String): String? {
        val plugin = findPlugin(id) ?: return null
        val directory = File(pluginsDir, plugin.id)
        return runCatching {
            val entry = directory.resolve(plugin.entry).readText()
            if (plugin.contentSha256.isBlank()) {
                // 兼容旧注册表：旧插件没有内容摘要，暂时允许加载，但记录降级状态。
                Logger.w(TAG, "插件缺少内容摘要，无法执行完整性校验: ${plugin.id}")
                return@runCatching entry
            }
            val actual = contentSha256(directory, plugin.entry)
            check(actual == plugin.contentSha256) { "插件内容摘要不匹配" }
            entry
        }.onFailure { error ->
            Logger.e(TAG, "插件完整性校验失败，拒绝加载: ${plugin.id}", error)
        }.getOrNull()
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

    private fun writePluginDirectory(
        directory: File,
        manifest: PluginManifest,
        pluginPackage: PluginPackageLoader.LoadedPluginPackage,
    ) {
        check(directory.mkdirs() || directory.isDirectory) { "无法创建插件 staging 目录" }
        File(directory, "manifest.json").writeText(
            AppJson.encodeToString(PluginManifest.serializer(), manifest),
        )
        File(directory, manifest.entry).writeText(pluginPackage.entryCode)
        pluginPackage.extraFiles.forEach { (relative, content) ->
            val out = File(directory, relative)
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

    @Suppress("TooGenericExceptionCaught")
    private fun contentSha256(pluginPackage: PluginPackageLoader.LoadedPluginPackage): String {
        val digest = MessageDigest.getInstance("SHA-256")
        appendHashPart(
            digest,
            "manifest.json",
            AppJson.encodeToString(
                PluginManifest.serializer(),
                pluginPackage.manifest,
            ).toByteArray(),
        )
        appendHashPart(digest, pluginPackage.manifest.entry, pluginPackage.entryCode.toByteArray())
        pluginPackage.extraFiles.toSortedMap().forEach { (path, content) ->
            appendHashPart(digest, path, content.toByteArray())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun contentSha256(directory: File, entryPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val manifest = directory.resolve("manifest.json").readText()
        appendHashPart(digest, "manifest.json", manifest.toByteArray())
        directory.resolve(entryPath).readBytes().let { bytes -> appendHashPart(digest, entryPath, bytes) }
        directory.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(directory).invariantSeparatorsPath }
            .filter { it != "manifest.json" && it != entryPath }
            .sorted()
            .forEach { path -> appendHashPart(digest, path, directory.resolve(path).readBytes()) }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun appendHashPart(digest: MessageDigest, path: String, bytes: ByteArray) {
        digest.update(path.toByteArray())
        digest.update(0.toByte())
        digest.update(bytes.size.toString().toByteArray())
        digest.update(0.toByte())
        digest.update(bytes)
        digest.update(0.toByte())
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

    private fun skillId(pluginId: String, toolName: String): String = "plugin_${pluginId}_${toolName}"

    companion object {
        private const val TAG = "PluginManager"
        /** 压缩包原始大小上限；与 loader 的解压后上限配合，防止 readBytes/copyTo 无界增长。 */
        internal const val MAX_PLUGIN_PACKAGE_BYTES = 20L * 1024 * 1024

        private val ALLOWED_CAPABILITIES = setOf(
            "resource.read",
            "ui",
            "ui.mood",
        )
    }
}
