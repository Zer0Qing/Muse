@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.zer0.muse.data.plugin

import io.zer0.muse.tools.script.ToolDeclaration
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 插件配置项类型。
 *
 * string / boolean / number / select 四种基础类型覆盖绝大多数插件配置场景。
 */
@Serializable
enum class ConfigItemType {
    /** 自由文本输入。 */
    string,
    /** 布尔开关。 */
    boolean,
    /** 数字输入（整数或浮点数）。 */
    number,
    /** 下拉枚举选择；[SelectConfigItem.options] 提供候选值。 */
    select,
}

/**
 * 下拉选择的候选值描述。
 *
 * @param value 实际写入配置的值（LLM/JS 看到的值）
 * @param label 展示给用户的标签（可为空，回退到 [value]）
 */
@Serializable
data class SelectOption(
    val value: String,
    val label: String = value,
)

/**
 * 单个配置项声明（来自 manifest.contributes.configuration）。
 *
 * @param key        唯一标识符，LLM 调用 host.getConfig(key) 时使用
 * @param type       值类型
 * @param defaultVal 用户未手动设置时的默认值
 * @param description 用途说明（LLM 据此决定是否调用及如何传参）
 * @param options      仅 type=select 时有意义；其他类型忽略
 */
@Serializable
data class ConfigItem(
    val key: String,
    val type: ConfigItemType = ConfigItemType.string,
    val defaultVal: JsonElement? = null,
    val description: String = "",
    val options: List<SelectOption> = emptyList(),
)

/**
 * 外部插件发行者签名 envelope。
 *
 * [publicKey] 和 [signature] 均为无换行的标准 Base64：公钥是 X.509
 * SubjectPublicKeyInfo，签名是 `SHA256withECDSA` 的 DER 编码。公钥只描述
 * 发行者身份，不代表信任；是否信任由 Android 私有存储中的本地信任根决定。
 */
@Serializable
data class PluginSignature(
    val publisherId: String,
    val publicKey: String,
    val signature: String,
    val algorithm: String = "SHA256withECDSA",
)

/**
 * 插件清单 (既有实现 plugins/ manifest 实现 + B6-01 外部插件包扩展)。
 *
 * 每个插件通过 manifest 声明元数据、能力、激活事件、入口文件和工具列表。
 * 外部 `.muse-plugin` / ZIP 包必须包含本清单；`PluginManifest.BUILT_IN` 仅作内置能力展示。
 */
@Serializable
data class PluginManifest(
    val id: String,
    val name: String,
    val version: String = "0.1.0",
    val description: String = "",
    /** 作者/来源。 */
    val author: String = "",
    /** 最低兼容 App 版本(语义化版本,当前不做强制阻断,保留字段供未来校验)。 */
    val minAppVersion: String = "1.0.0",
    /** JS 入口文件名(相对包根目录,默认 main.js)。 */
    val entry: String = "main.js",
    /** 插件类型: tool / ui-skin / provider。 */
    val kind: String = "tool",
    /** 信任级别: 内置插件可使用 full-access；外部 ZIP 必须为 sandboxed。 */
    val trust: String = "sandboxed",
    /** 是否在 UI 隐藏。 */
    val hidden: Boolean = false,
    /** 声明的能力 (resource.read / resource.write / network / ui / ui.mood)。 */
    val capabilities: List<String> = emptyList(),
    /** 声明的权限(与 capabilities 对齐,额外用于恶意清单校验)。 */
    val permissions: List<String> = emptyList(),
    /** 激活事件: onStartup / onCommand / onFileType。 */
    val activationEvents: List<String> = listOf("onStartup"),
    /** 是否已启用。 */
    val enabled: Boolean = true,
    /** 插件暴露的工具列表(LLM 可调用,注册时加 pluginId 前缀)。 */
    val tools: List<ToolDeclaration> = emptyList(),
    /** 发行者签名 envelope；旧包缺失该字段时按未签名处理，不自动信任。 */
    val signature: PluginSignature? = null,
    /**
     * 插件声明的配置项列表。
     *
     * 宿主在插件管理页按此 schema 渲染配置表单；运行时插件可通过
     * host.getConfig(key) 读取用户填写的值（默认值或已保存的自定义值）。
     * 旧插件 manifest 不含此字段时回退为空列表，向后完全兼容。
     */
    val contributes: PluginContributes? = null,
    /**
     * v1.0.92: 插件 UI 面板 — 插件包内 HTML 文件的相对路径(如 "ui/panel.html")。
     *
     * 声明后,插件管理页提供"打开面板"入口,以只读方式渲染该界面；
     * 用于贡献自定义看板/报表等界面。旧插件不含此字段时不受影响。
     */
    val uiPanel: String? = null,
    /**
     * v2.0: 插件工具卡 — 工具名 → 卡片 HTML 文件相对路径(如 "cards/stock.html")。
     *
     * 声明后,该插件工具的调用卡在聊天里提供"查看卡片"入口,
     * 以只读方式渲染 HTML;调用参数与结果经 window.__TOOL_CARD__ 注入。
     *
     * 签名兼容约束：默认值**不参与 JSON 序列化**（@EncodeDefault(NEVER)，与
     * 已发布市场包的发行者签名载荷保持字节级一致）；声明非空值时会完整参与签名
     * 与内容摘要。新增任何可选字段必须沿用同一规则 —— 默认值一旦进入序列化，
     * 所有存量签名的包会立刻验签失败（v2.0.0 曾因空 toolCards 被编码，导致
     * 官方市场全部插件报"发行者签名与插件内容不匹配"）。改字段集时必须同步
     * tools/market-signer/sign.py 并重跑 MarketSigningFixtureTest。
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val toolCards: Map<String, String> = emptyMap(),
) {
    companion object {
        val BUILT_IN: List<PluginManifest> = listOf(
            PluginManifest(
                id = "image-gen",
                name = "图片生成",
                description = "支持 OpenAI DALL-E / Gemini Imagen 图片生成",
                trust = "full-access",
                capabilities = listOf("network", "resource.write"),
            ),
            PluginManifest(
                id = "beautify",
                name = "审美增强",
                description = "Markdown 渲染美化、封面风格说明",
                capabilities = listOf("resource.read"),
            ),
            PluginManifest(
                id = "media",
                name = "媒体处理",
                description = "音频/视频/文档解析与处理",
                capabilities = listOf("resource.read", "resource.write"),
            ),
            PluginManifest(
                id = "mcp-bridge",
                name = "MCP 桥接",
                description = "Model Context Protocol 外部连接器",
                trust = "full-access",
                capabilities = listOf("network"),
                activationEvents = listOf("onStartup"),
            ),
            PluginManifest(
                id = "office",
                name = "办公工具",
                description = "PDF/文档/表格处理",
                capabilities = listOf("resource.read", "resource.write"),
            ),
        )
    }
}

/**
 * 插件 manifest 的组合/扩展声明块。
 *
 * 当前只支持 configuration 子块；未来可扩展 UI 皮肤、事件监听等声明。
 */
@Serializable
data class PluginContributes(
    /** 配置项列表，每项对应一个用户可调整的参数。 */
    val configuration: List<ConfigItem> = emptyList(),
)
