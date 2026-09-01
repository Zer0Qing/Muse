package io.zer0.muse.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * I-AUDIT: ContentSafetyRules 硬边界测试 — 补齐审批门"零测试"缺口的第三块。
 *
 * 审计 P1-1 指出 ToolPermissionResolver / ParamPolicies / ContentSafetyRules 三者
 * 完全无测试。前两者已由 ToolPermissionResolverTest 覆盖,本类补上执行前硬边界
 * (js 危险进程 / 敏感信息外传 / 本地文件 / 路径穿越 / 绝对路径)的回归护栏。
 */
class ContentSafetyRulesTest {

    @Test
    fun `execute_javascript 拦截危险进程执行`() {
        assertEquals(
            "js-dangerous-process",
            ContentSafetyRules.check("execute_javascript", "rm -rf /tmp/x")?.ruleId,
        )
        assertEquals(
            "js-dangerous-process",
            ContentSafetyRules.check("execute_javascript", "Runtime.getRuntime().exec(\"id\")")?.ruleId,
        )
    }

    @Test
    fun `execute_javascript 拦截敏感信息外传`() {
        assertEquals(
            "js-exfil-token",
            ContentSafetyRules.check("execute_javascript", "document.cookie")?.ruleId,
        )
    }

    @Test
    fun `open_url 拦截本地文件`() {
        assertEquals("url-local-file", ContentSafetyRules.check("open_url", "file:///etc/passwd")?.ruleId)
    }

    @Test
    fun `workspace_write 拦截路径穿越与绝对路径`() {
        assertEquals("workspace-traversal", ContentSafetyRules.check("workspace_write", "../etc/passwd")?.ruleId)
        assertEquals("workspace-absolute-path", ContentSafetyRules.check("workspace_write", "/etc/passwd")?.ruleId)
    }

    @Test
    fun `安全相对路径与未知工具不命中任何规则`() {
        assertNull(ContentSafetyRules.check("workspace_write", "files/note.txt"))
        assertNull(ContentSafetyRules.check("unknown_tool", "rm -rf /"))
    }
}
