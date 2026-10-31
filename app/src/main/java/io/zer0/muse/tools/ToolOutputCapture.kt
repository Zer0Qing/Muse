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
 * 命令类输出源：截断时保留尾部。
 *
 * 判据是语义（"结论在最后几行"：编译错误、测试失败、退出码），不是输出体积。
 * 集合里既有工具名（terminal_exec / execute_shell / termux_exec / device_shell），
 * 也有工具内部采集输出流时的前缀（python_stdout / node_stdout / shizuku_command）：
 * 两者共用一张表，免得同一个命令通道在采集层和编排层各自判一次方向。
 */
private val TAIL_KEEPING_OUTPUT_SOURCES = setOf(
    "terminal_exec",
    "execute_shell",
    "termux_exec",
    "device_shell",
    "shizuku_command",
    "python_stdout",
    "python_stderr",
    "node_stdout",
    "node_stderr",
    // 脚本执行的 stdout/stderr 已由上面的前缀覆盖尾部语义；这里再按工具名兜一层，
    // 是为了将来采集路径改写或另起调用方式时，方向仍不会退化成头部。冗余无害，漏了才有害。
    "execute_python_script",
    "execute_node_script",
)

/** 该输出源截断预览时保留尾部（命令类）还是保留头部（文件、搜索结果类）。 */
internal fun keepsOutputTail(sourceName: String?): Boolean =
    sourceName != null && sourceName.trim().lowercase() in TAIL_KEEPING_OUTPUT_SOURCES

/**
 * Keeps short command output inline and spills larger output to a file that read_file can page.
 *
 * 输出被截断时保留哪一段由 [keepsOutputTail] 按工具语义决定：命令类输出留尾部，
 * 文件与搜索结果类留头部。阈值不变，变的只是取哪一段。
 */
internal class ToolOutputCapture(
    private val outputDirectory: File,
    filePrefix: String,
    private val inlineLimitChars: Int = TOOL_OUTPUT_INLINE_FALLBACK_CHARS,
    private val previewLimitChars: Int = TOOL_RESULT_PREVIEW_CHARS,
    private val keepTail: Boolean = keepsOutputTail(filePrefix),
) : Closeable {
    private val fileName = "${filePrefix.replace(Regex("[^A-Za-z0-9_-]"), "_")}_${UUID.randomUUID()}.txt"
    private val inlineContent = StringBuilder()
    private val headPreview = StringBuilder()

    /** 尾部预览的滚动窗口（仅在 [keepTail] 时使用）：只保留最后 [previewLimitChars] 字。 */
    private val tailPreview = StringBuilder()
    private var totalChars = 0L
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
        totalChars += text.length

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
        val mountPath = "$TOOL_OUTPUTS_DIR:/$fileName"
        // 先取渲染后的预览，再拿它的实际长度报数：两者出自同一个字符串，
        // 不会出现「显示比提示多/少一个字」的错位。
        val previewText = renderPreview()
        return buildString {
            if (header.isNotBlank()) append(header).append('\n')
            append("[完整输出已保存到: ").append(file.absolutePath).append("]\n")
            append(
                "[需要完整内容时用 workspace_read 分段读取: path=$mountPath, " +
                    "offset_chars=0, length_chars=$TOOL_OUTPUT_READ_PAGE_CHARS；也可用 read_file 读取（path=$TOOL_OUTPUTS_DIR/$fileName）]\n\n",
            )
            append(previewText)
            append("\n\n")
            append(previewNotice(previewText.length))
        }
    }

    /**
     * 预览文本，且不留下半个代理对。
     *
     * 尾部模式丢掉开头那个孤立的低代理，头部模式丢掉结尾那个孤立的高代理：
     * 两种情况都只会在截断边界上出现一个，丢掉后 [previewNotice] 按实际长度重新报数。
     */
    private fun renderPreview(): String {
        if (!keepTail) {
            while (headPreview.isNotEmpty() && Character.isHighSurrogate(headPreview[headPreview.length - 1])) {
                headPreview.setLength(headPreview.length - 1)
            }
            return headPreview.toString()
        }
        while (tailPreview.isNotEmpty() && Character.isLowSurrogate(tailPreview[0])) {
            tailPreview.deleteCharAt(0)
        }
        return tailPreview.toString()
    }

    /** 分页提示：写明预览取自哪一端、原文总量与未显示的字符数，模型才能判断要不要继续读。 */
    private fun previewNotice(shownChars: Int): String {
        val omitted = (totalChars - shownChars).coerceAtLeast(0L)
        return if (keepTail) {
            "[以上是输出末尾 $shownChars 字的预览；原文共 $totalChars 字，开头 $omitted 字未显示；" +
                "需要时用 read_file 从 offset_chars=0 读取]"
        } else {
            "[以上是输出开头 $shownChars 字的预览；原文共 $totalChars 字，其余 $omitted 字未显示；" +
                "需要时用 read_file 按字符游标继续读取]"
        }
    }

    override fun close() {
        if (completed) return
        completed = true
        writer?.close()
        writer = null
    }

    private fun appendPreview(text: CharSequence) {
        if (!keepTail) {
            val remaining = previewLimitChars - headPreview.length
            if (remaining > 0) headPreview.append(text, 0, minOf(text.length, remaining))
            return
        }
        tailPreview.append(text)
        // 超出上限就丢掉最前面的一段：命令类输出只留最后几行才有意义。
        val overflow = tailPreview.length - previewLimitChars
        if (overflow > 0) tailPreview.delete(0, overflow)
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
    // 这里是"解析用"的前缀预览（HTML 解析、首段取样），所以总是取开头；
    // 给模型看的分页预览由 capture.finish 按输出源方向生成。
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
