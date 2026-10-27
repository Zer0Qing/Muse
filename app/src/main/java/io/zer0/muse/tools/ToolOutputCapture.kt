package io.zer0.muse.tools

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.Reader
import java.util.UUID

/**
 * Keeps short command output inline and spills larger output to a file that read_file can page.
 */
internal class ToolOutputCapture(
    private val outputDirectory: File,
    filePrefix: String,
    private val inlineLimitChars: Int = TOOL_OUTPUT_INLINE_FALLBACK_CHARS,
    private val previewLimitChars: Int = TOOL_RESULT_PREVIEW_CHARS,
) : Closeable {
    private val fileName = "${filePrefix.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${UUID.randomUUID()}.txt"
    private val inlineContent = StringBuilder()
    private val preview = StringBuilder()
    private var outputFile: File? = null
    private var writer: BufferedWriter? = null
    private var completed = false

    val savedFile: File? get() = outputFile

    init {
        require(inlineLimitChars >= 0)
        require(previewLimitChars >= 0)
    }

    fun append(text: CharSequence) {
        check(!completed) { "Tool output capture is already complete" }
        if (text.isEmpty()) return
        appendPreview(text)

        if (writer == null && inlineContent.length + text.length <= inlineLimitChars) {
            inlineContent.append(text)
            return
        }
        if (writer == null) startFile()
        writer?.append(text) ?: error("Tool output file writer was not initialized")
    }

    fun finish(header: String, emptyMessage: String): String {
        check(!completed) { "Tool output capture is already complete" }
        completed = true
        writer?.close()
        writer = null

        val file = outputFile ?: return buildString {
            if (header.isNotBlank()) {
                append(header)
                if (inlineContent.isNotEmpty()) append('\n')
            }
            append(inlineContent.ifEmpty { StringBuilder(emptyMessage) })
        }
        val relativePath = "$TOOL_OUTPUTS_DIR/$fileName"
        return buildString {
            if (header.isNotBlank()) append(header).append('\n')
            append("[完整输出已保存到: ").append(file.absolutePath).append("]\n")
            append(
                "[需要完整内容时使用 read_file 分段读取: path=$relativePath, " +
                    "offset_chars=0, length_chars=$TOOL_OUTPUT_READ_PAGE_CHARS]\n\n",
            )
            append(preview)
            append("\n\n[以上是预览；请按字符游标继续读取完整输出]")
        }
    }

    override fun close() {
        if (completed) return
        completed = true
        writer?.close()
        writer = null
    }

    private fun appendPreview(text: CharSequence) {
        val remaining = previewLimitChars - preview.length
        if (remaining > 0) preview.append(text, 0, minOf(text.length, remaining))
    }

    private fun startFile() {
        if (!outputDirectory.isDirectory && !outputDirectory.mkdirs() && !outputDirectory.isDirectory) {
            throw IOException("无法创建工具输出目录: ${outputDirectory.absolutePath}")
        }
        val file = File(outputDirectory, fileName)
        val newWriter = BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8))
        try {
            newWriter.append(inlineContent)
            inlineContent.setLength(0)
            outputFile = file
            writer = newWriter
        } catch (error: Throwable) {
            runCatching { newWriter.close() }
            runCatching { file.delete() }
            throw error
        }
    }
}

internal data class ToolTextStreamCaptureResult(
    val preview: String,
    val reference: String,
    val savedFile: File?,
    val totalChars: Long,
)

internal fun captureTextStream(
    reader: Reader,
    outputDirectory: File,
    filePrefix: String,
    previewLimitChars: Int,
): ToolTextStreamCaptureResult {
    require(previewLimitChars >= 0)
    val capture = ToolOutputCapture(outputDirectory, filePrefix)
    val preview = StringBuilder(minOf(previewLimitChars, TOOL_RESULT_PREVIEW_CHARS))
    var totalChars = 0L
    reader.use {
        val buffer = CharArray(8_192)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            val chunk = String(buffer, 0, count)
            capture.append(chunk)
            totalChars += count
            val remaining = previewLimitChars - preview.length
            if (remaining > 0) preview.append(chunk, 0, minOf(chunk.length, remaining))
        }
    }
    val reference = capture.finish(header = "", emptyMessage = "")
    return ToolTextStreamCaptureResult(preview.toString(), reference, capture.savedFile, totalChars)
}

internal suspend fun captureLargeToolOutput(context: Context, filePrefix: String, output: String): String {
    if (output.length <= TOOL_OUTPUT_INLINE_FALLBACK_CHARS) return output
    return try {
        withContext(Dispatchers.IO) {
            ToolOutputCapture(File(context.filesDir, TOOL_OUTPUTS_DIR), filePrefix).apply {
                append(output)
            }.finish(header = "", emptyMessage = "")
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Exception) {
        output + "\n\n[完整输出落盘失败；本次消息仍保留完整原文: ${error.message ?: "存储失败"}]"
    }
}
