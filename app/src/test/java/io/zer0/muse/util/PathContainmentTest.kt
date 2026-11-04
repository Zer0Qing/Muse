package io.zer0.muse.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 路径包含判定的**分隔符**契约。
 *
 * 仓库里同样的判断写过四处，其中三处是 `startsWith(root + File.separator)`，
 * 技能包那处漏了分隔符 —— 于是 `workspace/skillsX/SKILL.md` 会被判成
 * 在 `workspace/skills` 之内（字符串前缀恰好接得上）。
 * 这种"少一个分隔符"的写法在评审里几乎看不见，所以把它抽成一个函数并测住。
 *
 * 分隔符全部用 `File.separator` 拼，不写死 `/`：判定要在真机（Android/Linux）
 * 与本机（Windows）上都成立才算数。
 */
class PathContainmentTest {

    private val sep = File.separator

    private val root = "${sep}data${sep}files${sep}workspace${sep}skills"

    @Test
    fun `真正的子路径判为在内`() {
        assertTrue(isStrictlyInside(root + sep + "a1" + sep + "SKILL.md", root))
    }

    @Test
    fun `兄弟目录名以 root 开头时不得被判为在内（修掉的那处）`() {
        assertFalse(
            "少了分隔符就会把 skillsX 当成 skills 之内 —— 这正是四处里那一处漏写的内容",
            isStrictlyInside(root + "X" + sep + "SKILL.md", root),
        )
        assertFalse(isStrictlyInside(root + "_backup" + sep + "SKILL.md", root))
    }

    @Test
    fun `根目录本身默认不算在内`() {
        assertFalse(
            "解压守卫里目标等于根目录意味着条目名是空或点，历史上那三处都拒绝",
            isStrictlyInside(root, root),
        )
        assertTrue("显式允许时才算", isStrictlyInside(root, root, allowEqual = true))
    }

    @Test
    fun `root 带不带尾部分隔符结果一致（调用方怎么写都不易踩坑）`() {
        val withTail = root + sep
        assertTrue(isStrictlyInside(root + sep + "a1", withTail))
        assertFalse(isStrictlyInside(root + "X", withTail))
    }

    @Test
    fun `完全无关的路径判为在外`() {
        assertFalse(isStrictlyInside("${sep}data${sep}other", root))
        assertFalse(isStrictlyInside("", root))
    }
}
