package io.zer0.muse.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 「请用户选一个文件」的请求桥。
 *
 * ## 为什么需要它
 *
 * 助手自审第 3 条：「文件只限沙盒 filesDir，读不了用户真正想让我看的外部文件。」
 *
 * 技术障碍不是读文件，而是**授权**：Android 的 SAF 要求由用户亲自在系统选择器里点选，
 * App（更不用说模型）不能自己指定任意路径。所以模型无法「直接读 /sdcard/xxx」，
 * 只能发起一个请求，等用户在界面上选完再把内容交回来。
 *
 * ## 流程
 *
 * 工具调用 → [request] 挂起 → 状态流发布待处理请求 → UI 弹系统选择器
 * → 用户选完（或取消）→ UI 调 [deliver] → 工具拿到结果继续。
 *
 * 与审批卡同一个思路：工具侧挂起，UI 侧决定。区别是审批只要「同意/拒绝」，
 * 这里要带回一个真实文件的内容。
 *
 * ## 超时
 *
 * 用户可能一直不选（切走了、忘了）。超时后返回「未选择」而不是无限挂起 ——
 * 一次工具调用不该永远占住对话。
 */
object FileAccessRequestBridge {

    /** 一次请求。 */
    data class Request(
        val id: String,
        /** 给用户看的说明：为什么要这个文件。 */
        val reason: String,
        /** MIME 过滤（如 text 类）；空表示不限。 */
        val mimeTypes: List<String> = emptyList(),
        /** 用户选择结果；null 表示还没选。 */
        val result: FileAccessResult? = null,
    )

    /** 用户选择的结果。 */
    sealed interface FileAccessResult {
        /** 用户选了文件。 */
        data class Picked(
            /** 展示用文件名。 */
            val displayName: String,
            /** 文件内容（已按上限截断）。 */
            val content: String,
            /** 原始大小（截断前）。 */
            val originalSize: Long,
            /** 是否因超限被截断。 */
            val truncated: Boolean,
        ) : FileAccessResult

        /** 用户取消，或超时。 */
        data class Dismissed(val reason: String) : FileAccessResult
    }

    /** 当前待处理请求（UI 观察这个）。 */
    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    /** 等待中的 deferred，按请求 id 索引。 */
    private val waiters = java.util.concurrent.ConcurrentHashMap<String, CompletableDeferred<FileAccessResult>>()

    /** 单个文件读取上限：超过就截断并告知，而不是拒绝（用户可能只想看开头）。 */
    const val MAX_CONTENT_CHARS = 200_000

    /**
     * 用户在系统选择器里点了返回（未选任何文件）时的结果文案。
     *
     * 定义在工具包：它会经 [FileAccessResult.reason] 流进模型回执，是模型面文本，
     * 不该写进 ui/（硬编码中文门禁按目录扫描，会误报为未本地化的界面文案）。
     */
    const val USER_CANCELLED_PICK = "用户取消了选择"

    /** 等用户选择的超时。 */
    private const val PICK_TIMEOUT_MS = 120_000L

    /**
     * 发起请求并等待结果。
     *
     * @return 用户选择的结果；超时返回 [FileAccessResult.Dismissed]。
     */
    suspend fun request(reason: String, mimeTypes: List<String> = emptyList()): FileAccessResult {
        val id = "fa_${System.currentTimeMillis().toString(36)}"
        val deferred = CompletableDeferred<FileAccessResult>()
        waiters[id] = deferred
        _pending.value = Request(id = id, reason = reason, mimeTypes = mimeTypes)
        val result =
            withTimeoutOrNull(PICK_TIMEOUT_MS) { deferred.await() }
                ?: FileAccessResult.Dismissed("用户没有在 ${PICK_TIMEOUT_MS / 1000} 秒内选择文件")
        waiters.remove(id)
        _pending.value = null
        return result
    }

    /** UI 侧：用户选完了（或取消），把结果交回。 */
    fun deliver(id: String, result: FileAccessResult) {
        waiters[id]?.complete(result)
    }

    /** UI 侧：主动放弃（如页面销毁）。 */
    fun cancel(id: String, reason: String = "请求已取消") {
        waiters[id]?.complete(FileAccessResult.Dismissed(reason))
    }

    /** UI 侧：用户在系统选择器点了返回（未选文件）。文案归工具包，避免 ui/ 出现模型面中文。 */
    fun deliverNoPick(id: String) {
        cancel(id, USER_CANCELLED_PICK)
    }

    /**
     * 读取用户通过 SAF 选中的文件，产出 [FileAccessResult]，供 read_user_file 工具使用。
     *
     * 放在工具包而不是 UI 层：这里产出的文案（取消/空文件/读取失败原因）会经
     * [FileAccessResult] 流进工具回执、最终给模型读，是模型面文本，不是界面文案 ——
     * 不该被 stringResource 本地化（会改变模型行为），也不该被 ui/ 目录的硬编码门禁计入。
     *
     * 只有 UI 拿到的是用户亲手选的 URI，工具侧不应也无法自行构造 —— 这正是 SAF 的授权语义，
     * 所以入口仍由 UI 调用，逻辑归属工具包。读取失败按「没给成」处理，不抛异常打断工具循环。
     */
    fun readPickedFile(context: android.content.Context, uri: android.net.Uri): FileAccessResult {
        val maxChars = MAX_CONTENT_CHARS
        return runCatching {
            val resolver = context.contentResolver
            val displayName = (uri.lastPathSegment ?: "未命名文件").substringAfterLast('/')
            val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            val text =
                resolver.openInputStream(uri)?.use { input ->
                    // 按字符上限读，避免把超大文件整个拉进内存。
                    val reader = input.bufferedReader()
                    val buffer = CharArray(maxChars + 1)
                    var total = 0
                    while (total < buffer.size) {
                        val read = reader.read(buffer, total, buffer.size - total)
                        if (read <= 0) break
                        total += read
                    }
                    String(buffer, 0, total)
                } ?: ""
            if (text.isEmpty()) {
                FileAccessResult.Dismissed("文件为空或无法读取")
            } else {
                FileAccessResult.Picked(
                    displayName = displayName,
                    content = text.take(maxChars),
                    originalSize = if (size > 0) size else text.length.toLong(),
                    truncated = text.length > maxChars,
                )
            }
        }.getOrElse {
            FileAccessResult.Dismissed("读取失败：${it.message ?: it.javaClass.simpleName}")
        }
    }

    /** 测试用：清空所有等待。 */
    internal fun resetForTest() {
        waiters.values.forEach { it.complete(FileAccessResult.Dismissed("reset")) }
        waiters.clear()
        _pending.value = null
    }
}
