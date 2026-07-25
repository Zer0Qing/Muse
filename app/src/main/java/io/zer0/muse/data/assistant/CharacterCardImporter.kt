package io.zer0.muse.data.assistant

import android.content.Context
import android.net.Uri
import android.util.Base64
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream

/**
 * SillyTavern 角色卡导入器。
 *
 * 支持两种来源:
 *  - PNG 图片: tEXt chunk (keyword="chara") 存储 base64 编码的 JSON
 *  - JSON 文件: 直接解析为 V1/V2 角色卡
 *
 * PNG chunk 解析手动实现 (见 [PngChunkUtil]),不引入第三方 PNG 库。
 *
 * V1/V2 自动识别:JSON 顶层含 "spec" 字段视为 V2,否则按 V1 扁平结构解析。
 *
 * 不新增依赖,仅用 android.util.Base64 + ContentResolver。
 *
 * 参考实现:
 *  - rikkahub AssistantImporter.kt
 *  - openhanako character-cards/service.ts
 */
object CharacterCardImporter {

    private const val TAG = "CharacterCardImporter"

    // 限制单文件 20MB,防止恶意大文件导致 OOM (角色卡通常 < 5MB)
    private const val MAX_FILE_BYTES = 20L * 1024 * 1024

    /**
     * 从 PNG 图片元数据导入角色卡。
     *
     * PNG tEXt chunk 格式: length(4) + "tEXt"(4) + keyword + 0x00 + text + CRC(4)
     * SillyTavern 约定 keyword="chara", text=base64(JSON)。
     *
     * @return 成功返回包含角色卡数据的 [AssistantEntity] (id 为空, 调用方需生成 id 并 upsert);
     *         失败返回 Result.failure
     */
    suspend fun importFromPng(context: Context, uri: Uri): Result<AssistantEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readUriWithLimit(context, uri) ?: error("无法读取文件")
                val base64Text = PngChunkUtil.readTextChunk(bytes, "chara")
                    ?: error("PNG 中未找到 chara 元数据 (非 SillyTavern 角色卡)")
                val json = String(Base64.decode(base64Text, Base64.DEFAULT), Charsets.UTF_8)
                val card = parseCardJson(json)
                card.toAssistantEntity()
            }.onFailure { Logger.w(TAG, "importFromPng failed: ${it.message}") }
        }

    /**
     * 从 JSON 文件导入角色卡 (V1 扁平结构或 V2 包裹结构均可)。
     */
    suspend fun importFromJson(context: Context, uri: Uri): Result<AssistantEntity> =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = readUriWithLimit(context, uri) ?: error("无法读取文件")
                val json = String(bytes, Charsets.UTF_8)
                val card = parseCardJson(json)
                card.toAssistantEntity()
            }.onFailure { Logger.w(TAG, "importFromJson failed: ${it.message}") }
        }

    /**
     * 自动识别格式并导入。
     *
     * 分派策略:
     *  - MIME 含 "png" → 走 [importFromPng]
     *  - MIME 含 "json" → 走 [importFromJson]
     *  - MIME 未知 → 先试 PNG, 失败再试 JSON (兼容未正确标注 MIME 的来源)
     */
    suspend fun importAuto(context: Context, uri: Uri): Result<AssistantEntity> =
        withContext(Dispatchers.IO) {
            val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull() ?: ""
            when {
                mime.contains("png", ignoreCase = true) -> importFromPng(context, uri)
                mime.contains("json", ignoreCase = true) -> importFromJson(context, uri)
                else -> {
                    // MIME 未知: 先试 PNG, 失败回退 JSON
                    val pngResult = importFromPng(context, uri)
                    if (pngResult.isSuccess) pngResult else importFromJson(context, uri)
                }
            }
        }

    // ── 内部工具 ──

    /** 读 URI 全部字节,超过 [MAX_FILE_BYTES] 拒绝,返回 null。 */
    private fun readUriWithLimit(context: Context, uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8 * 1024)
                var read: Int
                var total = 0L
                while (input.read(buf).also { read = it } > 0) {
                    total += read
                    if (total > MAX_FILE_BYTES) {
                        Logger.w(TAG, "文件超过 ${MAX_FILE_BYTES / 1024 / 1024}MB 上限, 拒绝导入")
                        return null
                    }
                    out.write(buf, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "readUri failed: ${e.message}")
            null
        }
    }

    /** 解析 JSON 为 V2 卡片;自动识别 V1 扁平结构并包裹为 V2。 */
    private fun parseCardJson(json: String): SillyTavernCardV2 {
        val element = AppJson.parseToJsonElement(json)
        val obj = element.jsonObject
        return if (obj["spec"] != null) {
            // V2: 顶层含 spec 字段,直接解码
            AppJson.decodeFromString(SillyTavernCardV2.serializer(), json)
        } else {
            // V1: 扁平结构,把 flat 字段当 data 包成 V2
            val data = AppJson.decodeFromString(SillyTavernCardData.serializer(), json)
            SillyTavernCardV2(data = data)
        }
    }
}
