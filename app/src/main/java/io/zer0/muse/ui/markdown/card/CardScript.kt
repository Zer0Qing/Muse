package io.zer0.muse.ui.markdown.card

import io.zer0.common.AppJson
import kotlinx.serialization.builtins.serializer

/**
 * 卡片里唯一的一份脚本。
 *
 * ## 为什么必须只有一份
 *
 * 旧实现同时注入两段各自带 `ResizeObserver` 的脚本：
 *  - 桥引导脚本尾部那个 IIFE（上报给 `MuseCardBridge`）
 *  - 独立的高度观测脚本（上报给 `MuseHeightSink`）
 * 两者观察同一个 body、各自防抖、各自判断要不要上报，然后一起改写宿主的高度状态。
 * 谁最后跑谁赢，而它们的时序不可预测 —— 这就是「卡片高度时好时坏、偶尔被裁」的根。
 *
 * 现在：门面与观测分开职责，但**只有这一份**被注入，`CardRuntime` 是唯一装配点。
 *
 * ## 高度观测的取舍
 *
 * 量 `body` 的 border-box，不量 `documentElement.scrollHeight`
 * （内容比视口矮时后者等于视口高，会把卡片抬到整屏）。
 *
 * 变高即时上报（变高不会裁掉内容）；变矮要连续两次测量一致才相信
 * （避开折叠动画的中间态）。阈值 8px，滤掉亚像素抖动。
 *
 * **绝不向 body 写 height / overflow** —— 一旦写，测量就变成了「自己影响自己」，
 * 宿主设高 → 视口变 → 测量变 → 再设高的回授环就成立。这条是硬约束。
 */
internal object CardScript {

    /** 高度上报接口的全局名（由 CardRuntime 注册为 addJavascriptInterface）。 */
    const val HEIGHT_SINK_NAME: String = "MuseHeightSink"

    /**
     * 通知卡片「绑定数据变了」的那一句。宿主用 evaluateJavascript 执行它。
     *
     * 只发信号、不发数据：卡片收到后自己走 `window.muse.getData()` 取值，
     * 与轮询读的是同一条路径，所以两种模式的行为完全一致。
     */
    const val DATA_EVENT_JS: String = "window.__museDataEvent&&window.__museDataEvent()"

    /** 交互桥的全局名。 */
    const val BRIDGE_NAME: String = "MuseCardBridge"

    /** 将宿主的静默响应回传给卡片端 Promise。 */
    fun askResolveJs(requestId: String, responseJson: String): String {
        val idArg = AppJson.encodeToString(String.serializer(), requestId)
        val bodyArg = AppJson.encodeToString(String.serializer(), responseJson)
        return "window.__museAskResolve&&window.__museAskResolve($idArg,$bodyArg)"
    }

    /**
     * 完整页面脚本。
     *
     * 门面与观测是两段独立脚本：门面在无桥时会自己把能力标成 requires_host，
     * 观测则恒定注入（无桥场景同样需要高度跟随）。
     *
     * 结果按进程缓存一次：脚本内容与卡片、主题、数据都无关，而它会被每张卡的
     * 每次 HTML 装配调用。
     */
    fun build(): String = BUILD

    /**
     * 门面脚本单独暴露：它必须在卡片自身的内联脚本之前执行。
     *
     * 卡片脚本在挂载时就会同步调用 `window.muse.getJSON()` / `window.muse.data.onChange()`，
     * 而这些入口原来排在卡片正文之后，导致首次挂载必然抛
     * `Cannot read properties of undefined (reading 'onChange')`，订阅也永远注册不上。
     */
    fun facadeJs(): String = FACADE_JS

    /** 高度观测脚本：需要 document.body，放在卡片正文之后注入。 */
    fun observerJs(): String = OBSERVER_JS

    private val BUILD: String by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        buildString {
            append(FACADE_JS)
            append('\n')
            append(observerJs())
        }
    }

    /**
     * `window.muse` 门面：卡片脚本调用宿主的唯一入口。
     *
     * 无桥时也要定义，只是把能力如实标成 `requires_host` —— 卡片据此降级为静态展示，
     * 而不是写完控件、点了才发现没人接。
     */
    private const val FACADE_JS: String = """
(function(){
  window.muse = window.muse || {};
  var bridge = (typeof MuseCardBridge !== 'undefined') ? MuseCardBridge : null;
  window.muse.available = !!bridge;
  var lastTap = 0;
  document.addEventListener('click', function(){ lastTap = Date.now(); }, true);
  document.addEventListener('touchend', function(){ lastTap = Date.now(); }, true);

  // 身份只由宿主产生并注入；卡片端只读。宿主已通过前置脚本写过 cardId 时不得覆盖成 null，
  // 否则无桥宿主（工作台小应用、产物预览）里 getData() 会永远拿到空 id。
  window.muse.cardId = window.muse.cardId || null;
  if (bridge) { try { window.muse.cardId = bridge.getCardId(); } catch (e) { } }

  function capability(){
    if (!bridge) {
      return { emit: 'requires_host', state: 'requires_host', ask: 'requires_host',
               saveState: 'requires_host', data: 'requires_host', height: 'available', cardId: null };
    }
    try {
      var raw = bridge.capabilities();
      return raw ? JSON.parse(raw) : { emit: 'requires_host', state: 'requires_host', ask: 'requires_host',
                                      saveState: 'requires_host', data: 'requires_host', height: 'available', cardId: null };
    } catch (e) {
      return { emit: 'requires_host', state: 'requires_host', ask: 'requires_host',
               saveState: 'requires_host', data: 'requires_host', height: 'available', cardId: null };
    }
  }
  window.muse.capabilities = capability;

  // 只允许在用户最近一次点击后的短暂窗口内回传，防止卡片加载时静默注入消息。
  function withinGesture(){ return Date.now() - lastTap <= 1500; }

  // 桥调用失败要报回宿主，否则“点了卡片按钮没反应”这件事在两边都没有痕迹。
  function reportBridgeFailure(where, err){
    try {
      bridge && bridge.reportError(where + ': ' + ((err && err.message) ? err.message : String(err)));
    } catch (ignored) {}
  }
  
  window.muse.send = function(text, state){
    if (!bridge || !withinGesture()) return;
    var body = String(text == null ? '' : text);
    if (state === undefined || state === null) {
      try { bridge.send(body); } catch (e) { reportBridgeFailure('send', e); }
      return;
    }
    var payload;
    try { payload = (typeof state === 'string') ? state : JSON.stringify(state); }
    catch (e) { payload = null; }
    try { bridge.sendWithData(body, payload); } catch (e) { reportBridgeFailure('sendWithData', e); }
  };

  window.__museAskPending = window.__museAskPending || {};
  window.__museAskResolve = function(reqId, rawJson){
    var cb = window.__museAskPending[reqId];
    if (!cb) return;
    delete window.__museAskPending[reqId];
    var parsed;
    try { parsed = (typeof rawJson === 'string') ? JSON.parse(rawJson) : rawJson; }
    catch (e) { parsed = { ok: false, error: 'invalid_json', text: String(rawJson || '') }; }
    try { cb(parsed); } catch (ignored) {}
  };
  window.muse.ask = function(input){
    return new Promise(function(resolve){
      if (!bridge) { resolve({ ok: false, error: 'requires_host' }); return; }
      if (!withinGesture()) { resolve({ ok: false, error: 'requires_gesture' }); return; }
      var req = (typeof input === 'string') ? { prompt: input } : (input || {});
      var reqId = 'ask_' + Date.now() + '_' + Math.floor(Math.random() * 100000);
      var timer = setTimeout(function(){
        if (window.__museAskPending[reqId]) {
          delete window.__museAskPending[reqId];
          resolve({ ok: false, error: 'timeout' });
        }
      }, 35000);
      window.__museAskPending[reqId] = function(res){
        clearTimeout(timer);
        resolve(res);
      };
      try {
        var ok = bridge.ask(reqId, JSON.stringify(req));
        if (!ok) {
          clearTimeout(timer);
          delete window.__museAskPending[reqId];
          resolve({ ok: false, error: 'requires_host' });
        }
      } catch (e) {
        clearTimeout(timer);
        delete window.__museAskPending[reqId];
        reportBridgeFailure('ask', e);
        resolve({ ok: false, error: 'bridge_error' });
      }
    });
  };
  window.muse.saveState = function(state){
    if (!bridge || state === undefined || state === null) return false;
    var payload;
    try { payload = (typeof state === 'string') ? state : JSON.stringify(state); }
    catch (e) { return false; }
    try { return !!bridge.saveState(payload); }
    catch (e) { reportBridgeFailure('saveState', e); return false; }
  };

  // 声明式回传：元素上写 data-m-invoke="要发的话"，点一下就走 send。
  // 可选 data-m-state='{"k":1}' 带上结构化状态。
  // 走 capture 阶段，这样卡片自己绑的 click 也拦不住它；preventDefault 避免整页跳转。
  document.addEventListener('click', function(e){
    var node = e.target;
    while (node && node !== document && !(node.getAttribute && node.getAttribute('data-m-invoke'))) {
      node = node.parentNode;
    }
    if (!node || node === document || !node.getAttribute) return;
    var text = node.getAttribute('data-m-invoke') || '';
    if (!text) return;
    e.preventDefault();
    var raw = node.getAttribute('data-m-state');
    var payload;
    if (raw) { try { payload = JSON.parse(raw); } catch (err) { payload = undefined; } }
    window.muse.send(text, payload);
  }, true);

  window.muse.getData = function(cardId){
    if (!bridge) return null;
    try {
      var id = (cardId == null || cardId === '') ? window.muse.cardId : String(cardId);
      if (!id) return null;
      return bridge.getData(id);
    } catch (e) { return null; }
  };

  window.muse.getJSON = function(cardId){
    try {
      var raw = window.muse.getData(cardId);
      return raw ? JSON.parse(raw) : null;
    } catch (e) { return null; }
  };

  // 数据订阅：桥只能被卡片调进来，宿主反向通知要靠 evaluateJavascript。
  // 宿主支持推送时（本页由 CardRuntime 注入 __museDataPush）走事件，变化才触发；
  // 不支持时才退回 800ms 轮询 —— 降级路径保留，避免非聊天场景（无 CardDataStore）
  // 的卡片失去刷新能力。
  window.muse.data = window.muse.data || {};
  var __museSubs = [];
  function __museRemove(entry){
    var i = __museSubs.indexOf(entry);
    if (i >= 0) __museSubs.splice(i, 1);
  }
  function __museRead(entry){
    var raw;
    try { raw = window.muse.getData(); } catch (e) { return; }
    // 只在值真的变了才回调：轮询与推送走同一口径，避免推送风暴下重复重绘。
    if (raw === entry.last) return;
    entry.last = raw;
    var value = null;
    try { value = raw ? JSON.parse(raw) : null; } catch (e) { value = raw; }
    try { entry.cb(value); } catch (e) { }
  }
  window.muse.data.onChange = function(cb){
    if (typeof cb !== 'function') return function(){};
    var entry = { cb: cb, last: null, timer: null };
    __museSubs.push(entry);
    if (window.__museDataPush) {
      // 订阅时补读一次：覆盖「订阅之前数据就已经存在」和「推送落在页面加载期间」两种情形。
      setTimeout(function(){ __museRead(entry); }, 0);
      return function(){ __museRemove(entry); };
    }
    entry.timer = setInterval(function(){ __museRead(entry); }, 800);
    return function(){ clearInterval(entry.timer); __museRemove(entry); };
  };
  // 宿主推送入口：只表示「可能变了」，取值仍走桥。
  window.__museDataEvent = function(){
    var snapshot = __museSubs.slice();
    for (var i = 0; i < snapshot.length; i++) __museRead(snapshot[i]);
  };
})();
"""

    /**
     * 高度观测。
     *
     * 与桥无关：没有交互桥的卡片同样需要高度跟随，只是不能回传。
     * 这条独立性是旧实现踩过的坑 —— 当时高度上报挂在桥上，无桥路径完全没有观测。
     */
    private val OBSERVER_JS: String = """
(function(){
  if (window.__museHeightObserver) return;
  window.__museHeightObserver = true;
  // 高度只走 height sink：桥只管回传，不管高度。
  var sink = (typeof MuseHeightSink !== 'undefined') ? MuseHeightSink : null;
  if (!sink) return;

  var reported = 0;
  var pending = -1;
  var pendingCount = 0;
  var timer = null;

  function measure(){
    var b = document.body;
    if (!b) return 0;
    // 视口还没建立时不要量：宽度接近 0 会让每个字单独折行，
    // 量出来的高度是几千像素的假值，写进宿主就会把卡片撑成一屏空白。
    if (window.innerWidth < 80 || window.innerHeight < 40) return 0;
    var box = b.getBoundingClientRect().height;
    var content = b.scrollHeight;
    // 两者取大：盒子可能比内容矮（内容溢出），也可能因为 margin 折叠比内容高。
    // 只信其中一个都会出错 —— 信盒子就裁内容，信内容就漏掉底部留白。
    var h = Math.max(box, content);
    if (h <= 0) h = b.offsetHeight;
    return Math.ceil(h);
  }

  function push(h){
    reported = h;
    pending = -1;
    pendingCount = 0;
    try { sink.reportHeight(h); } catch (e) { }
  }

  function evaluate(){
    timer = null;
    var h = measure();
    if (h <= 0) return;
    if (reported === 0) { push(h); return; }
    var delta = h - reported;
    if (Math.abs(delta) < 8) return;
    if (delta > 0) { push(h); return; }
    if (pending === h) { pendingCount += 1 } else { pending = h; pendingCount = 1 }
    if (pendingCount >= 2) push(h);
  }

  function schedule(){
    if (timer !== null) return;
    timer = setTimeout(evaluate, 80);
  }

  // 兜底补测：只在「还没量到任何高度」时才补。
  // 每次 evaluate 都要 getBoundingClientRect + scrollHeight，等于强制一次布局；
  // 卡片多、滚动进出频繁时，150/400/900 三个无条件定时器就是每个卡片实例 3 次多余布局。
  // 首次成功上报之后 ResizeObserver 会继续负责后续变化，兜底没有存在价值。
  function fallback(){
    if (reported > 0) return;
    schedule();
  }

  var ro = (typeof ResizeObserver !== 'undefined') ? new ResizeObserver(schedule) : null;
  function start(){
    if (!document.body) { setTimeout(start, 16); return; }
    if (ro) ro.observe(document.body);
    schedule();
    window.addEventListener('load', fallback);
    setTimeout(fallback, 150);
    setTimeout(fallback, 400);
    setTimeout(fallback, 900);
  }
  start();
})();
    """.trimIndent()
}
