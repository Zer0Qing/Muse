package io.zer0.muse.ui.markdown

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface

/**
 * v1.0.92: 卡片回传动作 — 富内容卡片(HTML/SVG)与宿主之间的最小契约。
 *
 * 触发路径:
 *  - [Send]:  卡片内脚本调用 window.muse.send(text)(经 [MuseCardBridge] 桥回传);
 *  - [Save]:  用户点击卡片头部"保存"按钮(由调用方保存为工件)。
 */
sealed interface CardAction {
    /** 卡片内脚本请求发送一段用户消息。 */
    data class Send(val text: String) : CardAction

    /** 用户请求把卡片保存为工件。 */
    data class Save(val language: String, val content: String) : CardAction
}

/**
 * v1.0.92: 卡片 JS 桥 — 注入为全局对象 [BRIDGE_NAME],卡片脚本通过
 * `window.muse.send(text)`(引导脚本封装,见 [CARD_BRIDGE_BOOTSTRAP_JS])回传动作。
 *
 * 安全边界:
 *  - 仅暴露 [send] 一个方法,且限长(超长截断),不允许脚本读取任何宿主数据;
 *  - 回调切回主线程执行;空文本丢弃;
 *  - 桥仅在调用方提供回传回调的卡片上注入(聊天消息场景),其余场景(如工件查看)
 *    保持无桥、无脚本的纯渲染。
 */
internal class MuseCardBridge(private val onAction: (CardAction) -> Unit) {

    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun send(text: String) {
        val trimmed = text.trim().take(MAX_ACTION_TEXT_LENGTH)
        if (trimmed.isEmpty()) return
        mainHandler.post { onAction(CardAction.Send(trimmed)) }
    }

    companion object {
        /** addJavascriptInterface 使用的全局对象名。 */
        const val BRIDGE_NAME = "MuseCardBridge"

        /** 单次回传文本上限(字符),防止脚本滥用注入超长消息。 */
        private const val MAX_ACTION_TEXT_LENGTH = 4000
    }
}

/**
 * v1.0.92: 引导脚本 — 与卡片 HTML 一起注入(在清洗之后拼接,不受清洗正则影响),
 * 为卡片脚本提供 `window.muse` 门面。
 *
 * 卡片内用法:
 *   if (window.muse) window.muse.send("用户点了开始");
 *   if (window.muse && window.muse.available) { ... }   // 桥是否可用
 */
internal const val CARD_BRIDGE_BOOTSTRAP_JS: String = """
(function(){
  if (window.muse && window.muse.send) return;
  window.muse = window.muse || {};
  window.muse.available = true;
  window.muse.send = function(text){
    try { MuseCardBridge.send(String(text == null ? '' : text)); } catch (e) {}
  };
})();
"""
