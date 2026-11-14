package io.zer0.muse.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperienceToolPermissionTest {
    @Test
    fun `record experience requires trusted execution context`() {
        assertTrue(RecordExperienceTool.canWrite(ToolExecutionContext(scope = "main", spaceId = "default")))
        assertFalse(
            RecordExperienceTool.canWrite(
                ToolExecutionContext(scope = "subagent", spaceId = "default", isSubagentContext = true),
            ),
        )
    }
}
