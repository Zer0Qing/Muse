package io.zer0.muse.ui.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 聊天背景文件归属判定 —— 防的是**跨槽位误删**。
 *
 * 背景有三层槽位（全局 / 助手 / 会话），外加一个深色全局槽位，
 * 文件名分别是 `chat_background.jpg`、`chat_background_dark.jpg`、
 * `chat_bg_session_x.jpg`…；重新选图时还会留带时间戳的历史名
 * （`chat_background_1700000000000.jpg`）。
 *
 * 旧判定是 `name.startsWith("${stem}_")`，于是**全局槽位（stem=`chat_background`）
 * 会把 `chat_background_dark.jpg` 判成自己的文件删掉** ——
 * 用户只是清一下（或重选一下）浅色背景，深色模式的背景图就没了，
 * 而深色那边存的 `file://` 路径还在，表现成"深色模式莫名其妙没有背景"。
 */
class ChatBackgroundSlotMatchTest {

    private val globalBase = "chat_background.jpg"
    private val globalStem = "chat_background"
    private val darkBase = "chat_background_dark.jpg"
    private val darkStem = "chat_background_dark"

    @Test
    fun `全局槽位不得认领深色槽位的文件（曾经的越界删除）`() {
        assertFalse(
            "清浅色背景时把深色背景文件一起删了 —— 这正是修掉的 bug",
            isSlotFile("chat_background_dark.jpg", globalBase, globalStem),
        )
        assertFalse(
            "深色槽位自己的历史时间戳文件也不能被全局认领",
            isSlotFile("chat_background_dark_1700000000000.jpg", globalBase, globalStem),
        )
    }

    @Test
    fun `全局槽位认得自己的当前名与历史名`() {
        assertTrue(isSlotFile(globalBase, globalBase, globalStem))
        assertTrue(isSlotFile("chat_background_1700000000000.jpg", globalBase, globalStem))
    }

    @Test
    fun `深色槽位管自己的文件也不越界`() {
        assertTrue(isSlotFile(darkBase, darkBase, darkStem))
        assertTrue(isSlotFile("chat_background_dark_1700000000000.jpg", darkBase, darkStem))
        assertFalse("不能反过来把浅色那张删掉", isSlotFile(globalBase, darkBase, darkStem))
    }

    @Test
    fun `会话与助手槽位互不侵犯`() {
        val s1 = "chat_bg_session_a1"
        assertFalse(
            "id 前缀关系（a1 与 a12）不能让一个会话删另一个会话的背景",
            isSlotFile("chat_bg_session_a12.jpg", "chat_bg_session_a1.jpg", s1),
        )
        assertTrue("自己的历史文件仍然要清掉", isSlotFile("chat_bg_session_a1_1700.jpg", "chat_bg_session_a1.jpg", s1))
    }

    @Test
    fun `非 jpg 与只有名字前缀相同都不算`() {
        assertFalse(isSlotFile("chat_background_1700.png", globalBase, globalStem))
        assertFalse("后缀不是纯数字（不是时间戳）就不认领", isSlotFile("chat_background_notes.txt", globalBase, globalStem))
        assertFalse("光有 stem 没有下划线也不算", isSlotFile("chat_backgroundx_1700.jpg", globalBase, globalStem))
    }
}
