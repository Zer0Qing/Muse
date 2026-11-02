package io.zer0.muse.tools

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 浏览器动作的**结果合同**（对比清单 P1-11）。
 *
 * 曾经的行为：元素没找到、选择器失效、控件不可编辑，工具都返回 `success=true`，
 * 只在 `data` 里夹一句"未命中"。模型读到 success 就认为页面变了，接着往下走 ——
 * 这类"假成功"比少一个功能更伤：用户看到的是助手自信地报告完成了一件没发生的事。
 *
 * 现在的约定：
 *  - 没发生 → `success=false` + 可行动原因；
 *  - 发生了但看不到效果 → `success=true` + `verified=false`（并写明"页面变化未验证"）。
 */
class BrowserAutomationToolTest {

    private lateinit var browser: BrowserManager

    @Before
    fun setUp() {
        browser = mockk(relaxed = true)
    }

    private fun run(tool: String, args: Map<String, String>): JsonObject =
        BrowserAutomationTool.executeFromArgs(tool, args, browser).let {
            io.zer0.common.AppJson.parseToJsonElement(it).jsonObject
        }

    private fun JsonObject.success() = this["success"]!!.jsonPrimitive.content.toBoolean()
    private fun JsonObject.verified() = this["verified"]?.jsonPrimitive?.content?.toBoolean()
    private fun JsonObject.error() = this["error"]?.jsonPrimitive?.content.orEmpty()
    private fun JsonObject.data() = this["data"]?.jsonPrimitive?.content.orEmpty()

    @Test
    fun `numbered click on a missing element is a failure`() {
        coEvery { browser.evaluateJs(any()) } returns Result.success("MISS")

        val result = run(BrowserAutomationTool.TOOL_CLICK, mapOf("numberId" to "3"))

        assertFalse("元素没找到不能报成功", result.success())
        assertTrue("要给出下一步: ${result.error()}", result.error().contains("browser_snapshot"))
    }

    @Test
    fun `numbered click that happened is success but explicitly unverified`() {
        coEvery { browser.evaluateJs(any()) } returns Result.success("OK")

        val result = run(BrowserAutomationTool.TOOL_CLICK, mapOf("numberId" to "3"))

        assertTrue(result.success())
        assertEquals("点击是否改变了页面，浏览器这边看不到", false, result.verified())
        assertTrue("要写进文案: ${result.data()}", result.data().contains("未验证"))
    }

    @Test
    fun `selector click miss is a failure`() {
        coEvery { browser.click(any()) } returns Result.success(false)

        val result = run(BrowserAutomationTool.TOOL_CLICK, mapOf("selector" to "#gone"))

        assertFalse("未命中不能报成功", result.success())
        assertTrue("要说明选择器可能失效: ${result.error()}", result.error().contains("未命中"))
    }

    @Test
    fun `selector click hit is success and unverified`() {
        coEvery { browser.click(any()) } returns Result.success(true)

        val result = run(BrowserAutomationTool.TOOL_CLICK, mapOf("selector" to "#ok"))

        assertTrue(result.success())
        assertEquals(false, result.verified())
    }

    @Test
    fun `typing into a non editable element is a failure`() {
        coEvery { browser.evaluateJs(any()) } returns Result.success("NOT_EDITABLE")

        val result = run(
            BrowserAutomationTool.TOOL_TYPE,
            mapOf("numberId" to "5", "text" to "hello"),
        )

        assertFalse("不可编辑要拒绝，而不是往 div 里写 value 还报成功", result.success())
        assertTrue("要说清原因: ${result.error()}", result.error().contains("不可编辑"))
    }

    @Test
    fun `numbered type on a missing element is a failure`() {
        coEvery { browser.evaluateJs(any()) } returns Result.success("MISS")

        val result = run(BrowserAutomationTool.TOOL_TYPE, mapOf("numberId" to "9", "text" to "x"))

        assertFalse(result.success())
        assertTrue(result.error().contains("未找到"))
    }

    @Test
    fun `selector type miss is a failure`() {
        coEvery { browser.type(any(), any()) } returns Result.success(false)

        val result = run(BrowserAutomationTool.TOOL_TYPE, mapOf("selector" to "#gone", "text" to "x"))

        assertFalse(result.success())
    }

    @Test
    fun `the numbered type script refuses non editable elements`() {
        // 直接看脚本内容：可编辑性检查必须真的写进注入的 JS 里，而不是只在 Kotlin 侧判断
        val script = BrowserAutomationTool.debugNumberedTypeScript(0, "x")

        assertTrue("脚本要判 tagName/isContentEditable: $script", script.contains("isContentEditable"))
        assertTrue("脚本要返回明确的不可编辑信号: $script", script.contains("NOT_EDITABLE"))
    }
}
