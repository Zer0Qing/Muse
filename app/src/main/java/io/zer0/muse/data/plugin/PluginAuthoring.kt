package io.zer0.muse.data.plugin

import io.zer0.common.AppJson
import io.zer0.muse.tools.script.ToolDeclaration
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 助手（大模型）起草插件的请求。
 *
 * 模型只提交文本：JS 源码、工具声明与元数据；ZIP 包与 manifest 一律由宿主按
 * [PluginAuthoringRules] 构建，模型不产出任何二进制。构建出的包是**未签名草稿**，
 * 只有用户在插件管理页明确点头后，才会由本机作者密钥签名并启用。
 */
data class AuthoredPluginRequest(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    /** JS 入口源码；函数（工具实现）必须是顶层 `function` 声明。 */
    val code: String,
    /** 工具声明；函数名必须确实存在于 [code] 中。 */
    val tools: List<ToolDeclaration>,
    /** 声明能力，必须 ⊆ [PluginSecurityGate.allowedCapabilities]；默认不声明任何能力。 */
    val capabilities: List<String> = emptyList(),
)

/**
 * 助手起草插件的宿主侧规则（纯逻辑，便于单测）。
 *
 * 校验口径与 `tools/market-signer/sign.py` 一致：id/工具名/函数名形状、能力白名单，
 * 以及「manifest 声明的函数必须真的作为顶层函数写在入口代码里」。任何一条不通过都
 * 返回中文可读原因，且不产生任何落盘副作用（调用方在构建 ZIP 之前校验）。
 */
object PluginAuthoringRules {

    /** 入口代码大小上限；远低于 loader 的单文件/总解压上限，避免用巨大草稿挤占磁盘。 */
    const val MAX_CODE_BYTES = 256 * 1024

    /** 单个草稿允许声明的工具数量上限。 */
    const val MAX_TOOLS = 32

    /** 宿主固定入口文件名；模型不能自选入口路径。 */
    const val ENTRY_FILE = "main.js"

    /** 校验请求；返回中文可读原因，null 表示通过。 */
    fun validate(request: AuthoredPluginRequest): String? {
        if (!PLUGIN_ID_REGEX.matches(request.id)) {
            return "插件 id 非法: 只能包含小写字母、数字、下划线和连字符，且以字母或数字开头"
        }
        if (request.name.isBlank()) {
            return "插件名称不能为空"
        }
        if (PluginVersion.parse(request.version) == null) {
            return "插件版本号必须是语义化版本（如 1.0.0）: ${request.version}"
        }
        if (request.code.isBlank()) {
            return "插件代码不能为空"
        }
        val codeBytes = request.code.toByteArray(Charsets.UTF_8).size
        if (codeBytes > MAX_CODE_BYTES) {
            return "插件代码超过大小上限(${MAX_CODE_BYTES / 1024}KB): 当前 $codeBytes 字节"
        }
        if (request.tools.isEmpty()) {
            return "插件至少需要声明一个工具"
        }
        if (request.tools.size > MAX_TOOLS) {
            return "插件工具数量超过上限($MAX_TOOLS): 当前 ${request.tools.size} 个"
        }
        val duplicate = request.tools.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        if (duplicate != null) {
            return "插件工具名重复: ${duplicate.key}"
        }
        request.tools.forEach { tool ->
            if (!TOOL_NAME_REGEX.matches(tool.name)) {
                return "工具名非法: ${tool.name}（只允许字母、数字、下划线和连字符，长度 1-64）"
            }
            if (!FUNCTION_NAME_REGEX.matches(tool.functionName)) {
                return "工具函数名非法: ${tool.functionName}（只允许字母、数字、下划线和 $，且不能以数字开头）"
            }
            if (!definesFunction(request.code, tool.functionName)) {
                return "插件代码中没有定义工具函数 ${tool.functionName}（需要顶层 function 声明）"
            }
        }
        val invalidCapability = request.capabilities.firstOrNull { it !in PluginSecurityGate.allowedCapabilities }
        if (invalidCapability != null) {
            return "插件声明了不允许的能力: $invalidCapability"
        }
        return null
    }

    /**
     * 判断入口代码里是否存在顶层函数 [functionName]。
     *
     * 与 `tools/market-signer/sign.py` 的检查同口径：行首（或紧跟换行）到函数名之间
     * 只允许空白与 `function` 关键字。模型常见的缩进函数声明不算顶层函数，
     * 因为插件引擎只导出顶层函数。
     */
    fun definesFunction(code: String, functionName: String): Boolean =
        Regex("(^|\n)[ \t]*function\\s+" + Regex.escape(functionName) + "\\s*\\(").containsMatchIn(code)

    /**
     * 按模型请求构建 manifest。
     *
     * `trust` 固定 sandboxed、`kind` 固定 tool、入口固定 [ENTRY_FILE]；`enabled=true`
     * 只表示「一旦确认就可以启用」，草稿阶段仍由安装路径强制禁用
     * （PluginManager 只在 installationConfirmed 时才启用）。
     */
    fun manifestOf(request: AuthoredPluginRequest): PluginManifest = PluginManifest(
        id = request.id,
        name = request.name,
        version = request.version,
        description = request.description,
        author = "",
        entry = ENTRY_FILE,
        kind = "tool",
        trust = PluginSecurityGate.EXTERNAL_TRUST,
        capabilities = request.capabilities,
        permissions = emptyList(),
        activationEvents = listOf("onStartup"),
        enabled = true,
        tools = request.tools,
        signature = null,
    )

    /** 用宿主构建 ZIP 包；[extraFiles] 只用于重新打包已安装内容（签名启用路径）。 */
    fun buildZip(
        manifest: PluginManifest,
        entryCode: String,
        extraFiles: Map<String, String> = emptyMap(),
    ): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
            zip.write(
                AppJson.encodeToString(PluginManifest.serializer(), manifest).toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(manifest.entry))
            zip.write(entryCode.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            extraFiles.toSortedMap().forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    private const val MANIFEST_ENTRY = "manifest.json"
    private val PLUGIN_ID_REGEX = Regex("^[a-z0-9][a-z0-9_-]*$")
    private val TOOL_NAME_REGEX = Regex("^[a-zA-Z0-9_-]{1,64}$")
    private val FUNCTION_NAME_REGEX = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]{0,63}$")
}
