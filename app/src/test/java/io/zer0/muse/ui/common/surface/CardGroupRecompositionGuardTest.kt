package io.zer0.muse.ui.common.surface

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 防止 [CardGroup] 退化为非 inline 函数导致块内 State 变更时外层不重组。
 *
 * 当 `CardGroup` 不是 `inline` 时，Compose 会为 `content: @Composable CardGroupScope.() -> Unit`
 * 创建独立 RecomposeScope；块内读取的开关状态（如联网搜索、后台保活、主动消息总开关）变化时，
 * 只会重新执行 `content` 往旧 `CardGroupScopeImpl` 追加条目，外层 `CardGroup` 不重组，导致所有开关点击无 UI 反馈。
 */
class CardGroupRecompositionGuardTest {

    private fun readCardGroupSource(): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/ui/common/surface/CardGroup.kt"),
            File("app/src/main/java/io/zer0/muse/ui/common/surface/CardGroup.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 CardGroup.kt (cwd=${File(".").absolutePath})")
        return file.readText()
    }

    @Test
    fun `CardGroup must stay inline with inlined content lambda to propagate state recompositions`() {
        val src = readCardGroupSource()
        assertTrue(
            "CardGroup 必须是 inline fun，确保块内 State 读取订阅到调用方 RecomposeScope",
            src.contains("inline fun CardGroup("),
        )
        assertFalse(
            "CardGroup 的 content lambda 不能标为 noinline，否则会重新引入独立 RecomposeScope 卡死开关",
            Regex("""noinline\s+content\s*:""").containsMatchIn(src),
        )
    }
}
