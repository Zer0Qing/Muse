package io.zer0.muse.tools

import android.content.Context
import io.zer0.ai.core.ProviderConfig
import io.zer0.ai.image.ImageService
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginCapabilityGateway
import io.zer0.muse.data.plugin.PluginCapabilityLedgers
import io.zer0.muse.data.plugin.PluginFunctionInvoker
import io.zer0.muse.tools.script.NodePluginEngine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.zer0.muse.data.plugin.PluginHostApi
import io.zer0.muse.data.plugin.PluginManifest
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.tools.script.PythonSkillEngine
import io.zer0.muse.tools.script.SkillBridge
import io.zer0.muse.tools.script.SkillEngineResult
import io.zer0.muse.tools.script.WebViewSkillEngine
import io.zer0.muse.ui.qrcode.QrCodeGenerator

/**
 * P1-3b 拆域：Skill 媒体/JS/插件工具实现（从 SkillExecutor.kt 迁移）。
 * 由 SkillExecutor 委托调用。
 */
class SkillMediaToolsImpl(
    private val context: Context,
    private val stickerLibraryRepository: StickerLibraryRepository?,
    private val imageService: ImageService?,
    private val imageDrawConfigProvider: suspend () -> Pair<ProviderConfig?, String?> = { null to null },
    private val pluginManager: PluginManager?,
    /**
     * 插件总开关（默认开启）。
     *
     * 用 lambda 而不是直接依赖 SettingsRepository：这个类被很多地方构造（含测试），
     * 加一个可选依赖比改全部构造点安全；与 [imageDrawConfigProvider] 同型。
     */
    private val pluginsGloballyEnabled: suspend () -> Boolean = { true },
    /**
     * 插件发起工具调用的通道（`tool_invoke`）。为 null 时该动作一律拒绝 ——
     * 缺依赖必须是"不可用"，不能退化成"这个动作不存在所以放过"。
     */
    private val toolBridge: io.zer0.muse.data.plugin.PluginToolInvokeBridge? = null,
) {

    suspend fun execListStickers(args: Map<String, String>): String {
        val repo = stickerLibraryRepository
            ?: return context.getString(R.string.skill_sticker_not_configured)
        val category = args["category"]?.takeIf { it.isNotBlank() }
        // v1.0.54: 无 category 参数时只返回分类概览(分类名 + 数量),
        //   不再全量列出每个表情包 — 表情包多时全量列表可达数万字符,
        //   被 Agent Loop 截断后模型看不到后面的分类,不敢调用 send_sticker。
        if (category == null) {
            val cats = repo.listCategories()
            if (cats.isEmpty()) return context.getString(R.string.skill_sticker_list_empty)
            val sb = StringBuilder()
            sb.appendLine("共 ${cats.size} 个分类:")
            cats.forEach { c ->
                val cnt = repo.listStickers(c).size
                sb.appendLine("[$c]( $cnt 个)")
            }
            // 助手自审第 16 条：分类名必须严格匹配清单，猜错就失效，而且没有
            // 「这个语境适合什么」的推荐。这里按语境给一份对照，让模型从
            // 「当前对话是什么情绪」直接映射到分类，而不是先猜再回来查。
            appendCategoryGuide(cats, sb)
            sb.append("请调用 list_stickers 并传入 category 参数查看某个分类的具体表情包,然后调用 send_sticker 发送。")
            return sb.toString()
        }
        val items = repo.listStickers(category)
        if (items.isEmpty()) {
            // 分类名猜错时给候选，而不是一句「空」—— 否则模型只能再猜一次。
            val cats = repo.listCategories()
            return if (cats.isEmpty()) {
                context.getString(R.string.skill_sticker_list_empty)
            } else {
                buildString {
                    appendLine("分类 \"$category\" 下没有表情包（分类名必须与清单完全一致）。")
                    appendLine("现有分类: ${cats.joinToString("、")}")
                    append("请从中选一个准确的分类名重新调用 list_stickers。")
                }
            }
        }
        val sb = StringBuilder()
        // v1.0.54: 分类列表限制展示前 20 个 — 分类内贴纸可能上百个,全量输出会被截断,
        //   模型看不到完整列表就反复查 list_stickers,死循环不调 send_sticker。
        val shown = items.take(20)
        sb.appendLine("共 ${items.size} 个表情包(分类: $category),以下为前 ${shown.size} 个:")
        shown.forEach { item ->
            sb.appendLine("[${item.category}] ${item.fileName} (id=${item.id})")
        }
        sb.append("请从以上列表中选择一个合适的 id,立即调用 send_sticker 发送,不要重复查询列表。")
        return sb.toString()
    }

    /**
     * 按语境给分类对照表。
     *
     * 分类名是用户自定义的（「开心」「沙雕」「猫猫」…），没法硬编码映射，
     * 所以按关键词把分类归到几种常见对话情绪下。认不出的分类不硬塞，
     * 只提示「其余分类按名称自行判断」—— 猜错映射比不给更糟。
     */
    private fun appendCategoryGuide(categories: List<String>, sb: StringBuilder) {
        sb.append(buildCategoryGuide(categories))
    }

    suspend fun execSendSticker(args: Map<String, String>): String {
        val repo = stickerLibraryRepository
            ?: return context.getString(R.string.skill_sticker_not_configured)
        val id = args["id"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_id)
        val file = repo.getStickerFile(id)
            ?: return context.getString(R.string.skill_sticker_not_found, id)
        return context.getString(R.string.skill_sticker_sent, file.absolutePath)
    }

    suspend fun execGenerateImage(args: Map<String, String>): String {
        val service = imageService
            ?: return context.getString(R.string.skill_image_not_configured)
        val prompt = args["prompt"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_prompt)
        val size = args["size"]?.takeIf { it.isNotBlank() } ?: "1024x1024"
        // v1.136: Skill 可显式指定 model,未指定时由 ImageService 按 ProviderSpecificConfig / Catalog 兜底
        val (drawProviderConfig, configModelId) = imageDrawConfigProvider()
        val model = args["model"]?.takeIf { it.isNotBlank() } ?: configModelId ?: ""
        val referenceImage = args["reference_image"]?.takeIf { it.isNotBlank() }
        return resultOf {
            val urls = service.generate(
                prompt = prompt,
                params = io.zer0.ai.image.ImageGenParams(
                    model = model,
                    size = size,
                    responseFormat = "url",
                    n = 1,
                    referenceImageUri = referenceImage,
                ),
                providerConfig = drawProviderConfig,
            )
            if (urls.isEmpty()) return@resultOf context.getString(R.string.skill_image_no_result, prompt)
            context.getString(R.string.skill_image_generated, prompt, urls.joinToString("\n"))
        }.onError { msg, _ -> Logger.w("SkillExecutor", "generate_image 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.skill_image_failed, prompt)
    }

    suspend fun execGenerateQr(args: Map<String, String>): String {
        val content = args["content"]?.takeIf { it.isNotBlank() }
            ?: return context.getString(R.string.skill_missing_param_content_qr)
        val size = args["size"]?.toIntOrNull()?.coerceIn(100, 2000) ?: 600
        return resultOf {
            val bitmap = io.zer0.muse.ui.qrcode.QrCodeGenerator.generateQrBitmap(content, size)
                ?: return@resultOf context.getString(R.string.skill_qr_gen_failed)
            val filename = "qr_${System.currentTimeMillis()}.png"
            val file = java.io.File(context.cacheDir, filename)
            file.outputStream().use { out ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()
            context.getString(R.string.skill_qr_generated, file.absolutePath)
        }.onError { msg, _ -> Logger.w("SkillExecutor", "generate_qr 失败: $msg") }
            .getOrNull() ?: context.getString(R.string.skill_qr_gen_failed)
    }

    suspend fun execExecuteJavascript(args: Map<String, String>): String {
        val code = args["code"]?.takeIf { it.isNotBlank() }
            ?: return """{"result":"","logs":[],"error":"参数 code 缺失或为空"}"""
        val timeoutMs = args["timeout_ms"]?.toLongOrNull()?.coerceIn(1L, 60_000L) ?: 10000L
        // 初始化 JsSandbox(幂等)— 注入 Application Context
        JsSandbox.init(context)

        val input = kotlinx.serialization.json.buildJsonObject {
            put("code", kotlinx.serialization.json.JsonPrimitive(code))
            put("timeout_ms", kotlinx.serialization.json.JsonPrimitive(timeoutMs.toString()))
        }
        // 委托给 CodeExecutionTool.execute(suspend)— 复用统一的参数解析与结果序列化逻辑
        val output = CodeExecutionTool.execute(input)
        return output.toString()
    }

    suspend fun execPluginTool(skill: SkillEntity, argumentsJson: String): String {
        if (!pluginsGloballyEnabled()) {
            return "插件已被全局停用（设置 → 工具与扩展 → 插件总开关）"
        }
        val parts = skill.implementationKotlin.split(":")
        if (parts.size < 3) return "插件工具路由格式错误: ${skill.implementationKotlin}"
        val pluginId = parts[1]
        val functionName = parts[2]
        val manager = pluginManager ?: return "插件未安装: $pluginId"
        val plugin = manager.findPlugin(pluginId) ?: return "插件未安装: $pluginId"
        if (!plugin.enabled) return "插件已禁用: ${plugin.name}"
        // P0: capabilities/tools/entryCode 一律来自磁盘重新验证后的 manifest，
        // 不再信任 InstalledPlugin.capabilities 等注册表缓存；并在进入 JS 沙盒前
        // 拒绝已被新 manifest 移除/改名的 functionName。
        val verified = manager.loadVerifiedFunction(pluginId, functionName)
            ?: return "插件入口校验失败或工具已移除: $pluginId/$functionName"

        val configJson = manager.getPluginConfigJson(verified.manifest)
        val argsJson = "[" + argumentsJson.ifBlank { "{}" } + "]"


        // v2.7 → P0-6: 按 manifest.runtime 分派执行引擎。
        //  - python：内置 CPython 子进程（高信任档，需用户单独允许）
        //  - node：内置 Node 子进程 + vm 无网络上下文（可打断、可杀，见 NodePluginEngine）
        //  - 其余（默认 js）：WebView V8 沙盒 + __bridge__ 桥接
        if (verified.manifest.runtime.equals("python", ignoreCase = true)) {
            return execPythonPluginTool(
                pluginId = pluginId,
                entryCode = verified.entryCode,
                functionName = functionName,
                argsJson = argsJson,
                configJson = configJson,
            )
        }

        if (PluginFunctionInvoker.isNode(verified.manifest)) {
            return finishPluginCall(
                result = NodePluginEngine(context).callPluginFunction(
                    entryCode = verified.entryCode,
                    functionName = functionName,
                    argsJson = argsJson,
                    scopeKey = pluginId,
                    pluginConfigJson = configJson,
                    // 多轮桥接（P0-7-1）：插件里每次 muse.bridge() 都走**与 js 档同一套判定**，
                    // 不复用第二套逻辑 —— 两套授权的结局一定是其中一套漏检。
                    bridgeHandler = { requestJson ->
                        bridgeHandle(bridgeSentinel(requestJson), pluginId, verified.manifest, manager)
                    },
                ),
                manager = manager,
                pluginId = pluginId,
                manifest = verified.manifest,
            )
        }

        JsSandbox.init(context)
        // C-30: 传入 pluginId 作为 scopeKey,使熔断状态与 localStorage 按插件隔离,
        //   一个插件死循环超时不会熔断/影响其他插件与内置 JS 工具。
        return finishPluginCall(
            result = WebViewSkillEngine().callFunction(
                verified.entryCode,
                functionName,
                argsJson,
                scopeKey = pluginId,
                pluginConfigJson = configJson,
            ),
            manager = manager,
            pluginId = pluginId,
            manifest = verified.manifest,
        )
    }

    /**
     * 统一收尾：成功就过桥接（按能力账本放行），失败就处理熔断。
     *
     * 抽出来是因为 WebView 档与 Node 档的后续处理**完全一样**；留在原处就得写两遍，
     * 而"两遍"的结局一定是其中一遍忘了改 —— 桥接与账本恰好是最不能漏的那一处。
     */
    private suspend fun finishPluginCall(
        result: SkillEngineResult,
        manager: PluginManager,
        pluginId: String,
        manifest: PluginManifest,
    ): String = when (result) {
            is SkillEngineResult.Success -> bridgeOrValue(result.valueJson, pluginId, manifest, manager)
            is SkillEngineResult.Error -> {
                if (JsSandbox.isCircuitBrokenFor(pluginId)) {
                    resultOf { manager.setEnabled(pluginId, false) }
                        .onError { msg, _ -> Logger.w("SkillMediaToolsImpl", "自动禁用插件失败: $msg") }
                    "插件已自动禁用: JS 沙盒连续超时，请稍后重试"
                } else {
                    "插件工具执行失败: ${result.message}"
                }
            }
        }

    /**
     * 桥接动作按**能力账本**放行（P0-2）。此前这里无条件传 `ALL_ACTIONS`，
     * 而安装确认框又向用户展示"声明能力"，等于给了假安全感。
     *
     * 保留的两类底线与能力无关，属正确性：
     *  1. `fs_*` 仍锁定在插件自己的沙盒目录 `workspace/plugins/<pluginId>`，防路径逃逸；
     *  2. `http_*` 仍走 SSRF 防护（拒内网/本地地址）。
     */
    private suspend fun bridgeOrValue(
        value: String,
        pluginId: String,
        manifest: PluginManifest,
        manager: PluginManager,
    ): String = when (val bridge = bridgeHandle(value, pluginId, manifest, manager)) {
            is SkillBridge.HandleResult.NotBridge ->
                runCatching { AppJson.decodeFromString<String>(value) }.getOrElse { value.ifBlank { "null" } }
            // 桥接请求的执行结果作为技能返回值直接返回给 LLM
            is SkillBridge.HandleResult.Output -> bridge.json
            // 桥接解析/执行失败（含 action 未授权）→ 返回错误对象，避免脚本以为成功
            is SkillBridge.HandleResult.Failure -> buildJsonObject { put("error", JsonPrimitive(bridge.message)) }.toString()
        }

    /**
     * 桥接判定的唯一入口：动作集来自**能力账本**（P0-2），不是清单声明、也不是全放行。
     *
     * 两类底线与能力无关，属正确性，所以留在 [SkillBridge] 里：
     *  1. `fs_*` 锁定在插件自己的沙盒目录 `workspace/plugins/<pluginId>`，防路径逃逸；
     *  2. `http_*` 走 SSRF 防护（拒内网/本地地址）。
     */
    private suspend fun bridgeHandle(
        value: String,
        pluginId: String,
        manifest: PluginManifest,
        manager: PluginManager,
    ): SkillBridge.HandleResult {
        val allowedBridgeActions = PluginCapabilityLedgers.shared(context)
            .allowedActions(pluginId, manifest.capabilities)
        // 宿主侧状态写入（config_set 等）先过这一层；不属于它的动作原样回落 SkillBridge。
        // 授权集合是同一份 —— 两处共用，不存在"走哪条通道更宽松"的差别。
        PluginHostApi(
            writeConfig = { id, key, newValue -> manager.setPluginConfig(id, key, newValue) },
            toolBridge = toolBridge,
        )
            .handle(pluginId, manifest, value, allowedBridgeActions)
            ?.let { return it }
        val bridgeHost = SkillBridge.Host(
            context = context,
            fsRoot = java.io.File(context.filesDir, "workspace/plugins/$pluginId"),
        )
        return SkillBridge.tryHandle(value, allowedBridgeActions, bridgeHost)
    }

    /** 把 RPC 请求 `{"action":...,"params":...}` 变成 js 档用的 `__bridge__` 哨兵文本。 */
    private fun bridgeSentinel(requestJson: String): String {
        val request = runCatching { AppJson.parseToJsonElement(requestJson) as? JsonObject }.getOrNull()
            ?: return ""
        return buildJsonObject {
            put("__bridge__", JsonPrimitive(true))
            request["action"]?.let { put("action", it) }
            request["params"]?.let { put("params", it) }
        }.toString()
    }

    /**
     * v2.7: 执行 Python 插件工具。
     *
     * 与 JS 分支的差异：
     *  - 无 WebView 沙盒，也无 __bridge__ 桥接（Python 子进程自身已有完整能力，
     *    文件读写/网络/标准库直接用；高风险仍由插件工具的整体审批链兜底）；
     *  - 首次调用前确保 Python 运行时数据（stdlib/CA bundle）已就绪。
     *  - 返回值直接作为工具结果（字符串）；返回非字符串时 JSON 序列化。
     */
    private suspend fun execPythonPluginTool(
        pluginId: String,
        entryCode: String,
        functionName: String,
        argsJson: String,
        configJson: String?,
    ): String {
        // P0-5：Python 是同 UID 的子进程，能直接读写应用数据，桥接闸门管不到它。
        // 这一档必须由用户显式批准，且不接受清单自我声明 —— 否则插件写一行 runtime:python 就自给了。
        if (!PluginCapabilityLedgers.shared(context).isApproved(pluginId, PluginCapabilityGateway.RUNTIME_PYTHON)) {
            Logger.w("SkillMediaToolsImpl", "Python 插件未获高信任授权: $pluginId")
            return "插件需要「Python 运行时」高信任授权：Python 以子进程运行、与本应用同一 UID，" +
                "可直接读写应用数据。请在 设置 → 工具与扩展 → 插件 → 「$pluginId」→ 能力与权限 中允许后重试。"
        }
        val dataReady = io.zer0.muse.runtime.PythonRuntime.ensureData(context)
        if (dataReady.isFailure) {
            return "Python 运行时未就绪: ${dataReady.exceptionOrNull()?.message}"
        }
        return when (
            val result = PythonSkillEngine(context).callPluginFunction(
                entryCode = entryCode,
                functionName = functionName,
                argsJson = argsJson,
                scopeKey = pluginId,
                pluginConfigJson = configJson,
            )
        ) {
            is SkillEngineResult.Success -> runCatching {
                AppJson.decodeFromString<String>(result.valueJson)
            }.getOrElse {
                result.valueJson.ifBlank { "null" }
            }
            is SkillEngineResult.Error -> "插件工具执行失败: ${result.message}"
        }
    }
    companion object {
        /**
         * 生成语境对照段。纯函数，便于单测。
         *
         * @return 空串表示没有任何分类命中关键词（此时不输出这一段）
         */
        internal fun buildCategoryGuide(categories: List<String>): String {
            val guide = STICKER_MOOD_KEYWORDS.mapNotNull { (mood, keys) ->
                val matched = categories.filter { cat -> keys.any { cat.contains(it, ignoreCase = true) } }
                if (matched.isEmpty()) null else mood to matched
            }
            if (guide.isEmpty()) return ""
            val covered = guide.flatMap { it.second }.toSet()
            val rest = categories.filterNot { it in covered }
            return buildString {
                appendLine("按当前对话情绪挑分类:")
                guide.forEach { (mood, cats) -> appendLine("- $mood → ${cats.joinToString("、")}") }
                if (rest.isNotEmpty()) appendLine("- 其余分类按名称自行判断: ${rest.joinToString("、")}")
                appendLine()
            }
        }
    }
}

/**
 * 表情包分类的语境关键词表。
 *
 * 分类名由用户自定义，这里只做「关键词命中」的弱匹配：命中就归到对应情绪下，
 * 没命中就留给模型按名称判断。宁可少给建议，也不要给错映射。
 */
private val STICKER_MOOD_KEYWORDS: List<Pair<String, List<String>>> = listOf(
    "开心/庆祝" to listOf("开心", "庆祝", "高兴", "快乐", "happy", "celebration", "party"),
    "安慰/治愈" to listOf("安慰", "治愈", "抱抱", "温暖", "comfort", "hug", "healing"),
    "难过/低落" to listOf("难过", "哭", "低落", "伤心", "sad", "cry"),
    "生气/吐槽" to listOf("生气", "愤怒", "吐槽", "白眼", "angry", "annoyed"),
    "搞笑/沙雕" to listOf("搞笑", "沙雕", "魔性", "funny", "meme", "silly"),
    "可爱/卖萌" to listOf("可爱", "卖萌", "萌", "cute", "kawaii"),
    "鼓励/加油" to listOf("鼓励", "加油", "打气", "cheer", "encourage"),
    "无奈/摆烂" to listOf("无奈", "摆烂", "躺平", "sigh", "shrug"),
    "晚安/睡觉" to listOf("晚安", "睡", "困", "goodnight", "sleep"),
)
