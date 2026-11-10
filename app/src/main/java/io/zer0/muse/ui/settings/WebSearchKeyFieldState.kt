package io.zer0.muse.ui.settings

import io.zer0.muse.web.WebSearchKeyRoulette

/**
 * 联网搜索「Key 池」输入框的显示与保存语义（纯逻辑，可 JVM 单测）。
 *
 * 输入框默认**不回填**已保存的 Key：一打开设置页就把密钥明文摆在屏幕上，
 * 投屏或旁人扫一眼即泄露。但"不回填"顺手会引出第二个坑：**留空到底算什么？**
 * 若把空框读成"清空"，用户什么都没做、只按一次保存就会把密钥删掉。
 * 所以这里把三态显式分开：
 *  - 没动过（`edited == false`）：保存时**保持原池**，即使显示上是空的；
 *  - 动过且解析出内容：写入新池；
 *  - 动过且解析为空：才是真的要删除。
 *
 * 已保存多少把由 [savedCount] 单独给指示行，指示行只报个数、不回显任何 Key 字符。
 */
internal object WebSearchKeyFieldState {

    /** 指示行用：已保存池里有几把非空 Key。只给个数，不泄露内容。 */
    fun savedCount(saved: List<String>): Int = saved.count { it.isNotBlank() }

    /**
     * 逐条删除：把池中第 [index] 把 Key 去掉，其余保持原序。
     * 索引越界时原池原样返回（渲染层按下标渲染，越界只可能来自并发改池，静默保持现状最安全）。
     */
    fun removeAt(saved: List<String>, index: Int): List<String> {
        if (index !in saved.indices) return saved
        return saved.filterIndexed { i, _ -> i != index }
    }

    /**
     * 指示行/逐条行的掩码形态：长 Key 露头尾各 3 位（`sk-…789`），
     * 短 Key 全掩码（过短的串露头尾等于整串泄露）。空串原样返回。
     */
    fun masked(key: String): String = when {
        key.isEmpty() -> ""
        key.length <= 6 -> "*".repeat(key.length)
        else -> key.take(3) + "…" + key.takeLast(3)
    }

    /**
     * 保存/验证真正要用的 Key 池。
     * 没动过就原样保持（空框 ≠ 清空）；动过才按分隔符解析草稿，解析为空即删除。
     */
    fun poolToSave(saved: List<String>, draft: String, edited: Boolean): List<String> =
        if (edited) WebSearchKeyRoulette.parse(draft) else saved
}
