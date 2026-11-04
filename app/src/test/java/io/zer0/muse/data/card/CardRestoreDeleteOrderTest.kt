package io.zer0.muse.data.card

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 卡片删除与恢复线程的**登记顺序**契约。
 *
 * `CardDataStore.remove` 里那句注释写得很清楚：
 * 「必须在改内存**之前**登记：恢复线程的过滤读的是这个集合」。
 * 反过来的话存在一个窗口 —— 恢复线程读到"这条没被删过"，把旧快照写回来，
 * 于是**用户删掉的卡片在恢复后复活**。表现是偶发、且极难复现（两种交错顺序只有一种出问题）。
 *
 * 为什么断言代码结构而不是行为：要复现这个竞态需要控制恢复线程与删除的相对时序，
 * 单测里做不到稳定；而会引入回归的动作恰恰是"有人把两行换了位置"，
 * 结构断言正好盯得住它（同 `ReminderCatchUpOrderTest` 的取舍）。
 */
class CardRestoreDeleteOrderTest {

    private fun removeBody(): String {
        val candidates = listOf(
            File("src/main/java/io/zer0/muse/data/card/CardDataStore.kt"),
            File("app/src/main/java/io/zer0/muse/data/card/CardDataStore.kt"),
        )
        val source = requireNotNull(candidates.firstOrNull { it.exists() }) {
            "找不到 CardDataStore.kt，工作目录=${File(".").absolutePath}"
        }.readText()
        val start = source.indexOf("fun remove(cardId: String)")
        assertTrue("签名变了，这条守卫要跟着更新", start >= 0)
        return source.substring(start).substringBefore("\n    /**").let { body ->
            // 截到方法结束，避免把后面方法里的登记也算进来
            if (body.contains("\n    }")) body.substringBefore("\n    }") else body
        }
    }

    @Test
    fun `登记必须先于内存改写`() {
        val body = removeBody()

        val registers = body.indexOf("removedDuringRestore.add(cardId)")
        val mutates = body.indexOf("_data.value = _data.value - cardId")

        assertTrue("登记语句要在 remove 里（实现可能被改名，守卫要跟上）", registers >= 0)
        assertTrue("内存改写语句要在 remove 里", mutates >= 0)
        assertTrue(
            "顺序必须是先登记再改内存：反过来会让恢复线程把用户已删的卡片复活",
            registers < mutates,
        )
    }

    @Test
    fun `登记不受提前返回影响`() {
        val body = removeBody()

        val containsCheck = body.indexOf("if (!_data.value.containsKey(cardId)) return")
        assertTrue(
            "『不存在就提前 return』必须排在登记之后 —— 否则恢复窗口里删一条不存在的卡片" +
                "会留下没登记的删除记录",
            containsCheck < 0 || body.indexOf("removedDuringRestore.add(cardId)") < containsCheck,
        )
    }
}
