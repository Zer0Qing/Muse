package io.zer0.muse.ui.markdown.card

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

    /** 交互桥的全局名。 */
    const val BRIDGE_NAME: String = "MuseCardBridge"

    /**
     * 完整页面脚本。
     *
     * 门面与观测是两段独立脚本：门面在无桥时会自己把能力标成 requires_host，
     * 观测则恒定注入（无桥场景同样需要高度跟随）。
     */
    fun build(): String = buildString {
        append(FACADE_JS)
        append('\n')
        append(observerJs())
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

  // 身份只由宿主产生并注入；卡片端只读。
  window.muse.cardId = null;
  if (bridge) { try { window.muse.cardId = bridge.getCardId(); } catch (e) { } }

  function capability(){
    if (!bridge) {
      return { emit: 'requires_host', state: 'requires_host', data: 'requires_host',
               height: 'available', cardId: null };
    }
    try {
      var raw = bridge.capabilities();
      return raw ? JSON.parse(raw) : { emit: 'requires_host', state: 'requires_host',
                                      data: 'requires_host', height: 'available', cardId: null };
    } catch (e) {
      return { emit: 'requires_host', state: 'requires_host', data: 'requires_host',
               height: 'available', cardId: null };
    }
  }
  window.muse.capabilities = capability;

  // 只允许在用户最近一次点击后的短暂窗口内回传，防止卡片加载时静默注入消息。
  function withinGesture(){ return Date.now() - lastTap <= 1500; }

  window.muse.send = function(text, state){
    if (!bridge || !withinGesture()) return;
    var body = String(text == null ? '' : text);
    if (state === undefined || state === null) {
      try { bridge.send(body); } catch (e) { }
      return;
    }
    var payload;
    try { payload = (typeof state === 'string') ? state : JSON.stringify(state); }
    catch (e) { payload = null; }
    try { bridge.sendWithData(body, payload); } catch (e) { }
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

  // 数据订阅：桥只能被卡片调进来，推不进去，所以用轮询。
  // 只在调用时启动，只比较字符串，内容没变什么也不做。
  window.muse.data = window.muse.data || {};
  window.muse.data.onChange = function(cb){
    if (typeof cb !== 'function') return function(){};
    var last = null;
    var timer = setInterval(function(){
      var raw;
      try { raw = window.muse.getData(); } catch (e) { return; }
      if (raw === last) return;
      last = raw;
      var value = null;
      try { value = raw ? JSON.parse(raw) : null; } catch (e) { value = raw; }
      try { cb(value); } catch (e) { }
    }, 800);
    return function(){ clearInterval(timer); };
  };
})();
"""

    /**
     * 高度观测。
     *
     * 与桥无关：没有交互桥的卡片同样需要高度跟随，只是不能回传。
     * 这条独立性是旧实现踩过的坑 —— 当时高度上报挂在桥上，无桥路径完全没有观测。
     */
    private fun observerJs(): String {
        return """
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

  var ro = (typeof ResizeObserver !== 'undefined') ? new ResizeObserver(schedule) : null;
  function start(){
    if (!document.body) { setTimeout(start, 16); return; }
    if (ro) ro.observe(document.body);
    schedule();
    window.addEventListener('load', schedule);
    setTimeout(schedule, 150);
    setTimeout(schedule, 400);
    setTimeout(schedule, 900);
  }
  start();
})();
""".trimIndent()
    }
}
