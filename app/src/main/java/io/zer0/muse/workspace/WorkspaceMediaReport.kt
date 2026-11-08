package io.zer0.muse.workspace

import java.util.Locale

/**
 * 图片回执的输入。
 *
 * 抽成数据类不是为了好看：这一堆字段本来就是同一次判定的产物（类型、尺寸、
 * 随附与否、OCR 结果），当参数递轻易出现「改了三个调用点、漏了第四个」。
 */
data class ImageReportInput(
    val display: String,
    val mimeType: String,
    /** 解码头读到的宽高；读不到传 null（那时宁可不写尺寸，也不编一个）。 */
    val dimensions: Pair<Int, Int>?,
    val sizeBytes: Long,
    /** 是否已把原图作为图片消息随附。 */
    val attached: Boolean,
    /** 未随附的原因（size 超限、拿不出 Uri 等）。 */
    val attachSkipReason: String? = null,
    /** 本机 OCR 文本；空或 null 表示没有可用文字。 */
    val ocrText: String? = null,
    /** 是否真的读入了图片内容。没读入就不能说「没识别到文字」，那是把未尝试说成已尝试。 */
    val ocrAttempted: Boolean = true,
)

/**
 * 工作区媒体回执的**文本组装**（纯函数，不碰 Android API，可单测）。
 *
 * 为什么把文案抽出来：这三段文本是模型理解「二进制文件到底给了我什么」的唯一依据，
 * 里面每一个数字都得有出处（类型来自内容嗅探、尺寸来自解码头、大小来自 stat）。
 * 散在工具执行体里写就容易出现「说了有 OCR 其实没跑」这类自相矛盾的说法。
 *
 * 三条约定：
 *  1. **先给结论行**（类型 / MIME / 尺寸 / 大小），再给内容；模型不用解析也能判断下一步。
 *  2. 随附原图与否必须写明原因。图片超过 [ATTACH_MAX_BYTES] 就不随附——工具结果里的
 *     Base64 会直接进对话历史，一张 20MB 的原图等于往 256MB 的堆上再压 27MB。
 *  3. 拿不到的东西就说拿不到（OCR 没字、转写引擎没配、时长读不出），不猜。
 */
object WorkspaceMediaReport {

    /** 随附给模型的原图大小上限。 */
    const val ATTACH_MAX_BYTES: Long = 4L * 1024 * 1024

    /** 送去转写的音频大小上限。 */
    const val AUDIO_MAX_BYTES: Long = 20L * 1024 * 1024

    /** OCR 文本进入回执的上限。 */
    const val OCR_TEXT_CAP: Int = 3_000

    /**
     * 图片回执。
     *
     * 字段含义见 [ImageReportInput]；三条约定写在该数据类上，不在这里重复一遍。
     */
    fun image(input: ImageReportInput): String = buildString {
        val (width, height) = input.dimensions ?: 0 to 0
        appendLine("image | ${input.display} | ${input.mimeType.ifBlank { "图片" }} | ${formatBytes(input.sizeBytes)}")
        if (width > 0 && height > 0) {
            appendLine("尺寸：${width}×${height}")
        }
        appendLine(
            when {
                // 不写「已随附」：工具结果会被落库，而带图那条用户消息只进本回合历史、不进数据库。
                // 写成「已随附」的话，重开会话后历史里根本没有那条消息，模型会按这句话去找一张不存在的图。
                // 真正的交付声明由那条随图消息的 caption 承担（它只在实时路径存在）。
                input.attached -> "原图已读取。"
                else -> "原图未读取：${input.attachSkipReason ?: "超出随附上限"}。"
            },
        )
        val text = input.ocrText?.trim().orEmpty()
        when {
            text.isNotEmpty() -> {
                appendLine("本机 OCR 文字（ML Kit，不联网、不外发）：")
                append(lineBody(text))
            }

            !input.ocrAttempted ->
                append("本机 OCR：未尝试（没有读入图片内容）。")

            else ->
                append("本机 OCR：没有识别到文字（可能是图形、照片或 GMS 未就绪）。")
        }
    }.trimEnd()

    /**
     * 音频回执。
     *
     * @param transcript 转写文本；null 表示没有结果
     * @param missingReason 没有结果时的原因（未配置引擎、超限、转写失败）
     */
    fun audio(
        display: String,
        mimeType: String,
        sizeBytes: Long,
        transcript: String?,
        missingReason: String? = null,
    ): String = buildString {
        appendLine("audio | $display | ${mimeType.ifBlank { "音频" }} | ${formatBytes(sizeBytes)}")
        val text = transcript?.trim().orEmpty()
        if (text.isNotEmpty()) {
            append("转写内容：")
            append(lineBody(text))
        } else {
            append("没有转写内容：${missingReason ?: "转写未返回结果"}。这段音频的内容你看不到，需要文字时请让用户先转写好。")
        }
    }.trimEnd()

    /**
     * 其他非文本文件（压缩包、PDF、可执行体、未知二进制）的拒绝回执。
     *
     * 只说事实与下一步，不编内容。PDF 特别说明界面有预览、且有 parse_pdf 工具，
     * 免得模型以为读不了就去猜正文。
     */
    fun refusal(
        display: String,
        kind: WorkspaceFileKind,
        mimeType: String,
        sizeBytes: Long,
        note: String? = null,
    ): String {
        val hint = when (kind) {
            WorkspaceFileKind.DOCUMENT -> "PDF 请改用 parse_pdf 工具取正文，本接口不解析版式。"
            WorkspaceFileKind.ARCHIVE -> "压缩包不能直接读；先解压（可用 execute_node_script / 终端），再读里面的文件。"
            WorkspaceFileKind.EXECUTABLE -> "可执行文件没有可读正文。"
            WorkspaceFileKind.VIDEO -> "视频本接口不处理；需要画面内容请让用户截图或用界面预览。"
            else -> "没有文本正文可读。"
        }
        return buildString {
            appendLine("${kind.name.lowercase(Locale.US)} | $display | ${mimeType.ifBlank { "未知类型" }} | ${formatBytes(sizeBytes)}")
            append("$hint。workspace_read 只接受文本。")
            note?.let { append("（$it）") }
        }.trimEnd()
    }

    /** 人类可读的字节数，交给模型时也用它判断量级。 */
    fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1fMB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(Locale.US, "%.1fKB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun lineBody(text: String): String {
        val capped = if (text.length <= OCR_TEXT_CAP) text else text.take(OCR_TEXT_CAP) + "…（已截断 ${text.length - OCR_TEXT_CAP} 字）"
        return "\n\"\"\"\n" + capped + "\n\"\"\""
    }
}
