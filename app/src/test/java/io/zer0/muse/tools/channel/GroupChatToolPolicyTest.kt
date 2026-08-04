package io.zer0.muse.tools.channel

import io.zer0.ai.core.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** B8-03: 群聊媒体工具过滤策略测试。 */
class GroupChatToolPolicyTest {

    private fun def(name: String) = ToolDefinition(
        name = name,
        description = name,
        parametersJsonSchema = "{}",
    )

    @Test
    fun filtersMediaOutputTools() {
        val input = listOf(
            def("web_search"),
            def("generate_image"),
            def("generate_video"),
            def("generate_qr_code"),
            def("calculator"),
        )

        val result = GroupChatToolPolicy.filterRegularTools(input)

        assertEquals(listOf("web_search", "calculator"), result.map { it.name })
        assertFalse(result.any { it.name in GroupChatToolPolicy.MEDIA_OUTPUT_TOOLS })
    }

    @Test
    fun keepsRegularToolsWhenNoMediaToolsPresent() {
        val input = listOf(def("web_search"), def("schedule_reminder"))

        val result = GroupChatToolPolicy.filterRegularTools(input)

        assertEquals(2, result.size)
        assertTrue(result.any { it.name == "web_search" })
        assertTrue(result.any { it.name == "schedule_reminder" })
    }
}
