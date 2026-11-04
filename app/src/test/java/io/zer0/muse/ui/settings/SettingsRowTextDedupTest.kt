package io.zer0.muse.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 设置行里**不许出现"标题与副标题是同一句话"**。
 *
 * 起因是实测发现的用户可见冗余：`MediaSettingsPage` 的高级 TTS 三行滑块，
 * `title` 与 `subtitle` 都传了同一个字符串资源 ——
 * 用户在同一行里看到同一句话两遍（比如"合成语速"下面又写一遍"合成语速"），
 * 明显是没写完说明时用标题占位、后来忘了删。
 *
 * 这类问题 lint 永远抓不到（语法完全合法），所以按源码扫一遍：
 * 只锁"紧邻两行、同一个 `R.string.X` 分别当 title 与 subtitle"这一形状 ——
 * 范围刻意很窄，避免把"标题与说明本来就相近"的正常情况误伤。
 */
class SettingsRowTextDedupTest {

    private val pattern = Regex(
        """title\s*=\s*stringResource\(\s*R\.string\.(\w+)\s*\)\s*,\s*\n\s*subtitle\s*=\s*stringResource\(\s*R\.string\.(\w+)\s*\)""",
    )

    private fun sources(): List<File> {
        val roots = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File("../app/src/main/java"),
        )
        val root = requireNotNull(roots.firstOrNull { it.exists() }) {
            "找不到 app 源码目录，工作目录=${File(".").absolutePath}"
        }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun `没有任何设置行把同一字符串同时当标题和副标题`() {
        val offenders = sources().flatMap { file ->
            val text = file.readText()
            pattern.findAll(text).map { match ->
                if (match.groupValues[1] == match.groupValues[2]) {
                    "${file.name}: R.string.${match.groupValues[1]} 同时用作 title 与 subtitle"
                } else {
                    null
                }
            }.filterNotNull().toList()
        }

        assertTrue(
            "这些行会在同一行里把同一句话显示两遍（用标题占位忘了写说明的形状）：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `扫描器本身要能抓到形状（防止正则失效变成恒过测试）`() {
        val sample = """
            SettingsSliderRow(
                title = stringResource(R.string.settings_media_tts_stability),
                subtitle = stringResource(R.string.settings_media_tts_stability),
            )
        """.trimIndent()

        val match = pattern.find(sample)
        assertTrue("正则连样例都匹配不到，那条守卫就是恒真的摆设", match != null)
    }
}
