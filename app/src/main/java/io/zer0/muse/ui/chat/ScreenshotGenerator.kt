package io.zer0.muse.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Phase 3 3C: 对话截图生成器 — 将聊天消息渲染为可分享的 PNG 图片。
 *
 * 用法:
 * 1. 选择消息范围
 * 2. 调用 [generateScreenshot] 生成 Bitmap
 * 3. 调用 [shareScreenshot] 通过 Intent.SEND 分享
 *
 * 尺寸:
 * - Story: 1080x1920
 * - Square: 1080x1080
 */
object ScreenshotGenerator {

    enum class Size(val width: Int, val height: Int) {
        STORY(1080, 1920),
        SQUARE(1080, 1080),
    }

    /**
     * 生成对话截图并保存到应用缓存目录。
     *
     * @param context 应用上下文
     * @param messages 消息内容列表 (角色 + 内容)
     * @param assistantName 助手名称
     * @param size 截图尺寸
     * @return 保存后的文件 URI, 用于分享
     */
    fun generateAndSave(
        context: Context,
        messages: List<ScreenshotMessage>,
        assistantName: String,
        size: Size = Size.STORY,
    ): Uri? {
        return try {
            val bitmap = renderMessages(messages, assistantName, size)
            val file = File(context.cacheDir, "muse_screenshot_${System.currentTimeMillis()}.png")
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
            null
        }
    }

    /**
     * 分享截图通过 Intent.SEND。
     */
    fun shareScreenshot(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Screenshot"))
    }

    /**
     * 将消息列表渲染为 Bitmap。
     *
     * 由于 Compose Canvas 渲染需要在 Composable 上下文中进行,
     * 此方法使用 Android Canvas 进行离屏渲染。
     */
    private fun renderMessages(
        messages: List<ScreenshotMessage>,
        assistantName: String,
        size: Size,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        // 背景
        canvas.drawColor(android.graphics.Color.parseColor("#1A1A2E"))

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 36f
            color = android.graphics.Color.WHITE
        }

        // 品牌标题
        paint.textSize = 48f
        paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
        canvas.drawText("Muse", 60f, 100f, paint)

        // 助手名称
        paint.textSize = 32f
        paint.color = android.graphics.Color.parseColor("#9B8EC4")
        canvas.drawText(assistantName, 60f, 150f, paint)

        // 分隔线
        paint.color = android.graphics.Color.parseColor("#333355")
        canvas.drawLine(60f, 170f, size.width - 60f, 170f, paint)

        // 消息内容
        paint.textSize = 32f
        paint.color = android.graphics.Color.WHITE
        var y = 220f
        val maxWidth = size.width - 120f

        messages.forEach { msg ->
            // 角色标签
            paint.textSize = 24f
            paint.color = if (msg.isUser) {
                android.graphics.Color.parseColor("#C7C7CC")
            } else {
                android.graphics.Color.parseColor("#9B8EC4")
            }
            val roleLabel = if (msg.isUser) "You" else assistantName
            canvas.drawText(roleLabel, 60f, y, paint)
            y += 30f

            // 消息内容 (简单换行处理)
            paint.textSize = 32f
            paint.color = android.graphics.Color.WHITE
            val lines = msg.content.chunked((maxWidth / 16).toInt())  // 粗略估算每行字符数
            lines.forEach { line ->
                if (y > size.height - 100f) return@forEach
                canvas.drawText(line, 60f, y, paint)
                y += 42f
            }
            y += 20f
        }

        // 水印
        paint.textSize = 20f
        paint.color = android.graphics.Color.parseColor("#555577")
        canvas.drawText("Made with Muse", size.width - 280f, size.height - 40f, paint)

        return bitmap
    }

    data class ScreenshotMessage(
        val isUser: Boolean,
        val content: String,
    )
}
