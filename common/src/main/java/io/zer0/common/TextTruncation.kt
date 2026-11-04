package io.zer0.common

import kotlin.math.max
import kotlin.math.min

/**
 * 文本截断工具。
 *
 * ## 为什么需要"保头部 + 保尾部"
 *
 * 只留头部的截断（`text.take(n)`）对工具结果和报错是**丢关键信息**：日志的错误行、
 * 命令的输出结论、代码的返回语句往往在末尾。压缩上下文时按单条截断，如果只留头部，
 * 模型看到的永远是"调用开始了"却看不到"结果是什么"。
 *
 * ## 长度按 **UTF-16 char 数**算（不是字节）
 *
 * [maxLength] 与返回的 `originalLength` / `omittedLength` 都是 `String.length` 口径，
 * 也就是 UTF-16 code unit 数：一个 emoji 算 2。原先这段写的是"按**字节**切分"，
 * 与实现和参数文档都不符 —— 这种注释会让人以为 4096 就是 4KB 文本，
 * 拿它去卡真正的字节上限（网络帧、DB 列、通知）就会超。（本会话已被过期注释骗过一次。）
 *
 * ## 代理对安全
 *
 * 切分点会对齐代理对：按 `Char` 下标直接切会在 emoji / 部分 CJK 扩展区中间断开，
 * 产生孤立代理字符，下游 JSON 序列化直接失败。
 *
 * ## 保证：截断后的文本长度 ≤ `maxLength`
 *
 * 省略标记自己也要占长度，所以先给标记留出预算再分头尾。这条保证之前**是破的**：
 * [tail] 保留 `maxLength` 个字符后又拼上标记，每次必然超；[headTail] 在小预算下也超。
 * 而 `preview(text, limit)` 这类入口的 limit 由调用方任意给 —— 传 120 拿到 139 是真实的。
 * 标记放不下时退化为**不加标记的纯截断**：宁可少一句说明，也不交付比上限更长的文本。
 */
object TextTruncation {

    /** 默认：头部保留比例（其余留给尾部）。 */
    private const val DEFAULT_HEAD_RATIO = 0.4

    /** 默认：尾部保留比例。 */
    private const val DEFAULT_TAIL_RATIO = 0.4

    /** 截断结果。[truncated]=false 表示原文未超限、原样返回。 */
    data class Result(
        val text: String,
        val truncated: Boolean,
        val originalLength: Int,
        val omittedLength: Int,
    )

    /**
     * 超过 [maxLength] 时保留头尾、中间以省略标记替代。
     *
     * @param maxLength 触发截断的长度上限（按字符计；内部按 UTF-8 字节安全切分）
     * @param marker 省略标记的生成方式（默认给出省略了多少字符，便于模型判断信息量）
     */
    fun headTail(text: String, maxLength: Int, marker: (Int, Int) -> String = ::defaultMarker): Result {
        return if (maxLength <= 0 || text.length <= maxLength) {
            Result(text, truncated = false, originalLength = text.length, omittedLength = 0)
        } else {
            // 标记自身占长度，且标记里的数字位数随原文规模增长：
            // 省略量最大可以是"整段原文"，所以按 text.length 估才是上界。
            // （以前按 maxLength 估，遇到原文远长于上限时数字位数更多，结果就超了上限：
            //  上限 41 拿到 42 —— 由 TextTruncationTest 的"永不超过上限"循环测钉住。）
            val reserve = marker(text.length, text.length).length
            val budget = maxLength - reserve
            if (budget < 2) {
                // 预算小到连标记都放不下：退化为纯头部截断。
                // 这里不硬塞标记，因为"不超限"是调用方唯一能依赖的契约。
                val only = text.takeSurrogateSafe(maxLength)
                return Result(only, truncated = true, originalLength = text.length, omittedLength = text.length - only.length)
            }
            val headLength = max(1, (budget * DEFAULT_HEAD_RATIO).toInt())
            val tailLength = max(1, (budget * DEFAULT_TAIL_RATIO).toInt())
            val head = text.takeSurrogateSafe(headLength)
            val tail = text.takeLastSurrogateSafe(tailLength)
            val omitted = text.length - head.length - tail.length
            if (omitted <= 0) {
                // 上限极小导致头尾重叠：退化为纯头部截断，至少不产生重复内容
                val only = text.takeSurrogateSafe(maxLength)
                Result(only, truncated = true, text.length, text.length - only.length)
            } else {
                Result(
                    text = head + marker(omitted, text.length) + tail,
                    truncated = true,
                    originalLength = text.length,
                    omittedLength = omitted,
                )
            }
        }
    }

    /** 便捷入口：只要截断后的文本。 */
    fun headTailText(text: String, maxLength: Int): String = headTail(text, maxLength).text

    /**
     * 超过 [maxLength] 时只保留尾部，开头以省略标记替代。
     *
     * 用于「结论在最后」的内容：命令输出、构建日志、测试报告。这类文本开头是进度日志，
     * 真正要看的是最后几行的报错、失败用例与退出状态；按头部截断等于留住了最不重要的部分。
     *
     * @param maxLength 触发截断的长度上限（按字符计；切分不破坏代理对）
     */
    fun tail(text: String, maxLength: Int, marker: (Int, Int) -> String = ::defaultTailMarker): Result {
        if (maxLength <= 0 || text.length <= maxLength) {
            return Result(text, truncated = false, originalLength = text.length, omittedLength = 0)
        }
        // 原实现是 takeLastSurrogateSafe(maxLength) 再拼标记 —— 结果必然比上限长
        // （标记约 50 字），而 ToolOrchestrator 正是拿它做工具输出预览。
        val reserve = marker(text.length, text.length).length
        val budget = maxLength - reserve
        if (budget < 1) {
            val only = text.takeLastSurrogateSafe(maxLength)
            return Result(
                text = only,
                truncated = true,
                originalLength = text.length,
                omittedLength = text.length - only.length,
            )
        }
        val kept = text.takeLastSurrogateSafe(budget)
        val omitted = text.length - kept.length
        return Result(
            text = marker(omitted, text.length) + kept,
            truncated = true,
            originalLength = text.length,
            omittedLength = omitted,
        )
    }

    /** 便捷入口：只要保留尾部后的文本。 */
    fun tailText(text: String, maxLength: Int): String = tail(text, maxLength).text

    /** 默认省略标记：写明省略量与原文长度，避免模型误以为看到的就是全部。 */
    private fun defaultMarker(omitted: Int, original: Int): String = "\n\n[... 已省略 $omitted 字（原文 $original 字）...]\n\n"

    /** 尾部截断的省略标记：省略的是开头，标记放在最前，保留的尾部紧随其后。 */
    private fun defaultTailMarker(omitted: Int, original: Int): String = "[... 已省略开头 $omitted 字（原文 $original 字），以下为末尾部分 ...]\n\n"

    /**
     * 从头取 [count] 个字符，且不切断代理对（高位代理后紧跟低位代理才成对）。
     */
    private fun String.takeSurrogateSafe(count: Int): String {
        if (count >= length) return this
        val end = if (count > 0 && Character.isHighSurrogate(this[count - 1])) count - 1 else count
        return substring(0, min(end, length))
    }

    /**
     * 从尾取 [count] 个字符，且不从代理对中间开始。
     */
    private fun String.takeLastSurrogateSafe(count: Int): String {
        if (count >= length) return this
        var start = length - count
        if (start in 1 until length && Character.isLowSurrogate(this[start])) {
            start += 1
        }
        return substring(min(start, length))
    }
}
