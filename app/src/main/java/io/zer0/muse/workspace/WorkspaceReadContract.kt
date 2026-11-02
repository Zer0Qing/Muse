package io.zer0.muse.workspace

/**
 * 文件读取的**结果合同**（对比清单 P2-01）。
 *
 * 原来的问题：`workspace_read` 返回的正文看不出"这是哪一版、读的是哪一段"。
 * 分页本身在 [io.zer0.muse.tools.ToolTextFilePager] 里已有续读提示（"还有后续内容…"），
 * 缺的是**版本**：同一份文件改过之后再读，模型无从判断拿到的是不是旧内容。
 *
 * 所以这里只加一行头信息，不重复 pager 已有的续读提示：
 *
 * ```
 * --- notes.md | revision=1200:1699999999 | chars 0..4000 of 12000 ---
 * ```
 *
 * `revision` 由"字节数 + 修改时间"构成：不为此多读一遍文件（大文件读一遍的代价比这高得多），
 * 而同大小同修改时间的内容变化在实践中可以忽略。
 */
object WorkspaceReadContract {

    /** 总字符数未知时的哨兵值（分页读取时我们不去整读文件）。 */
    const val TOTAL_UNKNOWN = -1

    data class Meta(
        val displayPath: String,
        val revision: String,
        val offsetChars: Int,
        val returnedChars: Int,
        val totalChars: Int = TOTAL_UNKNOWN,
    ) {
        val endExclusive: Int get() = offsetChars + returnedChars

        val totalKnown: Boolean get() = totalChars >= 0
    }

    /** 正文前的说明行。 */
    fun header(meta: Meta): String {
        val scope = if (meta.totalKnown) {
            "chars ${meta.offsetChars}..${meta.endExclusive} of ${meta.totalChars}"
        } else {
            "chars ${meta.offsetChars}..${meta.endExclusive}"
        }
        val revision = meta.revision.takeIf { it.isNotBlank() }?.let { " | revision=$it" }.orEmpty()
        return "--- ${meta.displayPath}$revision | $scope ---"
    }

    /** 组合成最终返回给模型的文本。续读提示由 pager 追加在正文之后。 */
    fun render(meta: Meta, content: String): String = header(meta) + "\n" + content
}
