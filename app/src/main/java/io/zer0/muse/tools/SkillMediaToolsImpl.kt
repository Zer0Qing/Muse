package io.zer0.muse.tools

import android.content.Context
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.common.resultOf
import io.zer0.muse.R
import io.zer0.muse.data.plugin.PluginManager
import io.zer0.muse.data.sticker.StickerLibraryRepository
import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.tools.script.SkillEngineResult
import io.zer0.muse.tools.script.WebViewSkillEngine
import io.zer0.muse.ui.qrcode.QrCodeGenerator
import io.zer0.ai.image.ImageService
import io.zer0.ai.core.ProviderConfig
import io.zer0.muse.tools.JsSandbox

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
            sb.append("请调用 list_stickers 并传入 category 参数查看某个分类的具体表情包,然后调用 send_sticker 发送。")
            return sb.toString()
        }
        val items = repo.listStickers(category)
        if (items.isEmpty()) return context.getString(R.string.skill_sticker_list_empty)
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
        val parts = skill.implementationKotlin.split(":")
        if (parts.size < 3) return "插件工具路由格式错误: ${skill.implementationKotlin}"
        val pluginId = parts[1]
        val functionName = parts[2]
        val plugin = pluginManager?.findPlugin(pluginId)
        if (plugin == null) return "插件未安装: $pluginId"
        if (!plugin.enabled) return "插件已禁用: ${plugin.name}"
        val entryCode = pluginManager?.loadEntryCode(pluginId)
        if (entryCode.isNullOrBlank()) return "插件入口文件缺失: $pluginId"

        JsSandbox.init(context)
        val argsJson = "[" + argumentsJson.ifBlank { "{}" } + "]"
        // C-30: 传入 pluginId 作为 scopeKey,使熔断状态与 localStorage 按插件隔离,
        //   一个插件死循环超时不会熔断/影响其他插件与内置 JS 工具。
        return when (val result = WebViewSkillEngine().callFunction(entryCode, functionName, argsJson, scopeKey = pluginId)) {
            is SkillEngineResult.Success -> {
                val value = result.valueJson
                runCatching {
                    AppJson.decodeFromString<String>(value)
                }.getOrElse {
                    value.ifBlank { "null" }
                }
            }
            is SkillEngineResult.Error -> {
                if (JsSandbox.isCircuitBrokenFor(pluginId)) {
                    resultOf { pluginManager?.setEnabled(pluginId, false) }
                        .onError { msg, _ -> Logger.w("SkillMediaToolsImpl", "自动禁用插件失败: $msg") }
                    "插件已自动禁用: JS 沙盒连续超时，请稍后重试"
                } else {
                    "插件工具执行失败: ${result.message}"
                }
            }
        }
    }
}
