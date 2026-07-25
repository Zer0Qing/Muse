package io.zer0.ai.plugin

import androidx.compose.runtime.mutableStateMapOf
import io.zer0.ai.core.Model
import io.zer0.ai.core.ModelAbility
import io.zer0.ai.core.ProviderCategory
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.core.ProviderType
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * P2-10: Provider 插件注册中心。
 *
 * 维护一份内存级插件清单(用 [mutableStateMapOf] 让 Compose 能直接观察增删),
 * 负责:
 *  - 注册 / 注销插件
 *  - 从本地 JSON 文件加载插件(SAF 选择文件后转入此方法)
 *  - 把插件转换为 [ProviderConfig],使其能被现有 Provider 系统(
 *    [io.zer0.ai.ProviderRegistry] / [io.zer0.ai.ChatService])直接使用
 *
 * 设计取舍:
 *  - 仅 JSON 配置驱动,不执行 JS 沙盒(简化版)
 *  - 插件转出的 [ProviderConfig] 一律走 [ProviderType.OPENAI] 协议,
 *    因为 OpenAI Chat Completions 是事实上的兼容标准
 *  - v1.134 P1-3: 持久化到 [storageDir](由调用方注入 filesDir/muse_plugins/),
 *    App 重启后自动恢复;构造时同步扫描目录加载所有 .json 文件,
 *    [register] / [unregister] 同步落盘到对应文件
 *
 * @param storageDir 持久化目录;null 表示仅内存模式(向后兼容)
 */
class ProviderPluginRegistry(
    private val storageDir: File? = null,
) {

    /** 插件表:id → 插件。Compose 可观察此 map 的增删。 */
    private val plugins = mutableStateMapOf<String, ProviderPlugin>()

    /** JSON 解析器:容错未知字段,避免插件作者加额外元数据时崩溃。 */
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    init {
        // v1.134 P1-3: 启动时扫描 storageDir,自动加载所有已持久化的插件
        storageDir?.let { dir ->
            runCatching {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
                        ?.forEach { file ->
                            runCatching {
                                val plugin = json.decodeFromString<ProviderPlugin>(file.readText())
                                validate(plugin)
                                // 直接写入 map,绕过 register() 的落盘逻辑(文件已存在,无需重复写)
                                plugins[plugin.id] = plugin
                                Logger.i(TAG, "已加载持久化插件: ${plugin.id} (${plugin.displayName})")
                            }.onFailure { e ->
                                Logger.e(TAG, "加载插件文件失败: ${file.absolutePath}", e)
                            }
                        }
                }
            }.onFailure { e ->
                Logger.e(TAG, "扫描插件目录失败: ${dir.absolutePath}", e)
            }
        }
    }

    /**
     * 注册插件。同 id 覆盖。
     * 若 [storageDir] 非空,同步把插件 JSON 写入 ${id}.json(覆盖式)。
     */
    fun register(plugin: ProviderPlugin) {
        plugins[plugin.id] = plugin
        persist(plugin)
        Logger.i(TAG, "插件已注册: ${plugin.id} (${plugin.displayName})")
    }

    /**
     * 注销插件。不存在时静默忽略。
     * 若 [storageDir] 非空,同步删除对应 ${id}.json 文件。
     */
    fun unregister(pluginId: String) {
        val removed = plugins.remove(pluginId)
        if (removed != null) {
            removePersistedFile(pluginId)
            Logger.i(TAG, "插件已注销: $pluginId")
        }
    }

    /**
     * 列出所有已注册插件(按 id 排序,保证 UI 列表稳定)。
     */
    fun list(): List<ProviderPlugin> = plugins.values.sortedBy { it.id }

    /**
     * 加载本地 JSON 文件并注册为插件。
     *
     * 调用方通常先用 SAF (ActivityResultContracts.OpenDocument) 选定文件,
     * 把 Uri 拷贝到 cache 目录得到 [File],再传入此方法。
     *
     * 失败场景(返回 Result.failure):
     *  - 文件读取失败
     *  - JSON 解析失败
     *  - id / displayName / baseUrl 为空
     *  - apiKeyPattern 不是合法正则
     *
     * 成功时插件已 [register] 到内存表并持久化,直接返回插件实例。
     */
    suspend fun loadFromFile(file: File): Result<ProviderPlugin> = withContext(Dispatchers.IO) {
        runCatching {
            val text = file.readText()
            val plugin = json.decodeFromString<ProviderPlugin>(text)
            validate(plugin)
            register(plugin)
            plugin
        }.onFailure { e ->
            Logger.e(TAG, "加载插件失败: ${file.absolutePath}", e)
        }
    }

    /**
     * 把插件转为 [ProviderConfig],使其能被现有 Provider 系统使用。
     *
     * - [ProviderType] 固定为 OPENAI(插件 JSON 描述的是 OpenAI 兼容协议)
     * - [ProviderConfig.category] = [ProviderCategory.CUSTOM](用户自定义)
     * - apiKey 留空,由 [io.zer0.muse.ui.settings.ProviderEditPage] 在用户编辑时填入
     * - v1.134 P2-2: headers / requestTemplate / responsePath / streamResponsePath
     *   已全部透传到 [ProviderSpecificConfig.Custom] 同名字段;ai 模块的 Provider 实现
     *   后续可消费这些字段实现自定义请求模板与响应路径解析
     */
    fun toProviderConfig(plugin: ProviderPlugin): ProviderConfig {
        val custom = io.zer0.ai.core.ProviderSpecificConfig.Custom(
            chatCompletionsPath = "",
            customHeaders = plugin.headers,
            requestTemplate = plugin.requestTemplate,
            responsePath = plugin.responsePath,
            streamResponsePath = plugin.streamResponsePath,
        )
        return ProviderConfig(
            id = "plugin-${plugin.id}",
            displayName = plugin.displayName,
            type = ProviderType.OPENAI,
            baseUrl = plugin.baseUrl,
            apiKey = "",
            models = plugin.models.map { it.toModel(plugin.id) },
            enabled = true,
            builtIn = false,
            category = ProviderCategory.CUSTOM,
            specific = custom,
        )
    }

    /** 校验插件字段完整性,不合法时抛 [IllegalArgumentException]。 */
    private fun validate(plugin: ProviderPlugin) {
        require(plugin.id.isNotBlank()) { "插件 id 不能为空" }
        require(plugin.displayName.isNotBlank()) { "插件 displayName 不能为空" }
        require(plugin.baseUrl.isNotBlank()) { "插件 baseUrl 不能为空" }
        // 文件名安全性:id 直接用作文件名,禁止路径分隔符与空字符
        require(!plugin.id.contains('/') && !plugin.id.contains('\\') && !plugin.id.contains('\u0000')) {
            "插件 id 不能包含路径分隔符: ${plugin.id}"
        }
        if (plugin.apiKeyPattern.isNotBlank()) {
            // 提前校验正则合法性,避免用户填入 apiKey 时才发现 pattern 无法编译
            try {
                Regex(plugin.apiKeyPattern)
            } catch (e: Exception) {
                throw IllegalArgumentException("插件 apiKeyPattern 不是合法正则: ${plugin.apiKeyPattern}", e)
            }
        }
    }

    /** [PluginModel] → [Model] 转换。 */
    private fun PluginModel.toModel(providerId: String): Model = Model(
        id = id,
        name = displayName,
        providerId = providerId,
        contextWindow = contextWindow,
        supportsVision = supportsVision,
        abilities = if (supportsTools) setOf(ModelAbility.TOOL) else emptySet(),
    )

    /** v1.134 P1-3: 把插件 JSON 写入 storageDir/${id}.json(同步落盘)。 */
    private fun persist(plugin: ProviderPlugin) {
        val dir = storageDir ?: return
        runCatching {
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "${plugin.id}.json")
            file.writeText(json.encodeToString(ProviderPlugin.serializer(), plugin))
        }.onFailure { e ->
            Logger.e(TAG, "持久化插件失败: ${plugin.id}", e)
        }
    }

    /** v1.134 P1-3: 删除 storageDir/${id}.json(若存在)。 */
    private fun removePersistedFile(pluginId: String) {
        val dir = storageDir ?: return
        runCatching {
            val file = File(dir, "$pluginId.json")
            if (file.exists()) file.delete()
        }.onFailure { e ->
            Logger.e(TAG, "删除插件文件失败: $pluginId", e)
        }
    }

    private companion object {
        private const val TAG = "ProviderPluginRegistry"
    }
}
