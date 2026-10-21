package io.zer0.muse.ui.markdown

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import org.json.JSONObject

/**
 * v1.0.92: 卡片回传动作 — 富内容卡片(HTML/SVG)与宿主之间的最小契约。
 * v2.6.7: 补卡片身份与结构化状态，修「卡片闭环断开」。
 *
 * ## 为什么这次要改
 *
 * 桥原先只有 `send(text)`：卡片必须先把用户的操作压成一句自然语言，才能传回对话。
 * 压不进去的东西（拖动的中间值、选中的条目、计算结果）全部丢失，且宿主不知道
 * 这条消息来自哪张卡、对应哪次交互 —— 表现为「卡片在卡片里结束，对话在对话里结束」。
 *
 * 现在的契约：
 *  - [Send.cardId]：卡片身份。由宿主注入（用消息 id，天然绑定对话位置），
 *    卡片脚本通过 `window.muse.cardId` 直接读，不再需要模型把一次性 `c_xxx`
 *    写死进 HTML（那条路一换 cardId 就断）。
 *  - [Send.data]：卡片回传的结构化状态（JSON 文本）。宿主把它作为不可见的
 *    `<card_state>` 尾巴附在用户消息后，模型读得到，气泡不显示。
 *  - [Send.text]：面向用户与模型的一句话摘要。可以很粗，精度由 [Send.data] 承担。
 *
 * 触发路径:
 *  - [Send]:  卡片内脚本调用 window.muse.send(...)（经 [MuseCardBridge] 桥回传）;
 *  - [Save]:  用户点击卡片头部"保存"按钮(由调用方保存为工件)。
 */
sealed interface CardAction {
    /**
     * 卡片内脚本请求发送一段用户消息。
     *
     * @param text 面向阅读的一句话（气泡显示这一句，也是模型看到的正文）
     * @param cardId 卡片身份；null 表示宿主未注入（旧卡片/非聊天场景）
     * @param data 结构化状态（JSON 文本）；null 表示卡片没有额外状态
     */
    data class Send(
        val text: String,
        val cardId: String? = null,
        val data: String? = null,
    ) : CardAction

    /** 用户请求把卡片保存为工件。 */
    data class Save(val language: String, val content: String) : CardAction
}

/**
 * v1.0.92: 卡片 JS 桥 — 注入为全局对象 [BRIDGE_NAME],卡片脚本通过
 * `window.muse.send(text)`(引导脚本封装,见 [CARD_BRIDGE_BOOTSTRAP_JS])回传动作。
 *
 * 安全边界:
 *  - 仅暴露 [send] / [getData] 两个方法,不允许脚本读取任何宿主数据;
 *    卡片只能拿到宿主主动注入的 [cardId];
 *  - 回调切回主线程执行;空文本丢弃;
 *  - 桥仅在调用方提供回传回调的卡片上注入(聊天消息场景),其余场景(如工件查看)
 *    保持无桥、无脚本的纯渲染。
 */
internal class MuseCardBridge(
    private val onAction: (CardAction) -> Unit,
    /** v2.0: 卡片数据读取(cardId → JSON 文本);null 表示该场景不提供数据。 */
    private val readCardData: ((String) -> String?)? = null,
    /** v2.6.7: 卡片身份(宿主注入);null 表示不注入,卡片端 cardId 为 null。 */
    private val cardId: String? = null,
) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * v2.6.7: 卡片回传。
     *
     * 兼容两种调用形态：
     *  - `send("一句话")`                → 纯文本（旧卡片不用改）
     *  - `send("一句话", {json-ready})`   → 附带结构化状态
     */
    @JavascriptInterface
    fun send(text: String) {
        val trimmed = text.trim().take(MAX_ACTION_TEXT_LENGTH)
        if (trimmed.isEmpty()) return
        mainHandler.post { onAction(CardAction.Send(trimmed, cardId, null)) }
    }

    /**
     * v2.6.7: 带状态回传。data 为 JSON 文本，宿主原样透传给模型。
     *
     * 单独开一个方法而不是重载 [send]，是因为 @JavascriptInterface 的方法重载在
     * WebView 桥下的派发顺序不保证，且旧卡片依赖单参数签名。
     */
    @JavascriptInterface
    fun sendWithData(text: String, data: String?) {
        val trimmed = text.trim().take(MAX_ACTION_TEXT_LENGTH)
        val payload = data?.trim()?.take(MAX_ACTION_DATA_LENGTH)?.takeIf { it.isNotEmpty() }
        if (trimmed.isEmpty() && payload == null) return
        // 纯状态、无摘要时留空串，由 ViewModel 侧补本地化文案。
        mainHandler.post { onAction(CardAction.Send(trimmed, cardId, payload)) }
    }

    /** v2.0: 卡片脚本按 cardId 读取绑定数据(JSON 文本);无数据返回 null。 */
    @JavascriptInterface
    fun getData(cardId: String): String? {
        val id = cardId.trim()
        if (id.isEmpty()) return null
        return readCardData?.invoke(id)
    }

    /**
     * v2.6.7: 能力协商 —— 卡片先问宿主支持什么，再决定显示哪些控件。
     *
     * 借鉴 Hana 卡片手册的做法（`window.card.capabilities()`）：没有协商的桥，
     * 卡片只能盲目写控件，写完才发现桥不可用，静默失败 —— 用户看到的是
     * 「点了没反应」。有协商就能提前把不支持的东西隐藏或降级。
     *
     * 返回纯文本而非 JSON 对象，因为 @JavascriptInterface 只能回 String；
     * 引导脚本会把它包成对象暴露给卡片。
     */
    @JavascriptInterface
    fun capabilities(): String {
        val canEmit = onAction != null
        val canRead = readCardData != null || cardId != null
        return """{"emit":"${if (canEmit) "available" else "requires_host"}",""" +
            """"state":"${if (canEmit) "available" else "requires_host"}",""" +
            """"data":"${if (canRead) "available" else "requires_host"}",""" +
            """"height":"available","cardId":${if (cardId == null) "null" else "\"$cardId\""}}"""
    }

    /** v2.6.7: 卡片读取自己的身份（等价于 window.muse.cardId 的桥侧来源）。 */
    @JavascriptInterface
    fun getCardId(): String? = cardId

    /**
     * v2.6.7: 卡片上报自己的内容高度（CSS px）。
     *
     * 修「大卡片被挤压」：原来只在 onPageFinished 量一次 scrollHeight，
     * 卡片内异步渲染（图表、图片、折叠展开）或用户拖开面板后高度变了，
     * 宿主仍用旧高度裁切，内容就看不见了。
     * 由页面内 ResizeObserver 驱动，每次变高/变矮都重新告知宿主。
     */
    @JavascriptInterface
    fun reportHeight(px: Int) {
        if (px <= 0) return
        mainHandler.post { onHeightChanged?.invoke(px) }
    }

    /** v2.6.7: 高度回调（非 @JavascriptInterface，仅内部使用）。 */
    internal var onHeightChanged: ((Int) -> Unit)? = null

    companion object {
        /** addJavascriptInterface 使用的全局对象名。 */
        const val BRIDGE_NAME = "MuseCardBridge"

        /** 单次回传文本上限(字符),防止脚本滥用注入超长消息。 */
        private const val MAX_ACTION_TEXT_LENGTH = 4000

        /** 单次回传状态上限(字符)。状态是结构化数据，比摘要允许更长，但仍需设上限。 */
        private const val MAX_ACTION_DATA_LENGTH = 16_000

        /**
         * 卡片只回状态、没给摘要时的占位文本。
         *
         * 这里故意留空串：桥是非 Composable 环境，拿不到 stringResource；
         * 真正的用户可见文案在 ChatViewModel 侧用资源填充，避免硬编码中文。
         */
        const val EMPTY_SUMMARY = ""
    }
}

/**
 * v1.0.92: 引导脚本 — 与卡片 HTML 一起注入(在清洗之后拼接,不受清洗正则影响),
 * 为卡片脚本提供 `window.muse` 门面。
 *
 * v2.6.7 新增：
 *   window.muse.cardId                       // 只读，卡片身份（宿主用消息 id 注入）
 *   window.muse.send("摘要", { ...state })    // 带结构化状态回传
 *
 * 卡片内用法:
 *   if (!window.muse) return;                 // 无桥场景（只读渲染）
 *   window.muse.send("把睡眠调到 6 小时", { hours: 6 });
 */
internal const val CARD_BRIDGE_BOOTSTRAP_JS: String = """
(function(){
  if (window.muse && window.muse.send) return;
  window.muse = window.muse || {};
  window.muse.available = true;
  var __museLastTap = 0;
  document.addEventListener('click', function(){ __museLastTap = Date.now(); }, true);
  document.addEventListener('touchend', function(){ __museLastTap = Date.now(); }, true);
  // v2.6.7: 卡片身份。宿主注入，只读；无桥或未注入时为 null。
  try { window.muse.cardId = MuseCardBridge.getCardId(); } catch (e) { window.muse.cardId = null; }
  window.muse.send = function(text, state){
    // 安全守卫:仅允许在用户最近一次点击/触摸后的短暂窗口内回传,
    // 防止卡片脚本在加载时静默注入消息。
    if (Date.now() - __museLastTap > 1500) return;
    var body = String(text == null ? '' : text);
    if (state === undefined || state === null) {
      try { MuseCardBridge.send(body); } catch (e) { /* bridge send failures are non-fatal */ }
      return;
    }
    var payload;
    try { payload = typeof state === 'string' ? state : JSON.stringify(state); }
    catch (e) { payload = null; }
    try { MuseCardBridge.sendWithData(body, payload); } catch (e) { /* non-fatal */ }
  };
  // v2.0: 卡片数据绑定 — 按 cardId 读取宿主绑定的 JSON 数据(show_card/update_card_data 写入)
  // v2.6.7: 不传参时默认读自己这张卡。
  window.muse.getData = function(cardId){
    try {
      var id = (cardId == null || cardId === '') ? window.muse.cardId : String(cardId);
      if (!id) return null;
      return MuseCardBridge.getData(id);
    } catch (e) { return null; }
  };
  // v2.6.7: 便捷读取 —— 解析成对象；解析失败返回 null。
  window.muse.getJSON = function(cardId){
    try {
      var raw = window.muse.getData(cardId);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  };
  // v2.6.7: 能力协商 —— 卡片先问宿主支持什么，再决定显示哪些控件。
  // 用法：
  //   var cap = window.muse.capabilities();
  //   if (cap.emit === 'available') { 渲染可提交控件 }
  // 返回对象永不抛异常；桥不可用时全部标为 requires_host。
  window.muse.capabilities = function(){
    try {
      var raw = MuseCardBridge.capabilities();
      return raw ? JSON.parse(raw) : { emit: 'requires_host', state: 'requires_host', data: 'requires_host', height: 'requires_host', cardId: null };
    } catch (e) {
      return { emit: 'requires_host', state: 'requires_host', data: 'requires_host', height: 'requires_host', cardId: null };
    }
  };
  // v2.6.7: 高度自适应 —— 内容变高告诉宿主，避免大卡片被旧高度挤压。
  // 两个约束防止布局抖动：
  //  1) 80ms 防抖：内容变化密集时只报最后一次，不逐帧跨进程调用；
  //  2) 只增不减：内容变矮不回缩，避免上下跳（折叠面板收起时不需要缩）。
  //     例外：首次上报总是接受（否则从 0 开始涨会被当成“不减”）。
  (function(){
    if (typeof ResizeObserver === 'undefined') return;
    var reported = 0;
    var timer = null;
    function push(){
      timer = null;
      var h = document.body ? Math.ceil(document.body.scrollHeight) : 0;
      if (h <= 0) return;
      if (reported > 0 && h <= reported) return;
      reported = h;
      try { MuseCardBridge.reportHeight(h); } catch (e) { /* non-fatal */ }
    }
    function schedule(){
      if (timer !== null) return;
      timer = setTimeout(push, 80);
    }
    var ro = new ResizeObserver(schedule);
    function start(){
      if (document.body) { ro.observe(document.body); push(); }
      else setTimeout(start, 16);
    }
    start();
    window.addEventListener('load', schedule);
    // 图片/字体等异步资源加载完后高度还会变，补两次。
    setTimeout(schedule, 120);
    setTimeout(schedule, 480);
  })();
})();
"""

/**
 * v2.6.7: 把卡片回传的状态附加到用户消息正文末尾，供模型读取。
 *
 * 用不可见的私有区字符包裹（U+2063 INVISIBLE SEPARATOR），对话气泡渲染时剥离，
 * 不影响用户阅读；模型侧看到的是明确的「卡片状态」段。
 *
 * 为什么不塞进 MessageEntity 新列：消息表已是 DB 116，为一句话的元数据再升一版
 * 迁移，风险和收益不成比例。文本尾巴对模型完全够用，且天然跟着消息一起备份、
 * 导出、参与上下文裁剪。
 */
internal fun appendCardStateToMessage(text: String, cardId: String?, data: String?): String {
    if (data.isNullOrBlank()) return text
    val id = cardId?.takeIf { it.isNotBlank() } ?: "unknown"
    // 校验一次，避免把非法 JSON 直接当结构化状态塞给模型（卡片脚本可能拼错）。
    val normalized = runCatching { JSONObject(data) }.getOrNull()?.toString() ?: data
    return buildString {
        append(text)
        append('\n')
        append(CARD_STATE_OPEN)
        append("cardId=")
        append(id)
        append('\n')
        append(normalized)
        append('\n')
        append(CARD_STATE_CLOSE)
    }
}

/**
 * v2.6.7: 从消息正文里剥掉卡片状态尾巴（渲染用）。
 *
 * 气泡不该显示一串 JSON。模型侧拿到的仍是完整正文，只有 UI 层做剥离。
 */
internal fun stripCardStateFromMessage(text: String): String {
    if (!text.contains(CARD_STATE_OPEN)) return text
    val start = text.indexOf(CARD_STATE_OPEN)
    val end = text.indexOf(CARD_STATE_CLOSE)
    val without = if (end > start) {
        text.removeRange(start, end + CARD_STATE_CLOSE.length)
    } else {
        text.substring(0, start)
    }
    return without.trimEnd()
}

/** 卡片状态段的起止标记（含不可见分隔符，降低与正常文本碰撞的概率）。 */
internal const val CARD_STATE_OPEN: String = "\u2063[card_state]\u2063"
internal const val CARD_STATE_CLOSE: String = "\u2063[/card_state]\u2063"
