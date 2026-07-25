package io.zer0.muse.data.sharing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.common.Logger
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 5 5D: 角色分享 — 导出助手为 JSON 文件 + 生成角色卡片 PNG。
 *
 * JSON 格式: 包含助手名称/描述/systemPrompt/温度等配置
 * PNG 卡片: 头像 + 名称 + 标语 + 水印
 */
object CharacterSharer {

    private const val TAG = "CharacterShare"

    /**
     * 导出助手为 JSON 字符串(可保存为文件分享)。
     */
    fun exportToJson(assistant: ShareableAssistant): String {
        return AppJson.encodeToString(ShareableAssistant.serializer(), assistant)
    }

    /**
     * 从 JSON 导入助手。
     */
    fun importFromJson(json: String): ShareableAssistant? {
        return try {
            AppJson.decodeFromString(ShareableAssistant.serializer(), json)
        } catch (e: Exception) {
            Logger.w(TAG, "Import failed: ${e.message}")
            null
        }
    }

    /**
     * 生成角色卡片 PNG(头像 + 名称 + 标语 + 水印)。
     *
     * @return 生成的文件 URI, 用于分享
     */
    fun generateCardPng(
        context: Context,
        assistant: ShareableAssistant,
        width: Int = 1080,
        height: Int = 1350,
    ): Uri? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // 背景
            canvas.drawColor(android.graphics.Color.parseColor("#1A1A2E"))

            val paint = Paint().apply {
                isAntiAlias = true
                color = android.graphics.Color.WHITE
            }

            // 品牌
            paint.textSize = 36f
            paint.typeface = Typeface.DEFAULT_BOLD
            canvas.drawText("Muse", 60f, 80f, paint)

            // 头像 emoji (大号居中)
            paint.textSize = 200f
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText(assistant.emoji.ifBlank { "\uD83E\uDD16" }, width / 2f, 400f, paint)

            // 名称
            paint.textSize = 64f
            paint.typeface = Typeface.DEFAULT_BOLD
            paint.color = android.graphics.Color.WHITE
            canvas.drawText(assistant.name, width / 2f, 550f, paint)

            // 标语/描述
            paint.textSize = 32f
            paint.typeface = Typeface.DEFAULT
            paint.color = android.graphics.Color.parseColor("#9B8EC4")
            val desc = assistant.description.take(60)
            canvas.drawText(desc, width / 2f, 620f, paint)

            // 分隔线
            paint.color = android.graphics.Color.parseColor("#333355")
            canvas.drawLine(100f, 680f, width - 100f, 680f, paint)

            // 系统提示(前 3 行)
            paint.textSize = 24f
            paint.color = android.graphics.Color.parseColor("#AAAAAA")
            paint.textAlign = Paint.Align.LEFT
            val promptLines = assistant.systemPrompt.take(150).split("\n").take(3)
            var y = 730f
            promptLines.forEach { line ->
                canvas.drawText(line.take(40), 80f, y, paint)
                y += 36f
            }

            // 水印
            paint.textSize = 20f
            paint.color = android.graphics.Color.parseColor("#555577")
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText("Made with Muse", width - 60f, height - 40f, paint)

            // 保存
            val file = File(context.cacheDir, "character_card_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file,
            )
        } catch (e: Exception) {
            Logger.w(TAG, "Card generation failed: ${e.message}")
            null
        }
    }

    /**
     * 分享角色卡片通过 Intent.SEND。
     */
    fun shareCard(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Character"))
    }

    /**
     * 分享 JSON 文件。
     */
    fun shareJson(context: Context, json: String, fileName: String = "character.json") {
        val file = File(context.cacheDir, fileName)
        file.writeText(json)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Character JSON"))
    }
}

/**
 * 可分享的助手数据(导出/导入用)。
 */
@Serializable
data class ShareableAssistant(
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val maxTokens: Int = 2048,
    val emoji: String = "\uD83E\uDD16",
    val avatarUrl: String = "",
    val tags: List<String> = emptyList(),
    val version: Int = 1,
)
