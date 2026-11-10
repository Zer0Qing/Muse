package io.zer0.muse.tools

import io.zer0.common.AppJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具产出的一张图片，以及给它的那句说明。
 *
 * @param base64 图片本体（无 `data:` 前缀）。它走独立的带图消息，不进模型上下文文本。
 * @param caption 随图那条用户消息的文案；null 时由调用方兜底。
 * @param dataText 结果里本来就写给人看的文本。有它就不必把整串 JSON（含 Base64）
 *   当成工具输出交给截断与落盘管线。
 */
internal data class ScreenshotPayload(
    val base64: String,
    val caption: String?,
    val dataText: String? = null,
)

/**
 * 工具结果里的「图片载体」解析（v2.6.0 起由浏览器截图引入，v2.6.22 起工作区图片读取共用）。
 *
 * 抽到文件级而不是留在 `ToolOrchestrator` 里，只有一个理由：编排器要一堆依赖才能构造，
 * 而这条解析是纯字符串规则。留在类里就等于没人给它写测试，
 * 而它的失效方式是静默的——Base64 会当成普通超长输出进上下文和落盘。
 */
internal object ToolScreenshotPayload {

    /**
     * 从工具结果 JSON 里取出图片。
     *
     * 只在三个条件同时成立时返回非空：① 工具执行成功；② 结果能解析成 JSON 对象；
     * ③ 含约定字段 [BrowserAutomationTool.SCREENSHOT_RESULT_FIELD]。
     *
     * 一次解析取全部字段：原图 Base64 可以有几 MB，分成两个函数就是同一串 JSON 解析两遍。
     */
    fun of(toolResult: String, isSuccess: Boolean): ScreenshotPayload? {
        if (!isSuccess) return null
        if (!toolResult.contains("\"${BrowserAutomationTool.SCREENSHOT_RESULT_FIELD}\"")) return null
        return runCatching {
            val obj = AppJson.decodeFromString(JsonObject.serializer(), toolResult)
            fun field(name: String): String? = obj[name]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            val base64 = field(BrowserAutomationTool.SCREENSHOT_RESULT_FIELD) ?: return@runCatching null
            ScreenshotPayload(
                base64 = base64,
                caption = field(BrowserAutomationTool.SCREENSHOT_CAPTION_FIELD),
                dataText = field("data"),
            )
        }.getOrNull()
    }

    /**
     * 带图结果**该进上下文与任务卡**的那段文本。
     *
     * 不摘这一层的后果（v2.6.22 之前就是这样）：几 MB 的 Base64 被当成普通超长输出，
     * 走一遍截断与落盘、再往 `tool_outputs` 写一份重复字节，而模型和任务卡看到的
     * 只是被削了头的 JSON。图片本体从原始结果里取，走独立的带图消息。
     */
    fun resultText(payload: ScreenshotPayload): String = payload.dataText ?: "[图片已随附：见下一条图片消息]"
}
