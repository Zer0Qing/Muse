package io.zer0.muse.tools.script

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProcessSkillEngineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `large successful node payload is represented by a complete file reference`() {
        val file = File(tempFolder.root, "large-success.json")
        file.writeText("""{"ok":true,"value":"${"x".repeat(300_000)}","logs":[]}""")
        val reference = "[完整输出已保存到: /data/data/io.zer0.muse/files/tool_outputs/large-success.json]"

        val result = parseLargeNodePayload(file, reference)

        assertTrue(result is SkillEngineResult.Success)
        assertTrue((result as SkillEngineResult.Success).valueJson.contains(reference))
        assertTrue(file.readText().contains("x".repeat(300_000)))
    }

    @Test
    fun `large failed node payload remains a failure and keeps its file reference`() {
        val file = File(tempFolder.root, "large-failure.json")
        file.writeText("""{"ok":false,"error":"script failed","logs":["${"e".repeat(300_000)}"]}""")
        val reference = "[完整输出已保存到: /data/data/io.zer0.muse/files/tool_outputs/large-failure.json]"

        val result = parseLargeNodePayload(file, reference)

        assertTrue(result is SkillEngineResult.Error)
        assertTrue((result as SkillEngineResult.Error).message.contains("Node 脚本执行失败"))
        assertTrue(result.message.contains(reference))
    }
}
