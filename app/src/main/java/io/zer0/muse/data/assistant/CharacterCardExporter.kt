package io.zer0.muse.data.assistant

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * SillyTavern 角色卡导出器。
 *
 * 支持两种输出:
 *  - PNG: 把 JSON base64 编码后写入 PNG tEXt chunk (keyword="chara")
 *  - JSON: 直接写 V2 角色卡 JSON
 *
 * PNG 导出策略:
 *  - 若 [avatar] 非空,用其作为 PNG 载体 (compress 为 PNG 字节后注入 tEXt)
 *  - 若 [avatar] 为空,生成 1x1 透明 PNG 作为载体 (仅承载元数据,无可视图)
 *
 * PNG chunk 注入手动实现 (见 [PngChunkUtil]),不新增依赖。
 *
 * 参考实现:
 *  - rikkahub AssistantImporter.kt
 *  - openhanako character-cards/service.ts
 */
object CharacterCardExporter {

    private const val TAG = "CharacterCardExporter"

    /**
     * 导出为 SillyTavern PNG 角色卡。
     *
     * @param context 用于 ContentResolver 写 destUri
     * @param assistant 源助手
     * @param avatar 可选头像 Bitmap;为 null 时用 1x1 透明 PNG 作载体
     * @param destUri 目标 URI (SAF CreateDocument 返回)
     */
    suspend fun exportToPng(
        context: Context,
        assistant: AssistantEntity,
        avatar: Bitmap?,
        destUri: Uri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val card = SillyTavernCardV2.fromAssistantEntity(assistant)
            val json = AppJson.encodeToString(SillyTavernCardV2.serializer(), card)
            // NO_WRAP: 不插入换行, 保证 tEXt chunk 单段 text
            val base64 = Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            // 生成 PNG 载体字节 (头像 or 1x1 透明)
            val pngBytes = encodePngBytes(avatar)
            // 注入 tEXt chunk (插到 IHDR 之后)
            val finalBytes = PngChunkUtil.writeTextChunk(pngBytes, "chara", base64)

            context.contentResolver.openOutputStream(destUri)?.use { os ->
                os.write(finalBytes)
                os.flush()
            } ?: error("无法写入目标 URI")
        }.onFailure { Logger.w(TAG, "exportToPng failed: ${it.message}") }
    }

    /**
     * 导出为 SillyTavern JSON 角色卡 (V2 结构)。
     */
    suspend fun exportToJson(
        context: Context,
        assistant: AssistantEntity,
        destUri: Uri,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val card = SillyTavernCardV2.fromAssistantEntity(assistant)
            val json = AppJson.encodeToString(SillyTavernCardV2.serializer(), card)
            context.contentResolver.openOutputStream(destUri)?.use { os ->
                os.write(json.toByteArray(Charsets.UTF_8))
                os.flush()
            } ?: error("无法写入目标 URI")
        }.onFailure { Logger.w(TAG, "exportToJson failed: ${it.message}") }
    }

    // ── 内部工具 ──

    /** 把 Bitmap 编码为 PNG 字节;bitmap 为 null 时返回 1x1 透明 PNG。 */
    private fun encodePngBytes(bitmap: Bitmap?): ByteArray {
        val bmp = bitmap ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
