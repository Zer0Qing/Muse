package io.zer0.muse.ui.settings

import io.zer0.muse.data.skill.SkillEntity
import io.zer0.muse.tools.SkillImporter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「扩展与连接」中枢页的计数逻辑测试。
 *
 * 中枢页只负责"让人看见有多少"，所以这里的错法很具体：
 * 把内置技能算成用户资产、或者技能包数不出来，用户看到的就是一个不可信的徽标。
 */
class ConnectionCenterCountsTest {

    private fun skill(id: String, implementation: String) =
        SkillEntity(id = id, name = id, description = "d", implementationKotlin = implementation)

    @Test
    fun `built-in skills are not counted as user content`() {
        val builtInId = io.zer0.muse.tools.SkillExecutor.BUILT_IN_SKILLS.first().id
        val skills = listOf(
            skill(builtInId, "read_file"),
            skill("mine", SkillImporter.encodePromptText("指令")),
        )

        val (skillCount, packageCount) = skillAndPackageCounts(skills)

        assertEquals("内置技能不该计入", 1, skillCount)
        assertEquals(0, packageCount)
    }

    @Test
    fun `skill packages are counted by the prompt_file prefix only`() {
        val skills = listOf(
            skill("pkg-a", SkillImporter.encodePromptFile("workspace/skills/pkg-a/SKILL.md")),
            skill("pkg-b", SkillImporter.encodePromptFile("workspace/skills/pkg-b/SKILL.md")),
            skill("plain", SkillImporter.encodePromptText("只是一段指令")),
        )

        val (skillCount, packageCount) = skillAndPackageCounts(skills)

        assertEquals(3, skillCount)
        assertEquals("只认 prompt_file 前缀", 2, packageCount)
    }

    @Test
    fun `plugin tool rows are counted but skills are not`() {
        val skills = listOf(
            skill("plugin_5_weather_get", "plugin:weather:get"),
            skill("plugin_7_news_run", "plugin:news:run"),
            skill("user-skill", SkillImporter.encodePromptText("指令")),
        )

        assertEquals(2, pluginInstalledCount(skills))
    }

    @Test
    fun `empty list yields zeroes instead of crashing`() {
        val (skillCount, packageCount) = skillAndPackageCounts(emptyList())

        assertEquals(0, skillCount)
        assertEquals(0, packageCount)
        assertEquals(0, pluginInstalledCount(emptyList()))
    }
}
