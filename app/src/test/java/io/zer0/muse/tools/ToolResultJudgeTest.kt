package io.zer0.muse.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P5-6: 生产失败判定器 [ToolResultJudge.isSuccess] 的完整矩阵测试。
 *
 * 守护对象: 子代理/任务卡/定时任务/测试四处判定共用同一实现,任何一处弱化
 * (只认 1-2 种前缀)都会造成「模型以为工具成功,前台却无结果」的漂移。
 */
class ToolResultJudgeTest {

    @Test
    fun `plain success results are judged success`() {
        assertTrue(ToolResultJudge.isSuccess("计算完成: 1 + 1 = 2"))
        assertTrue(ToolResultJudge.isSuccess("saved"))
        assertTrue(ToolResultJudge.isSuccess("已写入 /tmp/a.txt"))
        assertTrue(ToolResultJudge.isSuccess("{\"ok\": true}"))
    }

    @Test
    fun `error keyword prefix is a failure`() {
        assertFalse(ToolResultJudge.isSuccess("error: open_url 网络不可达"))
    }

    @Test
    fun `bracket failure markers are failures`() {
        assertFalse(ToolResultJudge.isSuccess("[超时] 工具 browser_search 30s 未响应"))
        assertFalse(ToolResultJudge.isSuccess("[中断] 工具 execute_code 执行被取消"))
        assertFalse(ToolResultJudge.isSuccess("[错误] verify 失败"))
        assertFalse(ToolResultJudge.isSuccess("[失败] verify 失败"))
        assertFalse(ToolResultJudge.isSuccess("[取消] 用户取消"))
        assertFalse(ToolResultJudge.isSuccess("[工具输出已截断] 内容过长"))
    }

    @Test
    fun `all chinese error prefixes are failures`() {
        val failures = listOf(
            "工具不存在: foo",
            "工具执行异常: boom",
            "参数解析失败: bad json",
            "skill 执行异常: oops",
            "路径越权: /etc/passwd",
            "缺少参数: url",
            "文件过大: 100MB",
            "文件不存在: /x",
            "未知 skill: y",
            "URL 必须为 https",
            "无法读取文件",
            "未找到 id 为 abc 的会话",
            "子助手运行失败",
            "错误: 图片生成失败",
            "失败: verify",
            "超时: 请求超过 30s",
            "取消: 用户取消",
            "未配置 API Key",
            "为空: 结果列表为空",
            "不可用: 服务不可用",
        )
        failures.forEach { assertFalse("should fail: $it", ToolResultJudge.isSuccess(it)) }
        // 已知缺口(不阻塞): 判定器只做「前缀」匹配,后缀式失败文本如
        // 「图片生成失败」整段不含失败前缀时当前被判为成功 —— 见注释于
        // ToolResultJudge.kt BRACKET_FAILURE_MARKERS 附近,留待专项收紧。
    }

    @Test
    fun `structured error json is a failure`() {
        assertFalse(ToolResultJudge.isSuccess("""{"error": "网络错误", "code": 500}"""))
        assertFalse(ToolResultJudge.isSuccess("""{"data": null, "error": "UNAUTHORIZED"}"""))
    }

    @Test
    fun `non-error structured json is a success`() {
        assertTrue(ToolResultJudge.isSuccess("""{"result": "2", "ok": true}"""))
    }

    @Test
    fun `result containing error word in the middle is still a success`() {
        assertTrue(ToolResultJudge.isSuccess("查询完成,未发现 error 相关记录"))
    }

    @Test
    fun `long document text starting with a weak failure word is a success`() {
        // v2.6.2（用户审计）: read_file/web_fetch 返回的**正常正文**若以“错误/失败/无法”
        // 开头,不能被误判为失败（否则任务卡显示失败 + 注入重试引导）。
        val docAboutErrors = "错误处理指南\n\n本文介绍三种常见的错误处理模式：" +
            "重试、回退与熔断。在实际工程中，这些模式……".repeat(10)
        assertTrue(ToolResultJudge.isSuccess(docAboutErrors))

        val novelOpening = "失败乃成功之母。这是一个人人皆知却很少真懂的道理，故事要从二十年前那个夏天说起……".repeat(6)
        assertTrue(ToolResultJudge.isSuccess(novelOpening))

        val cannotDoc = "无法启动服务的排查步骤：先看日志，再检查端口占用，最后确认配置文件……".repeat(8)
        assertTrue(ToolResultJudge.isSuccess(cannotDoc))
    }

    @Test
    fun `short bare failure label is still a failure`() {
        // 弱失败词作为短标签仍应判失败（工具确实会直接返回这类短串）。
        assertFalse(ToolResultJudge.isSuccess("失败(坐标越界)"))
        assertFalse(ToolResultJudge.isSuccess("失败(包名无效)"))
        assertFalse(ToolResultJudge.isSuccess("无法打开输出流"))
        assertFalse(ToolResultJudge.isSuccess("不支持的虚拟屏按键:home"))
    }
}
