package io.zer0.muse.data.sharing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.FileProvider
import io.zer0.common.AppJson
import io.zer0.common.Logger
import io.zer0.muse.util.ShareIntentHelper
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

    // v2.x: 卡片配色 — 与 UiTheme 月桂绿体系对齐(Canvas 绘制读不到 Compose 主题,取同源色值)
    private val LAUREL_DEEP = android.graphics.Color.parseColor("#0F3D2A")
    private val LAUREL_MAIN = android.graphics.Color.parseColor("#2A7A55")
    private val LAUREL_BRIGHT = android.graphics.Color.parseColor("#4A9F70")
    private val LAUREL_PALE = android.graphics.Color.parseColor("#D4EBDD")
    private val WARM_WHITE = android.graphics.Color.parseColor("#FAFAF8")
    private val INK = android.graphics.Color.parseColor("#1A1A1A")
    private val SUBTLE = android.graphics.Color.parseColor("#8E8E93")
    private val STAR_GOLD = android.graphics.Color.parseColor("#F5C842")

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
     * v2.x: 生成角色卡片 PNG(月桂绿渐变冠部 + 圆形头像 + 名字/描述 + 品牌水印)。
     *
     * 头像优先使用 [ShareableAssistant.avatarUrl] 指向的本地图片(圆形裁剪);
     * 无图片时用 emoji 居中绘制。卡片规格 1080×1350(3:4),适合社交分享。
     *
     * @return 生成的文件 URI, 用于分享
     */
    fun generateCardPng(context: Context, assistant: ShareableAssistant, width: Int = 1080, height: Int = 1350): Uri? {
        return try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val w = width.toFloat()
            val h = height.toFloat()

            // ── 1. 冠部:月桂绿渐变(深→亮) ──
            val topHeight = h * 0.46f
            val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, 0f, 0f, topHeight,
                    intArrayOf(LAUREL_DEEP, LAUREL_MAIN, LAUREL_BRIGHT),
                    floatArrayOf(0f, 0.55f, 1f),
                    Shader.TileMode.CLAMP,
                )
            }
            canvas.drawRect(0f, 0f, w, topHeight, topPaint)

            // ── 2. 主体:暖白 ──
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WARM_WHITE }
            canvas.drawRect(0f, topHeight, w, h, bodyPaint)

            // ── 3. 头像(圆形,骑在分界线上) ──
            val cx = w / 2f
            val avatarR = 180f
            val cy = topHeight + 12f
            canvas.drawCircle(cx, cy, avatarR + 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WARM_WHITE })
            canvas.drawCircle(cx, cy, avatarR, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LAUREL_PALE })
            val avatarBitmap = assistant.avatarUrl
                .takeIf { it.isNotBlank() }
                ?.let { path -> File(path).takeIf { it.exists() } }
                ?.let { file -> BitmapFactory.decodeFile(file.absolutePath) }
            if (avatarBitmap != null) {
                canvas.save()
                canvas.clipPath(Path().apply { addCircle(cx, cy, avatarR, Path.Direction.CW) })
                val scale = maxOf(avatarR * 2f / avatarBitmap.width, avatarR * 2f / avatarBitmap.height)
                val dw = avatarBitmap.width * scale
                val dh = avatarBitmap.height * scale
                canvas.drawBitmap(
                    avatarBitmap,
                    null,
                    RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                )
                canvas.restore()
                avatarBitmap.recycle()
            } else {
                val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = avatarR * 1.3f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(
                    assistant.emoji.ifBlank { "\uD83E\uDD16" },
                    cx,
                    cy + avatarR * 0.48f,
                    emojiPaint,
                )
            }

            // ── 4. 名字与描述 ──
            val namePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = INK
                textSize = 68f
                typeface = Typeface.DEFAULT_BOLD
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText(assistant.name.takeCodePoints(24), cx, cy + avatarR + 150f, namePaint)

            val desc = assistant.description.trim().takeCodePoints(80)
            if (desc.isNotEmpty()) {
                val descPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = SUBTLE
                    textSize = 30f
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawText(desc, cx, cy + avatarR + 220f, descPaint)
            }

            // ── 5. 冠部点缀(金色星点) ──
            val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = STAR_GOLD }
            canvas.drawCircle(w * 0.13f, h * 0.075f, 9f, starPaint)
            canvas.drawCircle(w * 0.87f, h * 0.13f, 5f, starPaint)
            canvas.drawCircle(w * 0.78f, h * 0.055f, 4f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = LAUREL_PALE })

            // ── 6. 底部水印 ──
            val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = SUBTLE
                textSize = 22f
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("Made with Muse · museai.ltd", cx, h - 52f, markPaint)

            // ── 保存 ──
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
        ShareIntentHelper.startChooserSafely(context, intent, "Share Character")
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
        ShareIntentHelper.startChooserSafely(context, intent, "Share Character JSON")
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
