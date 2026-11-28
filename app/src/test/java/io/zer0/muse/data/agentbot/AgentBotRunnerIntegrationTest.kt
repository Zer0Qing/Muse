package io.zer0.muse.data.agentbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.zer0.muse.tools.ToolRegistry
import io.zer0.muse.tools.ToolRiskLevel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * 真实 [ToolRegistry] 与副作用分级的联动。
 *
 * 上一轮端到端从"派工"直接跳到"候选已存在"，中间的真实工具执行没参与。
 * 这里验证最基础的一环：注册表真的能给出风险等级，工具真的能执行。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AgentBotRunnerIntegrationTest {

    private lateinit var registry: ToolRegistry
    private lateinit var workspace: File

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        workspace = File(System.getProperty("java.io.tmpdir"), "runner-ws-" + System.nanoTime())
        workspace.mkdirs()
        registry = ToolRegistry(context)
        registry.register(
            ToolRegistry.ToolDef(
                name = "read_note",
                description = "读一个笔记文件",
                parameters = mapOf("path" to "文件路径"),
                required = setOf("path"),
                riskLevel = ToolRiskLevel.SAFE,
            ),
        ) { args ->
            val path = args["path"]?.toString().orEmpty()
            val file = File(workspace, path)
            if (file.isFile) file.readText() else "文件不存在"
        }
    }

    @After
    fun tearDown() {
        workspace.deleteRecursively()
    }

    @Test
    fun `safe tool is classified retry-safe by the registry`() {
        assertEquals(
            "只读工具必须被识别为 SAFE，否则每个任务都会判 PARTIAL",
            ToolRiskLevel.SAFE,
            registry.getToolRiskLevel("read_note"),
        )
    }

    @Test
    fun `unknown tool has no risk level`() {
        assertEquals(null, registry.getToolRiskLevel("never_registered"))
    }

    @Test
    fun `tool executes against the real workspace file`() = runTest {
        File(workspace, "a.md").writeText("真实内容")
        val result = registry.executeFromJson("read_note", """{"path":"a.md"}""")
        assertTrue("工具必须真的读到文件", result.contains("真实内容"))
    }
}
