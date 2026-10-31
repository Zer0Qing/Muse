package io.zer0.muse.ui

import io.zer0.muse.data.skill.SkillEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「一键启停」作用范围的判据。
 *
 * 这个函数决定用户点下「全部停用」时到底会发生什么。判错了后果很重：
 * 把内置技能一起关掉，等于把模型的所有工具收走，界面上却只是一句「全部停用」。
 */
class SkillBulkToggleTargetsTest {

    @Test
    fun `内置技能不参与一键启停`() {
        val skills = listOf(
            skill(id = "read_file", impl = "read_file"),
            skill(id = "mine", impl = "read_file"),
        )

        val targets = bulkToggleTargets(skills, builtInIds = setOf("read_file"))

        assertEquals(listOf("mine"), targets.map { it.id })
    }

    @Test
    fun `插件技能不参与一键启停`() {
        // 插件技能随插件启停，单独改会和插件状态打架。
        val skills = listOf(
            skill(id = "plugin_skill", impl = "plugin:my-plugin:hello"),
            skill(id = "mine", impl = "read_file"),
        )

        val targets = bulkToggleTargets(skills, builtInIds = emptySet())

        assertEquals(listOf("mine"), targets.map { it.id })
    }

    @Test
    fun `只有用户自己的技能会被批量切换`() {
        val skills = listOf(
            skill(id = "read_file", impl = "read_file"),
            skill(id = "plugin_skill", impl = "plugin:p:fn"),
            skill(id = "a", impl = "read_file"),
            skill(id = "b", impl = "http_get"),
        )

        val targets = bulkToggleTargets(skills, builtInIds = setOf("read_file"))

        assertEquals(listOf("a", "b"), targets.map { it.id })
    }

    @Test
    fun `没有可切换对象时返回空列表`() {
        val targets = bulkToggleTargets(
            listOf(skill(id = "read_file", impl = "read_file")),
            builtInIds = setOf("read_file"),
        )

        assertTrue(targets.isEmpty())
    }

    private fun skill(id: String, impl: String) = SkillEntity(
        id = id,
        name = id,
        description = "",
        implementationKotlin = impl,
    )
}
