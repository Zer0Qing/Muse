package io.zer0.muse.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * 收藏夹的源码级回归（审计 §11-1 / §11-8 / §11-9）。
 *
 * 这三条都是"代码看上去没问题、界面上才露馅"的类型，用纯逻辑单测抓不到，
 * 沿用 [MessageBubbleActionMenuWiringTest] 的做法直接钉源码结构。
 */
class FavoritesScreenWiringTest {

    @Test
    fun `preset group token never reaches the screen as a literal`() {
        val source = locate("app/src/main/java/io/zer0/muse/ui/FavoritesScreen.kt")

        // §11-1: 预设分组对话框是内部 token 外泄的源头，已移除。
        assertFalse(
            "预设分组选择对话框必须移除（它写的就是 __group_*__ 内部串）",
            source.contains("FavoriteGroupSelectDialog"),
        )
        // 存量值必须在读取时映射成字符串资源，而不是原样显示。
        assertTrue(
            "分组文案必须走 favoriteTagLabel 映射",
            source.contains("favoriteTagLabel(tag)"),
        )
        // 分组写入只保留自定义 tag 一条路径（唯一的定义 + 唯一的调用点）。
        assertEquals(
            "分组对话框只能有一个",
            2,
            Regex("FavoriteTagEditDialog\\(").findAll(source).count(),
        )
    }

    @Test
    fun `favorite star has a real selected state`() {
        val targets = listOf(
            "app/src/main/java/io/zer0/muse/ui/MessageBubble.kt",
            "app/src/main/java/io/zer0/muse/ui/translate/TranslateHistorySection.kt",
            "app/src/main/java/io/zer0/muse/ui/moment/MomentsScreen.kt",
            "app/src/main/java/io/zer0/muse/ui/FavoritesScreen.kt",
        )

        targets.forEach { relative ->
            val source = locate(relative).lines()
                // 注释里会引述旧写法作为反例，比对时剔除整行注释
                .filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }
                .joinToString("\n")
            // §11-8: `if (x) MuseIcons.star else MuseIcons.star` 是恒等三元式，状态位没参与渲染。
            assertFalse(
                "$relative 仍有恒等的星标三元式",
                Regex("if \\([^)]*\\)\\s*MuseIcons\\.star\\s*else\\s*MuseIcons\\.star").containsMatchIn(source),
            )
        }

        // 选中态必须真的参与渲染：tint / alpha / 容器三档用的是同一处口径。
        listOf(
            "app/src/main/java/io/zer0/muse/ui/translate/TranslateHistorySection.kt",
            "app/src/main/java/io/zer0/muse/ui/moment/MomentsScreen.kt",
        ).forEach { relative ->
            val source = locate(relative)
            assertTrue("$relative 必须用 favoriteStarTint 区分选中态", source.contains("favoriteStarTint("))
        }
        assertTrue(
            "朋友圈筛选还叠了 alpha 档位",
            locate("app/src/main/java/io/zer0/muse/ui/moment/MomentsScreen.kt").contains("favoriteStarAlpha("),
        )
    }

    @Test
    fun `preview truncation is structural not a raw take`() {
        val source = locate("app/src/main/java/io/zer0/muse/ui/FavoritesScreen.kt")

        // §11-9: 字符级 take 会切断代码围栏 / 强调符 / [N] 引用。
        assertFalse("收藏卡片预览不得再用 take(", Regex("\\.take\\(\\s*280\\s*\\)").containsMatchIn(source))
        assertTrue(
            "预览截断必须走结构感知实现",
            source.contains("MarkdownPreviewTruncation.preview("),
        )
    }

    @Test
    fun `favorites screen no longer reads the dead group filter`() {
        val source = locate("app/src/main/java/io/zer0/muse/ui/FavoritesScreen.kt")

        // §11-13: favoriteGroup / setFavoriteGroup 全仓无调用点，过滤逻辑是死分支。
        assertFalse("favoriteGroup 已移除", source.contains("state.favoriteGroup"))
        assertFalse("setFavoriteGroup 已移除", source.contains("viewModel.setFavoriteGroup"))
    }
}

private fun locate(relative: String): String {
    val candidates = listOf(Path.of(relative), Path.of("..").resolve(relative))
    val path = candidates.firstOrNull(Files::exists)
        ?: error("找不到 $relative，当前目录 ${System.getProperty("user.dir")}")
    return Files.newBufferedReader(path).use { it.readText() }
}
